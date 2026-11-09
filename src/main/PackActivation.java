package main;

import jakarta.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static main.PackCatalog.*;

/** Operator-only, prepare-before-switch local catalog transactions. No hot reload or game API route. */
public final class PackActivation {
    interface Phase { void at(String phase) throws IOException; }
    private PackActivation() {}
    public static void main(String[] args) throws Exception {
        require(args.length >= 1, usage());
        for (int i = 1; i < args.length; i++) if (!(args[0].equals("activation-plan") && i == 4) && !(args[0].equals("sandbox") && i == 4)) owned(Path.of(args[i]));
        switch (args[0]) {
            case "review-preview":
                require(args.length == 5, usage()); ContentReview.preview(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4])); break;
            case "sandbox":
                require(args.length == 5, usage()); sandbox(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Integer.parseInt(args[4])); break;
            case "activation-plan":
                require(args.length == 6, usage());
                System.out.println(plan(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], Path.of(args[5]))); break;
            case "activate": case "rollback":
                require(args.length == 3, usage());
                GameConfig config = GameConfig.load(Path.of(args[1])); owned(config.indexBase); owned(catalogPath(Path.of(args[1])));
                System.out.println(apply(Path.of(args[1]), Path.of(args[2]), args[0].equals("rollback"), phase -> {})); break;
            default: throw new IOException(usage());
        }
    }
    private static String usage() {
        return "PackTool: review-preview <old-draft> <new-draft> <review.json> <output-dir> | sandbox <game-config> <output-dir> <separate-index-base> <port> | activation-plan <game-config> <draft-dir> <review.json> <next-catalog-version> <plan-dir> | activate|rollback <game-config> <plan-dir>";
    }
    private static void owned(Path input) throws IOException {
        Path root = Path.of("").toAbsolutePath().normalize(), path = input.toAbsolutePath().normalize();
        require(path.startsWith(root.resolve("drafts")) || path.startsWith(root.resolve("data/packs")) || path.equals(root.resolve("data/game.json"))
                || path.startsWith(root.resolve("indexes")) || path.startsWith(root.resolve("target")), "Admin path must stay in this clone's draft/catalog/index workspace, not archive/benchmarks: " + path);
        IntakeFiles.safe(path);
    }
    static Path catalogPath(Path config) throws IOException {
        return config.toAbsolutePath().getParent().resolve(string(IntakeFiles.read(config), "catalog", 500)).normalize();
    }
    static Path resolve(Path root, String name) throws IOException {
        Path path = root.resolve(name).toAbsolutePath().normalize();
        require(!Path.of(name).isAbsolute() && path.startsWith(root.toAbsolutePath().normalize()), "Catalog/plan path escape: " + name);
        IntakeFiles.safe(path); return path;
    }
    static Map<String, byte[]> closure(Path catalog) throws IOException {
        Path root = catalog.toAbsolutePath().getParent(); Map<String, byte[]> files = new TreeMap<>();
        files.put("catalog.json", IntakeFiles.read(catalog, 4 * 1024 * 1024)); JsonObject row = IntakeFiles.read(catalog);
        for (String field : List.of("collections", "packs")) for (JsonValue value : array(row, field)) {
            JsonObject ref = object(value, "reference"); String name = string(ref, "path", 300); Path file = resolve(root, name);
            files.put(name, IntakeFiles.read(file, 4 * 1024 * 1024));
            if (field.equals("collections")) for (JsonValue item : array(IntakeFiles.read(file), "sources")) {
                String snapshot = string(object(item, "source"), "snapshot", 300);
                files.put(snapshot, IntakeFiles.read(resolve(root, snapshot), 256 * 1024));
            }
            if (field.equals("packs")) {
                Path decision = file.resolveSibling("review-decision.json");
                if (Files.exists(decision)) files.put(root.relativize(decision).toString().replace(File.separatorChar, '/'), IntakeFiles.read(decision, 4 * 1024 * 1024));
            }
        }
        require(files.size() <= 110, "Local activation supports at most 110 referenced catalog files; archive/split larger catalogs explicitly"); return files;
    }
    static void sandbox(Path configFile, Path output, Path indexes, int port) throws IOException {
        GameConfig config = GameConfig.load(configFile); require(port >= 1024 && port <= 65535 && port != config.port, "Choose a separate unprivileged preview port");
        require(!indexes.toAbsolutePath().normalize().equals(config.indexBase.toAbsolutePath().normalize()), "Sandbox needs a separate index base");
        require(!output.toAbsolutePath().normalize().startsWith(catalogPath(configFile).getParent()), "Sandbox must be outside the active catalog directory");
        Map<String, byte[]> files = new TreeMap<>(); closure(catalogPath(configFile)).forEach((name, bytes) -> files.put("catalog/" + name, bytes));
        JsonObject game = Json.createObjectBuilder(IntakeFiles.read(configFile)).add("catalog", "catalog/catalog.json")
                .add("indexBase", indexes.toAbsolutePath().normalize().toString()).add("port", port).build(); files.put("game.json", IntakeFiles.json(game));
        IntakeFiles.publish(output, files, new WikiApi.Budget(null)); System.out.println("Isolated game config: " + output.resolve("game.json") + "; indexes are not built by sandbox creation.");
    }
    static JsonObject plan(Path configFile, Path draft, Path reviewFile, String catalogVersion, Path output) throws IOException {
        GameConfig config = GameConfig.load(configFile); Path current = catalogPath(configFile);
        require(catalogVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+") && ContentReview.newer(catalogVersion, config.catalog.version), "Plan needs a strictly newer catalog version");
        ContentReview.Candidate candidate = ContentReview.candidate(draft, IntakeFiles.read(reviewFile));
        releaseConflicts(current.getParent(), candidate);
        Map<String, byte[]> previous = closure(current), prepared = new TreeMap<>(previous);
        String release = "releases/" + candidate.digest();
        candidate.files().forEach((name, bytes) -> {
            if (!name.equals("catalog.json")) prepared.put(name.startsWith("snapshots/") ? name : release + "/" + name, bytes);
        });
        JsonObject merged = merge(IntakeFiles.read(current), candidate, release, catalogVersion);
        catalogConflicts(current.getParent(), merged, sha256(IntakeFiles.json(merged)));
        prepared.put("catalog.json", IntakeFiles.json(merged));
        Map<String, byte[]> files = new TreeMap<>(); previous.forEach((name, bytes) -> files.put("before/" + name, bytes)); prepared.forEach((name, bytes) -> files.put("prepared/" + name, bytes));
        // Validate all references and eligibility before writing any plan or target catalog file.
        Path temporary = Files.createTempDirectory("cluelab-plan-").toRealPath();
        JsonObject availability; JsonArray indexes;
        try {
            writeTree(temporary, prepared); PackCatalog next = PackCatalog.load(temporary.resolve("catalog.json"));
            PackCatalog.Pack pack = next.topics.get(candidate.topicId());
            require(next.difficulties.keySet().stream().anyMatch(level -> pack.eligible(level).size() >= 5), "Insufficient reviewed coverage: need five clues at one difficulty");
            availability = PackTool.validation(next); indexes = indexPreview(next, config);
        } finally { remove(temporary); }
        JsonObject body = Json.createObjectBuilder().add("schemaVersion", 1).add("operation", "local-catalog-activation")
                .add("configPath", configFile.toAbsolutePath().normalize().toString()).add("configSha256", hashFile(configFile))
                .add("catalogPath", current.toString()).add("beforeCatalogSha256", sha256(previous.get("catalog.json")))
                .add("afterCatalogSha256", sha256(prepared.get("catalog.json"))).add("beforeFiles", ContentReview.hashes(previous))
                .add("draftPath", draft.toAbsolutePath().normalize().toString()).add("draftFiles", ContentReview.hashes(IntakeFiles.tree(draft)))
                .add("reviewPath", reviewFile.toAbsolutePath().normalize().toString()).add("reviewSha256", hashFile(reviewFile))
                .add("release", release).add("topicId", candidate.topicId()).add("candidateDigest", candidate.digest())
                .add("catalogVersion", catalogVersion).add("availability", availability).add("requiredIndexes", indexes)
                .add("limitations", candidate.review().getString("limitations")).add("restartRequired", true)
                .add("restartClearsInMemorySessions", false).add("files", ContentReview.hashes(files)).build();
        JsonObject plan = Json.createObjectBuilder(body).add("planId", sha256(IntakeFiles.json(body))).build(); files.put("plan.json", IntakeFiles.json(plan));
        IntakeFiles.publish(output, files, new WikiApi.Budget(null)); return plan;
    }
    private static JsonObject merge(JsonObject current, ContentReview.Candidate candidate, String release, String version) throws IOException {
        JsonObject collection = IntakeFiles.parse(IntakeFiles.utf8(candidate.files().get("collection.json")), "collection"), pack = IntakeFiles.parse(IntakeFiles.utf8(candidate.files().get("private/pack.json")), "pack");
        JsonArrayBuilder collections = replace(array(current, "collections"), ContentReview.reference(collection.getString("id"), collection.getString("version"), release + "/collection.json"));
        JsonArrayBuilder packs = replace(array(current, "packs"), ContentReview.reference(pack.getString("id"), pack.getString("version"), release + "/private/pack.json"));
        JsonArrayBuilder topics = Json.createArrayBuilder(); boolean found = false;
        for (JsonValue value : array(current, "topics")) {
            JsonObject row = object(value, "topic");
            if (row.getString("id").equals(candidate.topicId())) {
                require(row.getString("packId").equals(pack.getString("id")), "Conflicting topic/pack identity"); found = true;
                topics.add(Json.createObjectBuilder(row).add("packVersion", pack.getString("version")));
            } else topics.add(row);
        }
        if (!found) topics.add(Json.createObjectBuilder().add("id", candidate.topicId()).add("packId", pack.getString("id")).add("packVersion", pack.getString("version")));
        return Json.createObjectBuilder(current).add("version", version).add("collections", collections).add("packs", packs).add("topics", topics).build();
    }
    private static JsonArrayBuilder replace(JsonArray rows, JsonObject next) throws IOException {
        JsonArrayBuilder out = Json.createArrayBuilder(); boolean replaced = false;
        for (JsonValue value : rows) {
            JsonObject row = object(value, "reference");
            if (row.getString("id").equals(next.getString("id"))) {
                require(ContentReview.newer(next.getString("version"), row.getString("version")), "Conflicting/non-newer content identity/version: " + row.getString("id")); out.add(next); replaced = true;
            } else out.add(row);
        }
        if (!replaced) out.add(next); return out;
    }
    private static JsonArray indexPreview(PackCatalog catalog, GameConfig config) throws IOException {
        JsonArrayBuilder out = Json.createArrayBuilder(); CollectionIndex indexes = new CollectionIndex(catalog.collections, config.indexBase, config.indexType, config.scorer);
        for (PackCatalog.Collection collection : catalog.collections.values()) {
            Path base = indexes.base(collection.scope); boolean exists;
            try (Indexer indexer = new Indexer(base.toString(), config.indexType)) { exists = Files.exists(Path.of(indexer.getIndexPath())); }
            JsonObjectBuilder row = Json.createObjectBuilder().add("scope", collection.scope.key()).add("digest", collection.scope.digest)
                    .add("base", base.toAbsolutePath().toString()).add("indexType", config.indexType).add("documents", collection.sources.size())
                    .add("runtimeNamespace", RuntimeInfo.indexNamespace()).add("requiredBuild", !exists);
            if (exists) row.add("verifiedProvenance", indexes.describe(collection)); else row.addNull("verifiedProvenance"); out.add(row);
        }
        return out.build();
    }
    static JsonObject apply(Path configFile, Path directory, boolean rollback, Phase phase) throws IOException {
        Path target = catalogPath(configFile); IntakeFiles.safe(target); Path lock = target.resolveSibling(".activation.lock"); IntakeFiles.safe(lock);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock held = channel.tryLock()) {
            require(held != null, "Another catalog transaction is active; retry later");
            return lockedApply(configFile, directory, rollback, phase);
        } catch (OverlappingFileLockException error) { throw new IOException("Another catalog transaction is active; retry later", error); }
        catch (IOException error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            JsonObject rejected = Json.createObjectBuilder().add("action", rollback ? "rollback" : "activation").add("outcome", "rejected")
                    .add("observedAt", Instant.now().toString()).add("planPath", directory.toAbsolutePath().toString())
                    .add("error", message.substring(0, Math.min(1000, message.length()))).build();
            try { record(target.getParent().resolve(".activation-history/rejected/" + UUID.randomUUID()), "result.json", rejected); }
            catch (IOException logging) { error.addSuppressed(logging); }
            throw error;
        }
    }
    private static JsonObject lockedApply(Path configFile, Path directory, boolean rollback, Phase phase) throws IOException {
        JsonObject plan = validatePlan(configFile, directory); Path target = catalogPath(configFile), root = target.getParent();
        Path operation = root.resolve(".activation-history/" + plan.getString("planId") + (rollback ? "/rollback" : "/activation"));
        String expected = plan.getString(rollback ? "afterCatalogSha256" : "beforeCatalogSha256"), next = plan.getString(rollback ? "beforeCatalogSha256" : "afterCatalogSha256");
        String tree = rollback ? "before" : "prepared";
        try {
            if (Files.exists(operation.resolve("result.json"))) return IntakeFiles.read(operation.resolve("result.json"));
            String current = hashFile(target); boolean recovery = current.equals(next) && Files.exists(operation.resolve("intent.json"));
            require(current.equals(expected) || recovery, "Stale catalog fingerprint; create a new plan or use the correct latest rollback target");
            if (rollback) require(Files.exists(root.resolve(".activation-history/" + plan.getString("planId") + "/activation/intent.json")), "No known activation to roll back");
            Map<String, byte[]> desired = IntakeFiles.tree(directory.resolve(tree));
            if (!recovery && !rollback) verifyCurrent(target, object(plan, "beforeFiles"));
            if (rollback) verifyCurrentArtifacts(target, ContentReview.hashes(desired));
            PackCatalog catalog = PackCatalog.load(directory.resolve(tree + "/catalog.json")); GameConfig config = GameConfig.load(configFile);
            if (!rollback) releaseConflicts(root, ContentReview.candidate(Path.of(plan.getString("draftPath")), IntakeFiles.read(Path.of(plan.getString("reviewPath")))));
            if (!rollback) catalogConflicts(root, IntakeFiles.read(directory.resolve("prepared/catalog.json")), next);
            CollectionIndex indexes = new CollectionIndex(catalog.collections, config.indexBase, config.indexType, config.scorer);
            if (!rollback && !recovery) indexes.build(false);
            JsonArrayBuilder provenance = Json.createArrayBuilder();
            for (PackCatalog.Collection collection : catalog.collections.values()) provenance.add(indexes.describe(collection));
            JsonArray verifiedIndexes = provenance.build();
            phase.at("indexes-verified");
            for (var entry : desired.entrySet()) if (!entry.getKey().equals("catalog.json")) install(resolve(root, entry.getKey()), entry.getValue());
            verifyCurrentArtifacts(target, ContentReview.hashes(desired));
            if (!recovery) {
                require(hashFile(target).equals(expected) && hashFile(configFile).equals(plan.getString("configSha256")), "Catalog/config changed during preparation");
                if (!rollback) verifyCurrent(target, object(plan, "beforeFiles"));
            }
            JsonObject intent = Json.createObjectBuilder().add("planId", plan.getString("planId")).add("action", rollback ? "rollback" : "activation")
                    .add("beforeSha256", expected).add("afterSha256", next).add("catalogId", catalog.id).add("fromCatalogVersion", config.catalog.version)
                    .add("preparedAt", Instant.now().toString()).add("indexes", verifiedIndexes).build();
            if (!Files.exists(operation.resolve("intent.json"))) record(operation, "intent.json", intent);
            if (!recovery) {
                phase.at("before-switch");
                require(hashFile(target).equals(expected) && hashFile(configFile).equals(plan.getString("configSha256")), "Catalog/config changed immediately before switch");
                atomicSwitch(target, desired.get("catalog.json"));
                phase.at("after-switch");
            }
            require(hashFile(target).equals(next), "Post-switch catalog fingerprint mismatch");
            JsonObject result = Json.createObjectBuilder().add("planId", plan.getString("planId")).add("action", rollback ? "rollback" : "activation")
                    .add("outcome", recovery ? "recovered-after-switch" : "switched").add("observedAt", Instant.now().toString())
                    .add("catalogSha256", next).add("catalogVersion", catalog.version).add("catalogId", catalog.id)
                    .add("fromCatalogVersion", IntakeFiles.read(operation.resolve("intent.json")).getString("fromCatalogVersion", "unknown"))
                    .add("fromCatalogSha256", expected).add("indexes", verifiedIndexes)
                    .add("restartRequired", true).add("restartClearsInMemorySessions", false).build();
            record(operation, "result.json", result); return result;
        } catch (IOException error) {
            JsonObject failure = Json.createObjectBuilder().add("planId", plan.getString("planId")).add("action", rollback ? "rollback" : "activation")
                    .add("outcome", "failed-or-interrupted").add("observedAt", Instant.now().toString()).add("observedCatalogSha256", hashFile(target))
                    .add("error", error.getMessage().substring(0, Math.min(1000, error.getMessage().length()))).build();
            try { record(operation.resolve("failures/" + UUID.randomUUID()), "result.json", failure); } catch (IOException logging) { error.addSuppressed(logging); }
            throw error;
        }
    }
    private static void catalogConflicts(Path root, JsonObject catalog, String digest) throws IOException {
        Path history = root.resolve(".activation-history"); if (!Files.exists(history)) return; IntakeFiles.safe(history);
        try (var paths = Files.walk(history, 4)) {
            List<Path> entries = paths.limit(1025).toList(); require(entries.size() <= 1024, "Catalog history scan limit reached; preserve/archive receipts explicitly");
            for (Path file : entries) {
                IntakeFiles.safe(file);
                if (!file.getFileName().toString().equals("result.json") || !Files.isRegularFile(file)) continue;
                JsonObject previous = IntakeFiles.read(file);
                if (!Set.of("switched", "recovered-after-switch").contains(previous.getString("outcome", ""))
                        || !catalog.getString("id").equals(previous.getString("catalogId", catalog.getString("id")))) continue;
                if (catalog.getString("version").equals(previous.getString("catalogVersion", "")))
                    require(digest.equals(previous.getString("catalogSha256", "")), "Historical catalog ID/version has different bytes; choose a new catalog version");
                if (catalog.getString("version").equals(previous.getString("fromCatalogVersion", "")))
                    require(digest.equals(previous.getString("fromCatalogSha256", "")), "Rolled-back catalog ID/version has different bytes; choose a new catalog version");
            }
        }
    }
    private static void releaseConflicts(Path root, ContentReview.Candidate candidate) throws IOException {
        Path releases = root.resolve("releases"); if (!Files.exists(releases)) return; IntakeFiles.safe(releases);
        try (var paths = Files.walk(releases, 4)) {
            List<Path> entries = paths.limit(513).toList(); require(entries.size() <= 512, "Release history scan limit reached; retain/archive history explicitly");
            for (Path file : entries) {
                IntakeFiles.safe(file); String name = file.getFileName().toString();
                if (!Files.isRegularFile(file) || !Set.of("pack.json", "collection.json").contains(name)) continue;
                String key = name.equals("pack.json") ? "private/pack.json" : "collection.json";
                JsonObject old = IntakeFiles.read(file), next = IntakeFiles.parse(IntakeFiles.utf8(candidate.files().get(key)), key);
                if (old.getString("id").equals(next.getString("id")) && old.getString("version").equals(next.getString("version")))
                    require(Arrays.equals(IntakeFiles.read(file, 4 * 1024 * 1024), candidate.files().get(key)), "Historical content identity/version conflict: " + file);
            }
        }
    }
    static JsonObject validatePlan(Path configFile, Path directory) throws IOException {
        Map<String, byte[]> files = IntakeFiles.tree(directory); JsonObject plan = IntakeFiles.read(directory.resolve("plan.json"));
        IntakeFiles.schema(plan, "operation configPath configSha256 catalogPath beforeCatalogSha256 afterCatalogSha256 beforeFiles draftPath draftFiles reviewPath reviewSha256 release topicId candidateDigest catalogVersion availability requiredIndexes limitations restartRequired restartClearsInMemorySessions files planId");
        JsonObject body = Json.createObjectBuilder(plan).remove("planId").build();
        require(sha256(IntakeFiles.json(body)).equals(plan.getString("planId")), "Changed plan fingerprint");
        require(configFile.toAbsolutePath().normalize().toString().equals(plan.getString("configPath")) && hashFile(configFile).equals(plan.getString("configSha256")), "Plan config fingerprint/path changed");
        require(catalogPath(configFile).toString().equals(plan.getString("catalogPath")), "Plan targets a different catalog");
        JsonObject expected = object(plan, "files"); Set<String> names = new HashSet<>(expected.keySet()); names.add("plan.json");
        require(names.equals(files.keySet()), "Changed/incomplete plan file membership");
        for (String name : expected.keySet()) require(sha256(files.get(name)).equals(expected.getString(name)), "Changed plan artifact: " + name);
        Path draft = Path.of(plan.getString("draftPath")), review = Path.of(plan.getString("reviewPath"));
        require(ContentReview.hashes(IntakeFiles.tree(draft)).equals(object(plan, "draftFiles")) && hashFile(review).equals(plan.getString("reviewSha256")), "Changed draft/review artifacts; plan is stale");
        ContentReview.Candidate candidate = ContentReview.candidate(draft, IntakeFiles.read(review));
        require(candidate.digest().equals(plan.getString("candidateDigest")) && plan.getString("release").equals("releases/" + candidate.digest()), "Candidate identity changed");
        require(sha256(files.get("before/catalog.json")).equals(plan.getString("beforeCatalogSha256")) && sha256(files.get("prepared/catalog.json")).equals(plan.getString("afterCatalogSha256")), "Incorrect before/after catalog identities");
        JsonObject before = IntakeFiles.read(directory.resolve("before/catalog.json"));
        JsonObject merged = merge(before, candidate, plan.getString("release"), plan.getString("catalogVersion"));
        require(Arrays.equals(IntakeFiles.json(merged), files.get("prepared/catalog.json")), "Plan is not the declared targeted catalog change");
        require(ContentReview.newer(merged.getString("version"), before.getString("version")), "Catalog version must advance");
        PackCatalog loaded = PackCatalog.load(directory.resolve("prepared/catalog.json")); PackCatalog.Pack pack = loaded.topics.get(candidate.topicId());
        require(loaded.difficulties.keySet().stream().anyMatch(level -> pack.eligible(level).size() >= 5), "Insufficient five-clue game coverage");
        for (var entry : candidate.files().entrySet()) if (!entry.getKey().equals("catalog.json")) {
            String path = entry.getKey().startsWith("snapshots/") ? entry.getKey() : plan.getString("release") + "/" + entry.getKey();
            require(Arrays.equals(entry.getValue(), files.get("prepared/" + path)), "Candidate bytes differ from reviewed artifact: " + path);
        }
        PackCatalog.load(directory.resolve("before/catalog.json")); return plan;
    }
    private static void verifyCurrent(Path target, JsonObject hashes) throws IOException {
        require(hashFile(target).equals(hashes.getString("catalog.json")), "Stale catalog fingerprint"); verifyCurrentArtifacts(target, hashes);
    }
    private static void verifyCurrentArtifacts(Path target, JsonObject hashes) throws IOException {
        for (String name : hashes.keySet()) if (!name.equals("catalog.json")) require(hashFile(resolve(target.getParent(), name)).equals(hashes.getString(name)), "Changed/missing immutable catalog artifact: " + name);
    }
    private static void install(Path target, byte[] bytes) throws IOException {
        IntakeFiles.safe(target); Files.createDirectories(target.getParent());
        if (Files.exists(target)) { require(Arrays.equals(IntakeFiles.read(target, 4 * 1024 * 1024), bytes), "Immutable artifact/version conflict: " + target); return; }
        Path temporary = Files.createTempFile(target.getParent(), ".install-", ".tmp");
        try { Files.write(temporary, bytes); Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(temporary); }
    }
    static void atomicSwitch(Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".catalog-switch-", ".tmp");
        try {
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer data = ByteBuffer.wrap(bytes); while (data.hasRemaining()) out.write(data); out.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private static void record(Path operation, String name, JsonObject json) throws IOException {
        // Intent and result are separate immutable files; recovery can observe a switch without a result.
        install(operation.resolve(name), IntakeFiles.json(json));
    }
    private static String hashFile(Path file) throws IOException { return sha256(IntakeFiles.read(file, 4 * 1024 * 1024)); }
    private static void writeTree(Path root, Map<String, byte[]> files) throws IOException {
        for (var entry : files.entrySet()) { Path file = resolve(root, entry.getKey()); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW); }
    }
    private static void remove(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
}
