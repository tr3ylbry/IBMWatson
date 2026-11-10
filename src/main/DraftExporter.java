package main;

import jakarta.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static main.PackCatalog.*;

/** Converts source-only captures plus private operator proposals to existing schema-1 drafts. */
final class DraftExporter {
    private DraftExporter() {}
    static void export(JsonObject capture, JsonObject proposals, Path output, WikiApi.Budget budget) throws IOException {
        Map<String, byte[]> files = build(capture, proposals, budget);
        exportFiles(files, output, budget);
    }
    static void exportWorkspace(JsonObject capture, JsonObject proposals, Path output, WikiApi.Budget budget,
                                Path workspace, Path activeCatalog) throws IOException {
        IntakeFiles.safe(workspace); Files.createDirectories(workspace);
        Path lock = workspace.resolve(".export.lock"); IntakeFiles.safe(lock);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.tryLock()) {
            require(held != null, "Another workspace exporter is active; retry later");
            Map<String, byte[]> files = build(capture, proposals, budget);
            guardIdentity(files, workspace, PackCatalog.load(activeCatalog), budget);
            exportFiles(files, output, budget);
        } catch (OverlappingFileLockException error) { throw new IOException("Another workspace exporter is active; retry later", error); }
    }
    static void guardIdentity(Map<String, byte[]> files, Path workspace, PackCatalog active, WikiApi.Budget budget) throws IOException {
        JsonObject manifest = IntakeFiles.parse(IntakeFiles.utf8(files.get("manifest.json")), "new draft manifest");
        String id = manifest.getString("id"), version = manifest.getString("version");
        for (PackCatalog.Collection collection : active.collections.values()) require(!collection.scope.id.equals(id + "-sources") || !collection.scope.version.equals(version),
                "Collection identity/version is already active; choose a new content version");
        for (PackCatalog.Pack pack : active.topics.values()) require(!pack.id.equals(id + "-clues") || !pack.version.equals(version),
                "Pack identity/version is already active; choose a new content version");
        if (!Files.exists(workspace)) return;
        try (var walk = Files.walk(workspace, 6)) {
            List<Path> entries = walk.limit(1001).toList(); require(entries.size() <= 1000, "Draft workspace exceeds scan limit; archive old work outside workspace explicitly");
            for (Path path : entries) {
                budget.check(); IntakeFiles.safe(path);
                if (!path.getFileName().toString().equals("manifest.json") || !Files.isRegularFile(path)) continue;
                if (workspace.relativize(path).toString().contains(".draft-validation-") || workspace.relativize(path).toString().contains("-staging-")) continue;
                JsonObject previous = IntakeFiles.read(path);
                if (id.equals(previous.getString("id", "")) && version.equals(previous.getString("version", "")))
                    require(Arrays.equals(files.get("manifest.json"), IntakeFiles.json(previous)), "Draft ID/version already has different frozen inputs/output: " + path + "; use a new version");
            }
        }
    }
    private static void exportFiles(Map<String, byte[]> files, Path output, WikiApi.Budget budget) throws IOException {
        // Validate in an isolated directory before the atomic publication boundary.
        IntakeFiles.safe(output); Files.createDirectories(output.toAbsolutePath().getParent());
        Path validation = Files.createTempDirectory(output.toAbsolutePath().getParent(), ".draft-validation-");
        try {
            for (var entry : files.entrySet()) {
                budget.check(); Path file = validation.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.write(file, entry.getValue());
            }
            PackCatalog.loadDraft(validation.resolve("catalog.json"));
        } finally {
            try (var paths = Files.walk(validation)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
        IntakeFiles.publish(output, files, budget);
    }
    static Map<String, byte[]> build(JsonObject capture, JsonObject proposals, WikiApi.Budget budget) throws IOException {
        try { return buildChecked(capture, proposals, budget); }
        catch (RuntimeException error) { throw new IOException("Malformed capture/proposal fields: " + error.getMessage(), error); }
    }
    private static Map<String, byte[]> buildChecked(JsonObject capture, JsonObject proposals, WikiApi.Budget budget) throws IOException {
        IntakeFiles.schema(capture, "topic language selectionSha256 selection sources failures");
        require("en".equals(string(capture, "language", 10)), "Capture must be English");
        require(sha256(IntakeFiles.json(object(capture, "selection"))).equals(string(capture, "selectionSha256", 64)), "Capture selection fingerprint mismatch");
        IntakeFiles.schema(proposals, "id version topicId topic label description subtopics authoring clues");
        String id = IntakeFiles.slug(proposals, "id"), version = IntakeFiles.version(proposals), topicId = IntakeFiles.slug(proposals, "topicId");
        require(string(capture, "topic", 150).equals(string(proposals, "topic", 150)), "Proposal topic must match selected source topic");
        String label = string(proposals, "label", 100), description = string(proposals, "description", 2000);
        JsonObject authoring = object(proposals, "authoring");
        keys(authoring, "authoring", "method author createdOn notes humanReviewer humanReviewedOn assistance");
        String method=string(authoring,"method",100);require(Set.of("operator-supplied","provider-assisted").contains(method),"Unsupported authoring method");
        if(method.equals("provider-assisted")) {
            JsonObject assistance=object(authoring,"assistance");
            require(TopicBuilder.uuid(string(assistance,"attemptId",36))&&sha256(IntakeFiles.json(capture)).equals(string(assistance,"captureSha256",64)),"Assistance provenance must pin this capture and a managed attempt");
            require(assistance.getJsonObject("validation").getInt("eligible")==0&&assistance.getJsonObject("validation").getInt("humanReviewed")==0,"Assistance cannot approve content");
        } else require(!authoring.containsKey("assistance"),"Do not strip or relabel assistance provenance as manual authoring");
        string(authoring, "author", 200); string(authoring, "notes", 3000); String created = string(authoring, "createdOn", 10);
        try { LocalDate.parse(created); } catch (RuntimeException error) { throw new IOException("authoring.createdOn: expected actual YYYY-MM-DD", error); }
        require(authoring.containsKey("humanReviewer") && authoring.isNull("humanReviewer") && authoring.containsKey("humanReviewedOn") && authoring.isNull("humanReviewedOn"),
                "This draft importer cannot assert human approval; leave human fields null");
        Map<String, byte[]> files = new TreeMap<>(); Map<Integer, JsonObject> sources = new LinkedHashMap<>();
        JsonArrayBuilder exportedSources = Json.createArrayBuilder(); Set<String> titles = new HashSet<>(), hashes = new HashSet<>();
        JsonArray captured = array(capture, "sources"); require(!captured.isEmpty() && captured.size() <= WikiApi.MAX_PAGES, "Capture needs 1-12 usable sources");
        Set<Integer> selectedIds = new HashSet<>();
        for (JsonValue value : array(object(capture, "selection"), "selected")) selectedIds.add(integer(object(value, "selection"), "pageId"));
        for (JsonValue value : captured) {
            budget.check(); JsonObject source = object(value, "captured source");
            keys(source, "captured source", "pageId requestedPageId requestedTitle title originalUrl canonicalUrl publisher licenseUrl licenseEvidence capturedAt responseSha256 text textSha256 redirects revisionId");
            int page = integer(source, "pageId"); require(page > 0 && sources.putIfAbsent(page, source) == null, "Duplicate canonical page ID");
            require(selectedIds.contains(integer(source, "requestedPageId")), "Captured source was not selected");
            String title = WikiApi.title(source), text = source.getString("text", ""); WikiApi.usable(title, text);
            require(titles.add(Evaluation.normalize(title)) && hashes.add(source.getString("textSha256", "")), "Duplicate source title/text");
            require(WikiApi.article(title).equals(string(source, "canonicalUrl", 2000)) && WikiApi.article(string(source, "requestedTitle", 200)).equals(string(source, "originalUrl", 2000)), "Source URL/title mismatch");
            require(WikiApi.LICENSE.equals(string(source, "licenseUrl", 200)) && WikiApi.TERMS.equals(string(source, "licenseEvidence", 300)), "Unsupported source rights evidence");
            require(string(source, "responseSha256", 64).matches("[a-f0-9]{64}"), "Invalid API response hash");
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8); require(sha256(bytes).equals(string(source, "textSha256", 64)), "Captured source text hash mismatch");
            String date;
            try { date = Instant.parse(string(source, "capturedAt", 100)).atZone(ZoneOffset.UTC).toLocalDate().toString(); }
            catch (RuntimeException error) { throw new IOException("Invalid capture date", error); }
            require(source.containsKey("revisionId"), "revisionId must be present, null if unknown");
            String revision = source.isNull("revisionId") ? null : string(source, "revisionId", 100);
            require(revision == null || revision.matches("[1-9][0-9]*"), "Invalid observed revision ID");
            String sourceId = sourceId(topicId, page), snapshot = "snapshots/" + sourceId + ".txt";
            files.put(snapshot, bytes);
            JsonObjectBuilder row = Json.createObjectBuilder().add("id", sourceId).add("title", title).add("category", label)
                    .add("url", source.getString("canonicalUrl")).add("referenceTitle", title + " - English Wikipedia")
                    .add("publisher", "Wikipedia contributors / Wikimedia Foundation")
                    .add("attribution", "Wikipedia contributors, " + source.getString("canonicalUrl") + "; history: https://en.wikipedia.org/w/index.php?title="
                            + java.net.URLEncoder.encode(title, StandardCharsets.UTF_8) + "&action=history; CC BY-SA 4.0: " + WikiApi.LICENSE
                            + ". Plaintext lead excerpt via TextExtracts; formatting removed, no media. Additional third-party conditions require review.")
                    .add("snapshot", snapshot).add("snapshotKind", "excerpt").add("snapshotSha256", sha256(bytes)).add("capturedOn", date)
                    .add("revisionNote", "Observed revision metadata in same latest-page API query; not a guaranteed old-revision render. Lead only; templates/extraction can vary. Exact saved bytes are hashed. Original title: " + source.getString("requestedTitle"));
            if (revision == null) row.addNull("revisionId"); else row.add("revisionId", revision);
            exportedSources.add(row);
        }
        JsonArray subtopics = array(proposals, "subtopics"), inputClues = array(proposals, "clues");
        require(!inputClues.isEmpty() && inputClues.size() <= 60, "Provide 1-60 proposals; five proposed clues are not five eligible clues");
        JsonArrayBuilder clues = Json.createArrayBuilder(), review = Json.createArrayBuilder();
        Map<String, String> aliasOwners = new HashMap<>(), facts = new HashMap<>(); Set<Integer> answerPages = new HashSet<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (JsonValue value : inputClues) {
            budget.check(); JsonObject clue = object(value, "proposal");
            keys(clue, "proposal", "id factId subtopicId category text hint difficulty answerType answerPageId aliases support reviewNotes");
            String clueId = IntakeFiles.slug(clue, "id"), fact = IntakeFiles.slug(clue, "factId");
            require("article-title".equals(string(clue, "answerType", 100)), clueId + ": only article-title answers are supported");
            int answer = integer(clue, "answerPageId"); require(sources.containsKey(answer), clueId + ": answer source was not captured"); answerPages.add(answer);
            JsonObject support = object(clue, "support"); keys(support, "support", "pageId start text"); int supportId = integer(support, "pageId");
            require(sources.containsKey(supportId), clueId + ": support source was not captured");
            String passage = support.getString("text", ""), context = sources.get(supportId).getString("text");
            require(!passage.isBlank() && passage.length() <= 12000, clueId + ": exact supporting passage must be 1-12000 UTF-16 units");
            int start = support.containsKey("start") ? integer(support, "start") : context.indexOf(passage);
            require(start >= 0 && start <= context.length() - passage.length() && context.startsWith(passage, start), clueId + ": invented/mismatched support passage or UTF-16 offset");
            require(support.containsKey("start") || context.indexOf(passage, start + 1) < 0, clueId + ": passage repeats; supply explicit UTF-16 start");
            int end = start + passage.length(); require(boundary(context, start) && boundary(context, end), clueId + ": support splits a UTF-16 surrogate pair");
            JsonArrayBuilder flags = Json.createArrayBuilder().add("Pending factual support in context, unsupported claims, ambiguity, hint and difficulty review; exact quotation is not approval.");
            if(method.equals("provider-assisted"))for(JsonValue finding:authoring.getJsonObject("assistance").getJsonObject("validation").getJsonArray("clues")){
                JsonObject row=(JsonObject)finding;if(clueId.equals(row.getString("clueId","")))for(JsonValue flag:row.getJsonArray("flags"))flags.add(flag);
            }
            String previous = facts.putIfAbsent(fact, clueId);
            if (previous != null) flags.add("Repeated factId with " + previous + "; inspect paraphrase padding.");
            String supportKey = supportId + ":" + Evaluation.normalize(passage);
            previous = facts.putIfAbsent(supportKey, clueId);
            if (previous != null) flags.add("Repeated supporting passage with " + previous + "; inspect repeated facts.");
            String canonical = sources.get(answer).getString("title");
            for (JsonValue aliasValue : array(clue, "aliases")) {
                require(aliasValue instanceof JsonString, clueId + ": aliases must be strings"); String alias = ((JsonString) aliasValue).getString();
                if (!Evaluation.normalize(alias).equals(Evaluation.normalize(canonical))) flags.add("Questionable/unverified alias requires explicit review: " + alias);
                previous = aliasOwners.putIfAbsent(Evaluation.normalize(alias), clueId);
                if (previous != null) flags.add("Ambiguous/shared alias with " + previous + ": " + alias);
            }
            String difficulty = string(clue, "difficulty", 100); require(Set.of("easy", "standard", "hard").contains(difficulty), "Unsupported provisional difficulty: " + difficulty);
            counts.merge(difficulty, 1, Integer::sum);
            JsonObjectBuilder out = Json.createObjectBuilder().add("id", clueId).add("topicId", topicId)
                    .add("subtopicId", string(clue, "subtopicId", 100)).add("category", string(clue, "category", 200))
                    .add("text", string(clue, "text", 3000)).add("difficulty", difficulty).add("answerType", "article-title")
                    .add("answerSourceId", sourceId(topicId, answer)).add("aliases", array(clue, "aliases"))
                    .add("support", Json.createObjectBuilder().add("sourceId", sourceId(topicId, supportId)).add("start", start).add("end", end));
            if (clue.containsKey("hint")) out.add("hint", clue.get("hint"));
            clues.add(out); review.add(Json.createObjectBuilder().add("clueId", clueId).add("factId", fact).add("flags", flags)
                    .add("operatorNotes", string(clue, "reviewNotes", 3000)).addNull("humanReviewer").addNull("humanReviewedOn").addNull("humanDecision"));
        }
        String collectionId = id + "-sources", packId = id + "-clues";
        JsonObject collection = Json.createObjectBuilder().add("schemaVersion", 1).add("id", collectionId).add("version", version).add("language", "en")
                .add("description", "Selected English Wikipedia plaintext lead excerpts for " + label + "; not full-page archives.").add("sources", exportedSources).build();
        JsonObject pack = Json.createObjectBuilder().add("schemaVersion", 1).add("id", packId).add("version", version).add("topicId", topicId)
                .add("label", label).add("description", description).add("language", "en").add("collectionId", collectionId).add("collectionVersion", version)
                .add("subtopics", subtopics).add("review", Json.createObjectBuilder().add("status", "draft").add("reviewer", "Not approved; operator-supplied draft")
                        .add("reviewedOn", created).add("factualNotes", "Not factual approval. See private review.json for actual assistant/operator findings and pending flags.")
                        .add("editorialNotes", "Provisional difficulties; aliases, ambiguity, hints and repeated facts require review.")
                        .add("limitations", "reviewedOn is draft assembly date required by schema 1, not human approval. Zero eligible clues. No human review/playtest/calibration or distribution clearance."))
                .add("clues", clues).build();
        files.put("collection.json", IntakeFiles.json(collection)); files.put("private/pack.json", IntakeFiles.json(pack));
        JsonArrayBuilder difficulties = Json.createArrayBuilder();
        for (String level : List.of("easy", "standard", "hard")) difficulties.add(Json.createObjectBuilder().add("id", level).add("label", level.substring(0, 1).toUpperCase(Locale.ROOT) + level.substring(1)));
        JsonObject catalog = Json.createObjectBuilder().add("schemaVersion", 1).add("id", id + "-draft-catalog").add("version", version)
                .add("defaults", Json.createObjectBuilder().add("topicId", topicId).add("difficulty", "standard"))
                .add("difficulties", difficulties).add("collections", Json.createArrayBuilder().add(ref(collectionId, version, "collection.json")))
                .add("packs", Json.createArrayBuilder().add(ref(packId, version, "private/pack.json")))
                .add("topics", Json.createArrayBuilder().add(Json.createObjectBuilder().add("id", topicId).add("packId", packId).add("packVersion", version))).build();
        files.put("catalog.json", IntakeFiles.json(catalog));
        JsonObjectBuilder countJson = Json.createObjectBuilder(); counts.forEach(countJson::add);
        files.put("private/review.json", IntakeFiles.json(Json.createObjectBuilder().add("schemaVersion", 1).add("authoring", authoring)
                .add("proposals", inputClues.size()).add("distinctAnswerPages", answerPages.size()).add("proposedByDifficulty", countJson)
                .add("eligibleClues", 0).add("humanReviewed", 0).add("playtested", 0).add("calibrated", 0).add("clues", review)
                .add("shortfall", "All proposed clues remain ineligible until separate review/promotion; no five-clue playable game is advertised.").build()));
        files.put("private/proposals.json", IntakeFiles.json(proposals));
        files.put("provenance/capture.json", IntakeFiles.json(capture));
        JsonObjectBuilder hashesOut = Json.createObjectBuilder(); files.forEach((name, bytes) -> hashesOut.add(name, sha256(bytes)));
        files.put("manifest.json", IntakeFiles.json(Json.createObjectBuilder().add("schemaVersion", 1).add("id", id).add("version", version)
                .add("captureSha256", sha256(IntakeFiles.json(capture))).add("proposalsSha256", sha256(IntakeFiles.json(proposals))).add("files", hashesOut).build()));
        return files;
    }
    private static JsonObject ref(String id, String version, String path) { return Json.createObjectBuilder().add("id", id).add("version", version).add("path", path).build(); }
    private static String sourceId(String topic, int id) { return topic + "-wp-" + id; }
    private static boolean boundary(String text, int offset) { return offset <= 0 || offset >= text.length() || !Character.isHighSurrogate(text.charAt(offset - 1)) || !Character.isLowSurrogate(text.charAt(offset)); }
    static PackCatalog validate(Path root) throws IOException {
        Map<String, byte[]> actual = IntakeFiles.tree(root); JsonObject manifest = IntakeFiles.read(root.resolve("manifest.json"));
        IntakeFiles.schema(manifest, "id version captureSha256 proposalsSha256 files");
        JsonObject expected = object(manifest, "files"); Set<String> names = new HashSet<>(expected.keySet()); names.add("manifest.json");
        require(actual.keySet().equals(names), "Draft artifact file membership changed");
        for (String name : expected.keySet()) require(sha256(actual.get(name)).equals(expected.getString(name)), "Draft fingerprint mismatch: " + name);
        JsonObject capture = IntakeFiles.read(root.resolve("provenance/capture.json")), proposals = IntakeFiles.read(root.resolve("private/proposals.json"));
        Map<String, byte[]> rebuilt = build(capture, proposals, new WikiApi.Budget(null));
        require(rebuilt.keySet().equals(actual.keySet()), "Draft reconstruction membership mismatch");
        for (String name : actual.keySet()) require(Arrays.equals(actual.get(name), rebuilt.get(name)), "Draft reconstruction mismatch: " + name);
        return PackCatalog.loadDraft(root.resolve("catalog.json"));
    }
}
