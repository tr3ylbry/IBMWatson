package main;

import jakarta.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static main.PackCatalog.*;

/** Private bounded workbench. Requests refer only to managed IDs, never client paths or commands. */
final class TopicBuilder implements AutoCloseable {
    interface ApiFactory { WikiApi create() throws IOException; }
    record Artifact(String kind, String label, Path path) {}
    static final int MAX_JOBS = 32;
    private final Path root, workspace;
    private final Map<String, Path> targets;
    private final Map<String, Artifact> seeds;
    private final ApiFactory apis;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(GameService.daemonThreads("cluelab-admin-job"));
    private final Map<String, JsonObject> live = new HashMap<>();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private String active;
    private boolean closed;
    private final TopicRequests playerRequests;
    private final ProposalAssistance assistance;

    TopicBuilder(Path root, Path workspace, Map<String, Path> targets, Map<String, Artifact> seeds, ApiFactory apis) throws IOException {
        this(root,workspace,targets,seeds,apis,null);
    }
    TopicBuilder(Path root, Path workspace, Map<String, Path> targets, Map<String, Artifact> seeds, ApiFactory apis, TopicRequests playerRequests) throws IOException {
        this(root,workspace,targets,seeds,apis,playerRequests,ProposalProvider.environment(System.getenv()));
    }
    TopicBuilder(Path root, Path workspace, Map<String, Path> targets, Map<String, Artifact> seeds, ApiFactory apis, TopicRequests playerRequests,ProposalProvider provider) throws IOException {
        this.root = root.toAbsolutePath().normalize(); this.workspace = workspace; this.targets = Map.copyOf(targets); this.seeds = Map.copyOf(seeds); this.apis = apis;
        this.playerRequests = playerRequests;
        this.assistance=new ProposalAssistance(provider);
        privateWorkspace(root); Files.createDirectories(root.resolve("jobs"));
        lockChannel = FileChannel.open(root.resolve(".server.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try { lock = lockChannel.tryLock(); require(lock != null, "Another topic builder owns this workspace"); }
        catch (IOException | OverlappingFileLockException error) { lockChannel.close(); throw new IOException("Another topic builder owns this workspace", error); }
        try {
            for (String id : ids()) if (!Files.exists(jobPath(id).resolve("outcome/result.json"))) {
                if(request(id).getString("operation").equals("generate"))recoverGeneration(id);
                else finish(id, "interrupted", Json.createObjectBuilder().add("message", "Admin process stopped before recording completion. Inspect committed artifacts; start a new job, never assume it failed before committing.").build());
            }
        } catch (IOException error) { close(); throw error; }
    }
    static Map<String, Artifact> examples() {
        return Map.of("jovian-preview", new Artifact("preview", "Saved Jovian Search", Path.of("drafts/jovian-v1/preview")),
                "jovian-capture", new Artifact("capture", "Saved Jovian Sources", Path.of("drafts/jovian-v1/capture")),
                "jovian-original", new Artifact("export", "Jovian Original Draft 1.0.0", Path.of("drafts/jovian-v1/export")),
                "jovian-revised", new Artifact("export", "Jovian Revised Draft 1.0.1", Path.of("drafts/jovian-v2/export")),
                "jovian-proposals", new Artifact("proposals", "Jovian Revised Proposals", Path.of("drafts/jovian-v2/proposals.json")),
                "jovian-decision", new Artifact("decision", "Jovian Assistant Decision (Not Human Approval)", Path.of("drafts/jovian-v2/review.json")));
    }
    static void privateWorkspace(Path root) throws IOException {
        IntakeFiles.safe(root); Files.createDirectories(root);
        require(Files.getFileStore(root).supportsFileAttributeView("posix"), "Private workshop storage requires a POSIX filesystem");
        Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    }
    private List<String> ids() throws IOException {
        try (var stream = Files.list(root.resolve("jobs"))) {
            List<String> ids = stream.filter(Files::isDirectory).map(p -> p.getFileName().toString()).filter(TopicBuilder::uuid).sorted().limit(MAX_JOBS + 1).toList();
            require(ids.size() <= MAX_JOBS, "Workbench limit reached; preserve/archive the workspace before a new one"); return ids;
        }
    }
    static boolean uuid(String id) { return id != null && id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"); }
    private Path jobPath(String id) throws IOException { require(uuid(id), "Invalid managed job ID"); Path p = root.resolve("jobs").resolve(id); IntakeFiles.safe(p); return p; }
    private JsonObject request(String id) throws IOException { return IntakeFiles.read(jobPath(id).resolve("request.json")); }
    synchronized JsonObject submit(JsonObject input) throws IOException {
        require(!closed, "Workbench is closing"); String id = string(input, "requestId", 36); Path path = jobPath(id);
        String operation = string(input, "operation", 30);
        String fields = switch (operation) {
            case "preview" -> "topic limit";
            case "select" -> "artifactId pageIds acknowledgeRights";
            case "capture" -> "artifactId";
            case "validate", "export" -> "captureId proposalJson generationId";
            case "generate" -> "captureId difficulty count draftId version topicId label description confirmed retryOf acknowledgeRetry";
            case "review" -> "beforeId draftId decisionJson";
            case "plan" -> "reviewId targetId catalogVersion";
            case "activate", "rollback" -> "planId confirmPlanId confirmed";
            default -> throw new IOException("Unsupported workbench operation");
        };
        keys(input, "job request", "requestId operation playerRequestId playerTargetId " + fields);
        if (Files.exists(path.resolve("request.json"))) { require(request(id).equals(input), "Retry ID conflicts with its original request; saved work was not overwritten"); return status(id); }
        if(operation.equals("generate")){assistance.available();ProposalAssistance.validateInput(input);ProposalAssistance.sources(data(string(input,"captureId",100),"capture"));}
        linked(input, true);
        require(active == null, "Another job is active; recover or cancel it before starting another");
        require(ids().size() < MAX_JOBS, "Workbench has 32 jobs; preserve/archive it and start a new workspace");
        IntakeFiles.publish(path, Map.of("request.json", IntakeFiles.json(input)), new WikiApi.Budget(null));
        active = id; live.put(id, snapshot(id, operation, "queued", Json.createObjectBuilder().add("message", "Waiting for the single workbench worker").build()));
        worker.submit(() -> run(id, input)); return status(id);
    }
    private void run(String id, JsonObject input) {
        String operation = input.getString("operation");
        try {
            progress(id, "Running " + operation, JsonValue.EMPTY_JSON_OBJECT);
            JsonObject linked = linked(input, true);
            if (linked != null) {
                JsonObject begun = playerRequests.operator(linked.getString("id"),linked.getString("targetKey"),event(id,"begin"),"begin","Operator preparation has begun in the private workshop.",null);
                require(!TopicRequests.TERMINAL.contains(begun.getString("phase")), "Request was closed before work began");
            }
            JsonObject result = execute(id, input);
            completeLink(id,input,result);
            String state = result.getBoolean("partial", false) ? "partial" : "succeeded";
            finish(id, state, result);
        } catch (Exception error) {
            try {
                if(error instanceof ProposalAssistance.Failure f)finish(id,f.state,f.details);
                else if(operation.equals("generate")&&Files.exists(jobPath(id).resolve("dispatch/marker.json")))finish(id,Files.exists(jobPath(id).resolve("received/response.json"))?"cancelled":"uncertain",ProposalAssistance.stopped(jobPath(id)));
                else finish(id, error instanceof InterruptedIOException ? "cancelled" : "failed", Json.createObjectBuilder().add("message", safeMessage(error)).build());
            }
            catch (IOException logging) { synchronized (this) { live.put(id, snapshot(id, operation, "interrupted", Json.createObjectBuilder().add("message", "Result could not be saved; inspect committed artifacts before retrying").build())); } }
        } finally { synchronized (this) { active = null; } }
    }
    private synchronized void progress(String id, String message, JsonObject details) {
        JsonObject old = live.get(id);
        live.put(id, snapshot(id, old == null ? "capture" : old.getString("operation"), Files.exists(root.resolve("jobs/" + id + "/cancel")) ? "cancelling" : "running",
                Json.createObjectBuilder().add("message", message).add("progress", details).build()));
    }
    private void finish(String id, String state, JsonObject result) throws IOException {
        JsonObject input=request(id);
        if(input.getString("operation").equals("generate")){
            JsonObjectBuilder retry=Json.createObjectBuilder();for(String key:List.of("captureId","difficulty","count","draftId","version","topicId","label","description"))retry.add(key,input.get(key));
            Path intent=jobPath(id).resolve("attempt/intent.json");
            boolean canPrepareRetry=Files.exists(intent)&&IntakeFiles.read(intent).getInt("retryNumber")==0&&Set.of("failed","cancelled","interrupted","uncertain").contains(state);
            result=Json.createObjectBuilder(result).add("retryInput",retry).add("retryEligible",canPrepareRetry).build();
        }
        JsonObject value = Json.createObjectBuilder(snapshot(id, input.getString("operation"), state, result)).add("completedAt", Instant.now().toString()).build();
        IntakeFiles.publish(jobPath(id).resolve("outcome"), Map.of("result.json", IntakeFiles.json(value)), new WikiApi.Budget(null));
        synchronized (this) { live.remove(id); }
    }
    private JsonObject snapshot(String id, String operation, String state, JsonObject result) {
        return Json.createObjectBuilder().add("id", id).add("operation", operation).add("state", state).add("result", result)
                .add("cancellable", Set.of("preview", "capture", "generate").contains(operation) && Set.of("queued", "running", "cancelling").contains(state)).build();
    }
    synchronized JsonObject status(String id) throws IOException {
        if (live.containsKey(id)) return live.get(id);
        request(id); Path result = jobPath(id).resolve("outcome/result.json");
        require(Files.exists(result), "Job is not available yet; reconnect"); return IntakeFiles.read(result);
    }
    synchronized JsonObject cancel(String id) throws IOException {
        JsonObject status = status(id); if (!status.getBoolean("cancellable")) return status;
        Path marker = jobPath(id).resolve("cancel"); if (!Files.exists(marker)) Files.createFile(marker);
        progress(id, "Cancellation requested; waiting for bounded source work to stop", JsonValue.EMPTY_JSON_OBJECT); return status(id);
    }
    private Artifact artifact(String id, String kind) throws IOException {
        Artifact seed = seeds.get(id);
        if (seed != null) { require(seed.kind.equals(kind), "Wrong artifact kind; expected " + kind); IntakeFiles.safe(seed.path); return seed; }
        JsonObject r = request(id); require(r.getString("operation").equals(kind), "Wrong artifact kind; expected " + kind);
        Path p = jobPath(id).resolve("artifact"); require(Files.isDirectory(p), "This job has no committed artifact; inspect its outcome");
        return new Artifact(kind, kind + " " + id.substring(0, 8), p);
    }
    private JsonObject data(String id, String kind) throws IOException { return IntakeFiles.read(artifact(id, kind).path.resolve(kind + ".json")); }
    private Path target(String id) throws IOException { require(targets.containsKey(id), "Unknown activation target; use an advertised target ID"); return targets.get(id); }
    private static String event(String id,String phase) { return UUID.nameUUIDFromBytes((id+":"+phase).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    private JsonObject linked(JsonObject input, boolean activeOnly) throws IOException {
        if (!input.containsKey("playerRequestId") && !input.containsKey("playerTargetId")) return null;
        require(playerRequests != null, "Player request handoff is not enabled on this workshop");
        String targetId=string(input,"playerTargetId",100), id=string(input,"playerRequestId",36); TopicRequests.uuid(id);
        JsonObject row=playerRequests.operatorGet(id,PlayerReadiness.targetKey(target(targetId)));
        if (activeOnly) require(!TopicRequests.TERMINAL.contains(row.getString("phase")),"This player request is closed; private work cannot reopen it");
        if (input.containsKey("targetId")) require(targetId.equals(input.getString("targetId")),"Plan target differs from the player request target");
        if (Set.of("activate","rollback").contains(input.getString("operation"))) require(targetId.equals(request(input.getString("planId")).getString("targetId")),"Activation plan belongs to a different player target");
        return row;
    }
    private void completeLink(String job, JsonObject input, JsonObject result) throws IOException {
        JsonObject row=linked(input,false); if(row==null)return;
        String op=input.getString("operation");
        if(op.equals("plan")){
            JsonObject plan=IntakeFiles.read(jobPath(job).resolve("artifact/plan.json"));
            PackCatalog.Pack pack=PackCatalog.load(jobPath(job).resolve("artifact/prepared/catalog.json")).topics.get(plan.getString("topicId"));
            require(pack.eligible(row.getString("difficulty")).size()>=5,"Reviewed candidate has fewer than five clues at the requested difficulty");
            playerRequests.operator(row.getString("id"),row.getString("targetKey"),event(job,"finish"),"offer","Operator proposed a reviewed local pack. Explicit player choice, activation and loaded-player verification are still required.",PlayerReadiness.binding(pack,row.getString("difficulty")));
        }else if(Set.of("generate","export","review","activate","rollback").contains(op)){
            playerRequests.operator(row.getString("id"),row.getString("targetKey"),event(job,"finish"),"awaiting-review","Private preparation step completed. Review, exact player choice and loaded catalog/index verification are separate gates.",null);
        }
    }
    JsonObject inbox() throws IOException {
        JsonArrayBuilder rows=Json.createArrayBuilder();
        if(playerRequests!=null)for(JsonObject row:playerRequests.inbox()){
            String targetId=null;for(var target:targets.entrySet())if(PlayerReadiness.targetKey(target.getValue()).equals(row.getString("targetKey")))targetId=target.getKey();
            JsonObject readiness=targetId==null?PlayerReadiness.result(false,"target-not-mounted","This request target is not mounted in this workshop.")
                    :row.isNull("offered")?PlayerReadiness.result(false,"preparation-required","Manual preparation or an explicit active-pack choice is required.")
                    :PlayerReadiness.check(playerRequests.root,target(targetId),row.getJsonObject("offered"));
            if(TopicRequests.TERMINAL.contains(row.getString("phase")))readiness=PlayerReadiness.result(false,"request-closed","Request closed; late private results cannot reopen it.");
            JsonObject view=PlayerReadiness.publicView(row,readiness,List.of());
            rows.add(Json.createObjectBuilder(view).add("targetId",targetId==null?"not-mounted":targetId).add("mounted",targetId!=null));
        }
        return Json.createObjectBuilder().add("enabled",playerRequests!=null).add("requests",rows).build();
    }
    JsonObject requestTransition(JsonObject input) throws IOException {
        keys(input,"operator request transition","requestId targetId retryId action message");require(playerRequests!=null,"Request inbox is not enabled");
        String action=string(input,"action",30);require(Set.of("awaiting-review","failed","unavailable","clarification").contains(action),"Preparation begins only when a linked workshop job starts");
        playerRequests.operator(string(input,"requestId",36),PlayerReadiness.targetKey(target(string(input,"targetId",100))),string(input,"retryId",36),action,string(input,"message",300),null);return inbox();
    }
    private JsonObject execute(String id, JsonObject input) throws Exception {
        Path job = jobPath(id), output = job.resolve("artifact"); WikiApi.Budget budget = new WikiApi.Budget(job.resolve("cancel"));
        switch (input.getString("operation")) {
            case "generate": return assistance.generate(job,root.resolve("jobs"),input,data(string(input,"captureId",100),"capture"),new WikiApi.Budget(job.resolve("cancel"),Duration.ofSeconds(ProposalProvider.TIMEOUT_SECONDS)));
            case "preview": {
                JsonObject preview = apis.create().preview(string(input, "topic", 150), integer(input, "limit"), budget);
                IntakeFiles.publish(output, Map.of("preview.json", IntakeFiles.json(preview)), budget); return Json.createObjectBuilder().add("artifactId", id).build();
            }
            case "select": {
                require(input.getBoolean("acknowledgeRights", false), "Inspect source terms and explicitly acknowledge attribution/rights first");
                Set<Integer> ids = new LinkedHashSet<>(); for (JsonValue v : array(input, "pageIds")) { require(v instanceof JsonNumber, "Page IDs must be integers"); require(ids.add(((JsonNumber) v).intValueExact()), "Duplicate selected page ID"); }
                JsonObject selection = SourceIntake.select(data(string(input, "artifactId", 100), "preview"), ids);
                IntakeFiles.publish(output, Map.of("select.json", IntakeFiles.json(selection)), budget); return Json.createObjectBuilder().add("artifactId", id).build();
            }
            case "capture": {
                JsonObject capture = SourceIntake.capture(data(string(input, "artifactId", 100), "select"), apis.create(), budget,
                        row -> progress(id, "Capturing " + row.getString("title"), row));
                IntakeFiles.publish(output, Map.of("capture.json", IntakeFiles.json(capture)), budget);
                return Json.createObjectBuilder(SourceIntake.captureReport(capture)).add("artifactId", id).add("partial", !array(capture, "failures").isEmpty()).build();
            }
            case "validate": case "export": {
                JsonObject capture = data(string(input, "captureId", 100), "capture"), proposals = IntakeFiles.parse(jsonText(input, "proposalJson"), "proposal import");
                if("provider-assisted".equals(proposals.getJsonObject("authoring").getString("method",""))){
                    String generation=string(input,"generationId",36);JsonObject original=IntakeFiles.read(artifact(generation,"generate").path.resolve("proposals.json"));
                    JsonObject provenance=original.getJsonObject("authoring").getJsonObject("assistance");
                    require(sameJson(provenance,proposals.getJsonObject("authoring").getJsonObject("assistance")),"Assistance provenance changed; reopen its original managed artifact");
                    require(sha256(IntakeFiles.json(capture)).equals(proposals.getJsonObject("authoring").getJsonObject("assistance").getString("captureSha256")),"Generated draft belongs to another capture");
                    proposals=Json.createObjectBuilder(proposals).add("authoring",Json.createObjectBuilder(proposals.getJsonObject("authoring")).add("assistance",provenance)).build();
                }else require(!input.containsKey("generationId"),"Manual proposals cannot relabel a linked generation attempt");
                if (input.getString("operation").equals("export")) {
                    DraftExporter.exportWorkspace(capture, proposals, output, budget, workspace, PackActivation.catalogPath(target("normal")));
                    return Json.createObjectBuilder().add("artifactId", id).add("validation", PackTool.validation(DraftExporter.validate(output)))
                            .add("screening", IntakeFiles.read(output.resolve("private/review.json"))).build();
                }
                // Diagnostics must not reserve a published content version. Only explicit export does that.
                Path temporary = Files.createTempDirectory(job, ".draft-validation-");
                try {
                    Path diagnostic = temporary.resolve("export"); DraftExporter.export(capture, proposals, diagnostic, budget);
                    JsonObject validation = PackTool.validation(DraftExporter.validate(diagnostic)), screening = IntakeFiles.read(diagnostic.resolve("private/review.json"));
                    IntakeFiles.publish(output, Map.of("proposals.json", IntakeFiles.json(proposals), "validation.json", IntakeFiles.json(validation), "screening.json", IntakeFiles.json(screening)), budget);
                    return Json.createObjectBuilder().add("artifactId", id).add("validation", validation).add("screening", screening).build();
                } finally { try (var files = Files.walk(temporary)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); } }
            }
            case "review": {
                Path before = artifact(string(input, "beforeId", 100), "export").path, draft = artifact(string(input, "draftId", 100), "export").path;
                JsonObject decision = IntakeFiles.parse(jsonText(input, "decisionJson"), "review decision");
                IntakeFiles.publish(job.resolve("decision"), Map.of("review.json", IntakeFiles.json(decision)), budget);
                try { ContentReview.preview(before, draft, job.resolve("decision/review.json"), output); }
                catch (IOException error) { if (!Files.exists(output.resolve("preview.json"))) throw error; }
                JsonObject preview = IntakeFiles.read(output.resolve("preview.json"));
                return Json.createObjectBuilder().add("artifactId", id).add("partial", !preview.getJsonArray("blockingFailures").isEmpty()).add("preview", preview).build();
            }
            case "plan": {
                String reviewId = string(input, "reviewId", 100); artifact(reviewId, "review"); JsonObject reviewed = request(reviewId);
                JsonObject plan = PackActivation.plan(target(string(input, "targetId", 100)), artifact(reviewed.getString("draftId"), "export").path,
                        jobPath(reviewId).resolve("decision/review.json"), string(input, "catalogVersion", 40), output);
                return Json.createObjectBuilder().add("artifactId", id).add("plan", safePlan(plan, input.getString("targetId"))).build();
            }
            case "activate": case "rollback": {
                String planId = string(input, "planId", 100); Path plan = artifact(planId, "plan").path; JsonObject p = IntakeFiles.read(plan.resolve("plan.json"));
                require(input.getBoolean("confirmed", false) && p.getString("planId").equals(string(input, "confirmPlanId", 64)), "Confirm the exact displayed plan, manual restart, two-hour expiry and recovery limits");
                Path config = target(request(planId).getString("targetId"));
                JsonObject receipt = PackActivation.apply(config, plan, input.getString("operation").equals("rollback"), phase -> progress(id, "Catalog transaction: " + phase, JsonValue.EMPTY_JSON_OBJECT));
                return Json.createObjectBuilder().add("receipt", redact(receipt)).add("current", current(planId)).build();
            }
            default: throw new IOException("Unsupported operation");
        }
    }
    synchronized JsonObject catalog() throws IOException {
        JsonArrayBuilder artifacts = Json.createArrayBuilder(), jobs = Json.createArrayBuilder(), configs = Json.createArrayBuilder();
        seeds.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> artifacts.add(Json.createObjectBuilder().add("id", e.getKey()).add("kind", e.getValue().kind).add("label", e.getValue().label)));
        for (String id : ids()) {
            JsonObject s = status(id); JsonObjectBuilder summary = Json.createObjectBuilder().add("id", id).add("operation", s.getString("operation")).add("state", s.getString("state")).add("cancellable", s.getBoolean("cancellable"));
            if (s.containsKey("completedAt")) summary.add("completedAt", s.getString("completedAt")); jobs.add(summary);
            if (Files.isDirectory(jobPath(id).resolve("artifact"))) artifacts.add(Json.createObjectBuilder().add("id", id).add("kind", s.getString("operation")).add("label", s.getString("operation") + " " + id.substring(0, 8)));
        }
        for (var t : new TreeMap<>(targets).entrySet()) {
            byte[] bytes = IntakeFiles.read(PackActivation.catalogPath(t.getValue()), 4 * 1024 * 1024); JsonObject config = IntakeFiles.parse(IntakeFiles.utf8(bytes), "managed catalog");
            configs.add(Json.createObjectBuilder().add("id", t.getKey()).add("label", t.getKey().equals("normal") ? "Normal Local Game" : "Isolated Workshop")
                    .add("catalogId", config.getString("id")).add("catalogVersion", config.getString("version")).add("catalogSha256", sha256(bytes)));
        }
        return Json.createObjectBuilder().add("artifacts", artifacts).add("jobs", jobs).add("targets", configs).add("maxJobs", MAX_JOBS).add("assistance",assistance.description()).build();
    }
    JsonObject open(String id) throws IOException {
        Artifact seed = seeds.get(id);
        if (seed != null) {
            if (Set.of("proposals", "decision").contains(seed.kind)) return Json.createObjectBuilder().add("kind", seed.kind).add("data", IntakeFiles.read(seed.path)).build();
            return view(id, seed.kind);
        }
        return view(id, request(id).getString("operation"));
    }
    private JsonObject view(String id, String kind) throws IOException {
        Artifact a = artifact(id, kind); JsonObjectBuilder out = Json.createObjectBuilder().add("kind", kind);
        switch (kind) {
            case "generate": out.add("data",IntakeFiles.read(a.path.resolve("proposals.json"))).add("report",IntakeFiles.read(a.path.resolve("report.json"))).add("capture",IntakeFiles.read(a.path.resolve("capture.json"))).add("captureId",request(id).getString("captureId"));break;
            case "preview", "select", "capture": out.add("data", data(id, kind)); break;
            case "validate":
                if (Files.exists(a.path.resolve("proposals.json"))) out.add("data", IntakeFiles.read(a.path.resolve("proposals.json"))).add("screening", IntakeFiles.read(a.path.resolve("screening.json"))).add("validation", IntakeFiles.read(a.path.resolve("validation.json")));
                else out.add("data", IntakeFiles.read(a.path.resolve("private/proposals.json"))).add("screening", IntakeFiles.read(a.path.resolve("private/review.json"))).add("validation", PackTool.validation(DraftExporter.validate(a.path)));
                break;
            case "export": out.add("data", IntakeFiles.read(a.path.resolve("private/proposals.json"))).add("screening", IntakeFiles.read(a.path.resolve("private/review.json")))
                    .add("manifestSha256", hash(a.path.resolve("manifest.json"))).add("validation", PackTool.validation(DraftExporter.validate(a.path))); break;
            case "review": out.add("data", IntakeFiles.read(a.path.resolve("preview.json"))); break;
            case "plan": out.add("data", safePlan(IntakeFiles.read(a.path.resolve("plan.json")), request(id).getString("targetId"))).add("current", current(id)); break;
            default: throw new IOException("This job has no reopenable artifact");
        }
        return out.build();
    }
    private JsonObject current(String id) throws IOException {
        JsonObject plan = IntakeFiles.read(artifact(id, "plan").path.resolve("plan.json")); Path config = target(request(id).getString("targetId"));
        byte[] bytes = IntakeFiles.read(PackActivation.catalogPath(config), 4 * 1024 * 1024); String actual = sha256(bytes);
        return Json.createObjectBuilder().add("catalogSha256", actual).add("catalogVersion", IntakeFiles.parse(IntakeFiles.utf8(bytes), "managed catalog").getString("version"))
                .add("state", actual.equals(plan.getString("afterCatalogSha256")) ? "candidate-active" : actual.equals(plan.getString("beforeCatalogSha256")) ? "previous-active" : "different-catalog").build();
    }
    private JsonObject safePlan(JsonObject plan, String target) {
        JsonObjectBuilder b = Json.createObjectBuilder(); for (String key : List.of("planId", "beforeCatalogSha256", "afterCatalogSha256", "candidateDigest", "catalogVersion", "topicId", "availability", "limitations", "restartRequired", "restartClearsInMemorySessions")) b.add(key, plan.get(key));
        return b.add("targetId", target).add("requiredIndexes", redact(plan.getJsonArray("requiredIndexes"))).build();
    }
    private static JsonValue redact(JsonValue value) {
        if (value instanceof JsonObject object) { JsonObjectBuilder out = Json.createObjectBuilder(); object.forEach((key, item) -> { if (!Set.of("path", "base", "sourceFile", "configPath", "catalogPath", "draftPath", "reviewPath").contains(key)) out.add(key, redact(item)); }); return out.build(); }
        if (value instanceof JsonArray array) { JsonArrayBuilder out = Json.createArrayBuilder(); array.forEach(v -> out.add(redact(v))); return out.build(); } return value;
    }
    static String hash(Path path) throws IOException { return sha256(IntakeFiles.read(path, 4 * 1024 * 1024)); }
    private static boolean sameJson(JsonValue a,JsonValue b){
        if(a instanceof JsonNumber x&&b instanceof JsonNumber y)return x.bigDecimalValue().compareTo(y.bigDecimalValue())==0;
        if(a instanceof JsonObject x&&b instanceof JsonObject y)return x.keySet().equals(y.keySet())&&x.keySet().stream().allMatch(k->sameJson(x.get(k),y.get(k)));
        if(a instanceof JsonArray x&&b instanceof JsonArray y){if(x.size()!=y.size())return false;for(int i=0;i<x.size();i++)if(!sameJson(x.get(i),y.get(i)))return false;return true;}
        return Objects.equals(a,b);
    }
    private static String jsonText(JsonObject row, String key) throws IOException {
        require(row.get(key) instanceof JsonString, key + ": expected private JSON text");
        String value = row.getString(key); require(!value.isBlank() && value.length() <= 1024 * 1024, key + ": import must be 1-1048576 characters"); return value;
    }
    private String safeMessage(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        message = message.replace(Path.of("").toAbsolutePath().toString(), "[managed workspace]").replace(root.toString(), "[managed workspace]");
        return message.substring(0, Math.min(message.length(), 1500));
    }
    private void recoverGeneration(String id)throws IOException{
        Path job=jobPath(id);
        if(Files.exists(job.resolve("received/response.json"))&&!Files.exists(job.resolve("cancel")))try{
            JsonObject result=ProposalAssistance.recover(job,request(id),new WikiApi.Budget(null));finish(id,result.getBoolean("partial",false)?"partial":"succeeded",result);return;
        }catch(ProposalAssistance.Failure f){finish(id,f.state,f.details);return;}
        boolean dispatched=Files.exists(job.resolve("dispatch/marker.json"));
        String state=Files.exists(job.resolve("received/response.json"))||Files.exists(job.resolve("cancel"))&&!dispatched?"cancelled":dispatched?"uncertain":"interrupted";
        finish(id,state,dispatched?ProposalAssistance.stopped(job):Json.createObjectBuilder().add("message","Interrupted before dispatch marker. No automatic provider call or retry.").build());
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        if (active != null && live.get(active) != null && live.get(active).getBoolean("cancellable")) { Path marker = jobPath(active).resolve("cancel"); if (!Files.exists(marker)) Files.createFile(marker); }
        worker.shutdown();
        // Do not release the workspace lock while a transaction is still executing.
        Thread closer = new Thread(() -> { try { while (!worker.awaitTermination(1, TimeUnit.SECONDS)) {} try{assistance.close();}finally{lock.release();lockChannel.close();} } catch (InterruptedException | IOException ignored) { Thread.currentThread().interrupt(); } }, "cluelab-admin-close");
        closer.setDaemon(true); closer.start();
    }
}
