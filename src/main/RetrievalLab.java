package main;

import jakarta.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Operator-only, frozen development/held-out experiment. Never used by the game. */
public final class RetrievalLab {
    private RetrievalLab() {}
    static final int TYPE = 1, SCORER = 4, REPEATS = 5;

    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    static JsonObject verified(Path file, String fingerprint) throws IOException {
        require(Files.size(file) <= 4 * 1024 * 1024, file + ": JSON exceeds 4 MiB");
        require(sha(Files.readAllBytes(file)).equals(fingerprint), file + ": frozen SHA-256 differs; publish a new dataset version, not an in-place edit.");
        return PackCatalog.read(file);
    }
    static JsonObject freeze(Path root) throws IOException {
        JsonObject frozen = PackCatalog.read(root.resolve("experiment.json"));
        require(frozen.getInt("schemaVersion") == 1, "Unsupported experiment schemaVersion");
        require(frozen.getInt("indexType") == TYPE && frozen.getInt("scorer") == SCORER && frozen.getInt("repetitions") == REPEATS,
                "This experiment runner supports only the predeclared plain/JM, five-repeat protocol.");
        JsonArray trials = frozen.getJsonArray("trials");
        require(trials.size() == 3, "Exactly three predeclared trials are supported");
        for (int i = 0; i < 3; i++) require(trials.getJsonObject(i).getString("id").equals(RetrievalProfile.values()[i].id), "Predeclared trial order differs");
        require(trials.getJsonObject(1).getJsonNumber("titleBoost").doubleValue() == 2
                && trials.getJsonObject(1).getJsonNumber("categoryBoost").doubleValue() == 1.5
                && trials.getJsonObject(0).getInt("bodyBoost") == 1 && trials.getJsonObject(1).getInt("bodyBoost") == 1
                && trials.getJsonObject(2).getInt("candidateWindow") == 30, "Trial parameters differ from the frozen implementation");
        return frozen;
    }
    static JsonObject manifest(Path root, JsonObject frozen) throws IOException {
        JsonObject source = verified(root.resolve("collection.json"), frozen.getString("collectionSha256"));
        require(source.getInt("schemaVersion") == 1, "Unsupported collection schemaVersion");
        require(source.getInt("documentCount") == source.getJsonArray("documents").size(), "Collection document count differs");
        Set<String> ids = new HashSet<>();
        for (JsonObject document : source.getJsonArray("documents").getValuesAs(JsonObject.class))
            require(ids.add(document.getString("id")), "Duplicate source document ID: " + document.getString("id"));
        return source;
    }
    static List<JsonObject> split(Path root, JsonObject frozen, JsonObject source, String name) throws IOException {
        JsonObject set = verified(root.resolve("private/" + name + ".json"), frozen.getString(name.equals("development") ? "developmentSha256" : "heldOutSha256"));
        require(set.getInt("schemaVersion") == 1 && set.getString("split").equals(name), "Unsupported or inconsistent split schema: " + name);
        require(set.getString("collectionId").equals(source.getString("id")) && set.getString("collectionVersion").equals(source.getString("version")), "Split refers to a different source collection");
        List<JsonObject> cases = set.getJsonArray("cases").getValuesAs(JsonObject.class);
        require(cases.size() == 20, "Frozen pilot must contain exactly 20 cases per split");
        Map<String, JsonObject> documents = new HashMap<>();
        source.getJsonArray("documents").getValuesAs(JsonObject.class).forEach(d -> documents.put(d.getString("id"), d));
        Set<String> ids = new HashSet<>(), answers = new HashSet<>();
        for (JsonObject clue : cases) {
            require(ids.add(clue.getString("id")), "Duplicate case ID: " + clue.getString("id"));
            require(clue.getString("answerType").equals("article-title"), "Unavailable experiment answer type");
            require(PackCatalog.validText(clue.getString("category"), 200) && PackCatalog.validText(clue.getString("clue"), 3000), "Invalid category/clue content");
            String id = clue.getString("answerDocumentId"); JsonObject document = documents.get(id);
            require(document != null && answers.add(id), "Missing or repeated answer document: " + id);
            Set<String> aliases = new HashSet<>();
            for (JsonValue value : clue.getJsonArray("aliases")) {
                require(value instanceof JsonString, "Aliases must be strings");
                String alias = ((JsonString) value).getString();
                require(PackCatalog.validText(alias, 200) && !alias.contains("|") && aliases.add(Evaluation.normalize(alias)), "Invalid or duplicate alias");
            }
            require(aliases.contains(Evaluation.normalize(document.getString("title"))), "Aliases must include the source title: " + id);
            JsonObject support = clue.getJsonObject("support");
            require(support.getString("documentId").equals(id) && support.getString("rawSha256").equals(document.getString("rawSha256"))
                    && PackCatalog.validText(support.getString("quote"), 600), "Support/source fingerprint mismatch: " + id);
        }
        return cases;
    }
    static void disjoint(List<JsonObject> development, List<JsonObject> heldOut) throws IOException {
        Set<String> ids = new HashSet<>(), answers = new HashSet<>();
        for (JsonObject clue : development) { ids.add(clue.getString("id")); answers.add(clue.getString("answerDocumentId")); }
        for (JsonObject clue : heldOut) require(!ids.contains(clue.getString("id")) && !answers.contains(clue.getString("answerDocumentId")), "Development/held-out case or answer overlap");
    }
    static void corpus(Path corpus, JsonObject source, List<JsonObject> cases) throws IOException {
        Path file = corpus.resolve("source.txt");
        require(Files.size(file) == source.getInt("sourceBytes") && Files.size(file) <= 64 * 1024 * 1024, "Wrong or oversized corpus snapshot");
        byte[] bytes = Files.readAllBytes(file);
        require(sha(bytes).equals(source.getString("sourceSha256")), "Corpus SHA-256 differs from frozen collection");
        try (var files = Files.list(corpus)) { require(files.filter(p -> p.toString().endsWith(".txt")).count() == 1, "Corpus must contain only the single frozen .txt shard"); }
        String[] lines = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().split("\n", -1);
        for (JsonObject document : source.getJsonArray("documents").getValuesAs(JsonObject.class)) {
            require(document.getInt("firstLine") >= 1 && document.getInt("lastLine") >= document.getInt("firstLine")
                    && document.getInt("lastLine") <= lines.length, "Invalid source line range: " + document.getString("id"));
            String raw = String.join("\n", Arrays.copyOfRange(lines, document.getInt("firstLine") - 1, document.getInt("lastLine")));
            require(sha(raw.getBytes(StandardCharsets.UTF_8)).equals(document.getString("rawSha256")), "Source passage boundaries differ: " + document.getString("id"));
            for (JsonObject clue : cases) if (clue.getString("answerDocumentId").equals(document.getString("id")))
                require(raw.contains(clue.getJsonObject("support").getString("quote")), "Support passage absent: " + clue.getString("id"));
        }
    }
    static JsonObject provenance(JsonObject description, JsonObject source, JsonObject frozen) throws IOException {
        JsonObject metadata = description.getJsonObject("metadata");
        require(description.getInt("documents") == source.getInt("documentCount")
                && source.getString("sourceSha256").equals(metadata.getString("sourceSha256", ""))
                && frozen.getString("collectionSha256").equals(metadata.getString("manifestSha256", ""))
                && RuntimeInfo.lucene().equals(metadata.getString("luceneVersion", ""))
                && RuntimeInfo.nlp().equals(metadata.getString("nlpVersion", "")), "Index does not match the frozen experiment/runtime; build separately from the exact source snapshot.");
        return description;
    }
    static JsonObject measure(Path base, JsonObject source, JsonObject frozen, List<JsonObject> cases, RetrievalProfile profile) throws Exception {
        try (Indexer indexer = new Indexer(base.toString(), TYPE); QueryEngine engine = new QueryEngine(TYPE, SCORER, indexer, profile)) {
            JsonObject description = provenance(engine.describeIndex(), source, frozen);
            double warmup = engine.ask("clue retrieval warmup", 10).responseTimeMs;
            List<Double> times = new ArrayList<>(); JsonArrayBuilder predictions = Json.createArrayBuilder();
            int first = 0, hit = 0; double rr = 0;
            for (JsonObject clue : cases) {
                String query = clue.getString("category") + " " + clue.getString("clue");
                String aliases = String.join("|", clue.getJsonArray("aliases").getValuesAs(JsonString.class).stream().map(JsonString::getString).toList());
                int rank = -1; QueryResponse response = null;
                for (int repeat = 0; repeat < REPEATS; repeat++) {
                    // This is the entire engine input. Labels/support stay in this evaluator.
                    QueryResponse next = engine.ask(query, 10); times.add(next.responseTimeMs);
                    int found = 0;
                    for (var candidate : next.candidates) if (Evaluation.accepts(aliases, candidate.title)) { found = candidate.rank; break; }
                    require(rank == -1 || rank == found, "Nonrepeatable candidate ranking for " + clue.getString("id"));
                    rank = found; response = next;
                }
                if (rank == 1) first++; if (rank > 0) { hit++; rr += 1.0 / rank; }
                JsonArrayBuilder candidates = Json.createArrayBuilder();
                response.candidates.forEach(c -> candidates.add(Json.createObjectBuilder().add("rank", c.rank).add("title", c.title).add("rawRetrievalScore", c.score)));
                JsonObjectBuilder prediction = Json.createObjectBuilder().add("id", clue.getString("id")).add("acceptedRank", rank).add("candidates", candidates)
                        .add("curatedAnswerSupport", clue.getJsonObject("support"));
                if (response.candidates.isEmpty()) prediction.addNull("opponentRetrieved");
                else {
                    var top = response.candidates.get(0);
                    prediction.add("opponentRetrieved", Json.createObjectBuilder().add("title", top.title).add("text", top.evidence).add("source", top.source).add("kind", top.evidenceKind));
                }
                predictions.add(prediction);
            }
            Collections.sort(times); int size = cases.size();
            return Json.createObjectBuilder().add("profile", profile.id).add("index", description)
                    .add("size", size).add("correctFirst", first).add("foundWithin10", hit)
                    .add("accuracy", (double) first / size).add("hitAt10", (double) hit / size).add("mrrAt10", rr / size)
                    .add("timing", Json.createObjectBuilder().add("samples", times.size()).add("warmupMs", warmup)
                            .add("minMs", times.get(0)).add("medianMs", percentile(times, .5)).add("p95Ms", percentile(times, .95))
                            .add("meanMs", times.stream().mapToDouble(Double::doubleValue).average().orElseThrow()).add("maxMs", times.get(times.size() - 1)))
                    .add("predictions", predictions).build();
        }
    }
    static double percentile(List<Double> sorted, double fraction) { return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * fraction) - 1)); }
    static String select(List<JsonObject> reports) {
        JsonObject best = reports.get(0);
        for (JsonObject report : reports) {
            int comparison = Double.compare(report.getJsonNumber("mrrAt10").doubleValue(), best.getJsonNumber("mrrAt10").doubleValue());
            if (comparison == 0) comparison = Integer.compare(report.getInt("correctFirst"), best.getInt("correctFirst"));
            if (comparison == 0) comparison = Integer.compare(report.getInt("foundWithin10"), best.getInt("foundWithin10"));
            if (comparison > 0) best = report;
        }
        return best.getString("profile");
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 5) throw new IllegalArgumentException("RetrievalLab validate <dataset> [corpus] | index <dataset> <corpus> <new-index-base> | baseline/develop <dataset> <index-base> | test <dataset> <index-base> <development-report>");
        String mode = args[0]; Path root = Paths.get(args[1]); JsonObject frozen = freeze(root), source = manifest(root, frozen);
        if (mode.equals("index")) {
            require(args.length == 4, "index needs a corpus and separate new index base");
            corpus(Paths.get(args[2]), source, Collections.emptyList()); // No private split is opened for indexing.
            try (Indexer indexer = new Indexer(args[3], TYPE)) {
                indexer.parseWikis(Paths.get(args[2]), false, Map.of("sourceSha256", source.getString("sourceSha256"), "manifestSha256", frozen.getString("collectionSha256"), "datasetId", source.getString("id"), "datasetVersion", source.getString("version")));
            }
            System.out.println("Built frozen source-only experiment index; no private clues were read."); return;
        }
        List<JsonObject> development = split(root, frozen, source, "development");
        if (mode.equals("validate")) {
            require(args.length <= 3, "validate accepts an optional corpus directory");
            List<JsonObject> held = split(root, frozen, source, "held-out"); disjoint(development, held);
            List<JsonObject> both = new ArrayList<>(development); both.addAll(held);
            if (args.length == 3) corpus(Paths.get(args[2]), source, both);
            System.out.println(Json.createObjectBuilder().add("valid", true).add("documents", source.getInt("documentCount")).add("development", development.size()).add("heldOut", held.size()).add("humanApproved", false).build()); return;
        }
        require(args.length >= 3, "An experiment index base is required"); Path base = Paths.get(args[2]);
        JsonObjectBuilder output = Json.createObjectBuilder().add("schemaVersion", 1).add("experimentId", frozen.getString("id")).add("version", frozen.getString("version"))
                .add("mode", mode).add("measuredAt", Instant.now().toString()).add("runtime", RuntimeInfo.json())
                .add("frozenSha256", sha(Files.readAllBytes(root.resolve("experiment.json"))))
                .add("collectionSha256", frozen.getString("collectionSha256")).add("developmentSha256", frozen.getString("developmentSha256"))
                .add("classification", "same-author-historical-source-pilot-not-general-accuracy");
        List<JsonObject> reports = new ArrayList<>();
        if (mode.equals("baseline") || mode.equals("develop")) {
            require(args.length == 3, "baseline/develop accept only dataset and index base");
            for (RetrievalProfile profile : RetrievalProfile.values()) {
                if (mode.equals("baseline") && profile != RetrievalProfile.BASELINE) continue;
                reports.add(measure(base, source, frozen, development, profile));
            }
            output.add("split", "development");
            if (mode.equals("develop")) output.add("selectedProfile", select(reports));
        } else if (mode.equals("test")) {
            require(args.length == 4, "test needs the sealed development report");
            Path selectionFile = Paths.get(args[3]); JsonObject selection = PackCatalog.read(selectionFile);
            require(selection.getString("mode").equals("develop") && selection.getString("developmentSha256").equals(frozen.getString("developmentSha256"))
                    && selection.getString("collectionSha256").equals(frozen.getString("collectionSha256"))
                    && selection.getString("frozenSha256").equals(sha(Files.readAllBytes(root.resolve("experiment.json")))), "Selection belongs to another frozen experiment");
            require(selection.getJsonObject("runtime").getString("lucene").equals(RuntimeInfo.lucene()) && selection.getJsonObject("runtime").getString("corenlp").equals(RuntimeInfo.nlp()), "Selection runtime differs");
            List<JsonObject> trials = selection.getJsonArray("reports").getValuesAs(JsonObject.class);
            require(trials.size() == 3, "Selection must contain the three predeclared development trials");
            for (int i = 0; i < 3; i++) require(trials.get(i).getString("profile").equals(RetrievalProfile.values()[i].id), "Trial order/configuration differs");
            String selected = select(trials); require(selected.equals(selection.getString("selectedProfile")), "Selected profile differs from development rule");
            // The held-out file is opened only after the development selection is validated.
            List<JsonObject> held = split(root, frozen, source, "held-out"); disjoint(development, held);
            reports.add(measure(base, source, frozen, held, RetrievalProfile.BASELINE));
            if (!selected.equals("baseline")) reports.add(measure(base, source, frozen, held, RetrievalProfile.from(selected)));
            output.add("split", "held-out").add("heldOutSha256", frozen.getString("heldOutSha256"))
                    .add("selectionSha256", sha(Files.readAllBytes(selectionFile))).add("selectedProfile", selected);
        } else throw new IllegalArgumentException("Unknown experiment command: " + mode);
        JsonArrayBuilder values = Json.createArrayBuilder(); reports.forEach(values::add); output.add("reports", values);
        System.out.println(output.build());
    }
}
