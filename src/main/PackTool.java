package main;

import jakarta.json.*;
import java.nio.file.*;
import java.util.*;

/** Operator-only validation, source indexing and independently identified pack evaluations. */
public final class PackTool {
    private PackTool() {}
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && Set.of("review-preview", "activation-plan", "activate", "rollback", "sandbox").contains(args[0])) {
            PackActivation.main(args); return;
        }
        if (args.length < 1 || args.length > 3) throw new IllegalArgumentException("Usage: validate|index|evaluate [game-config.json] [--indexes|--rebuild]");
        GameConfig config = GameConfig.load(Paths.get(args.length >= 2 ? args[1] : "data/game.json"));
        String option = args.length == 3 ? args[2] : "";
        CollectionIndex index = new CollectionIndex(config);
        switch (args[0]) {
            case "validate":
                if (!option.isEmpty() && !option.equals("--indexes")) throw new IllegalArgumentException("validate supports only --indexes");
                if (option.equals("--indexes")) for (PackCatalog.Collection collection : config.catalog.collections.values()) index.verify(collection);
                System.out.println(validation(config.catalog)); break;
            case "index":
                if (!option.isEmpty() && !option.equals("--rebuild")) throw new IllegalArgumentException("index supports only --rebuild");
                index.build(option.equals("--rebuild"));
                System.out.println("Verified/built " + config.catalog.collections.size() + " source collections. Private clue packs were not indexed."); break;
            case "evaluate":
                if (!option.isEmpty()) throw new IllegalArgumentException("evaluate accepts no extra options");
                JsonArrayBuilder reports = Json.createArrayBuilder();
                for (PackCatalog.Pack pack : config.catalog.topics.values()) reports.add(evaluate(pack, index, config));
                System.out.println(reports.build()); break;
            default: throw new IllegalArgumentException("Unknown pack command: " + args[0]);
        }
    }
    static JsonObject validation(PackCatalog catalog) {
        JsonArrayBuilder packs = Json.createArrayBuilder();
        for (PackCatalog.Pack pack : catalog.topics.values()) {
            JsonObjectBuilder counts = Json.createObjectBuilder();
            catalog.difficulties.keySet().forEach(level -> counts.add(level, pack.eligible(level).size()));
            packs.add(Json.createObjectBuilder().add("packId", pack.id).add("version", pack.version)
                    .add("topicId", pack.topicId).add("clues", pack.clues.size()).add("eligibleCounts", counts)
                    .add("review", pack.review).add("packSha256", pack.digest)
                    .add("sourceScope", pack.collection.scope.key()).add("documents", pack.collection.sources.size()));
        }
        return Json.createObjectBuilder().add("structurallyValid", true).add("catalogId", catalog.id)
                .add("catalogVersion", catalog.version).add("note", "Structural checks do not certify factual accuracy, licensing or difficulty.")
                .add("packs", packs).build();
    }
    static JsonObject evaluate(PackCatalog.Pack pack, CollectionIndex index, GameConfig config) throws Exception {
        JsonArrayBuilder predictions = Json.createArrayBuilder();
        Map<String, Metrics> levels = new LinkedHashMap<>(); Metrics total = new Metrics();
        for (PackCatalog.Clue clue : pack.clues) {
            if (!pack.reviewed) continue;
            // Support ranges, aliases and answerSourceId never cross this retrieval boundary.
            QueryResponse response = index.ask(pack.collection.scope, clue.category, clue.retrievalText());
            int rank = 0;
            for (int i = 0; i < response.candidates.size(); i++) if (Evaluation.accepts(clue.answers(), response.candidates.get(i).title)) { rank = i + 1; break; }
            total.add(rank); levels.computeIfAbsent(clue.difficulty, level -> new Metrics()).add(rank);
            predictions.add(Json.createObjectBuilder().add("clueId", clue.id).add("difficulty", clue.difficulty).add("acceptedRank", rank)
                    .add("prediction", response.candidates.isEmpty() ? "" : response.candidates.get(0).title));
        }
        JsonObjectBuilder breakdown = Json.createObjectBuilder(); levels.forEach((level, metrics) -> breakdown.add(level, metrics.json()));
        return Json.createObjectBuilder().add("packId", pack.id).add("packVersion", pack.version).add("packSha256", pack.digest)
                .add("topicId", pack.topicId).add("sourceScope", pack.collection.scope.key()).add("collectionDigest", pack.collection.scope.digest)
                .add("documents", pack.collection.sources.size()).add("indexType", config.indexType).add("scorer", config.scorer)
                .add("indexProvenance", index.describe(pack.collection))
                .add("indexOrigin", "fresh-source-collection-not-coursework-archive")
                .add("classification", "small-curated-development-pack-not-held-out").add("hintPolicy", "public-to-both")
                .add("metrics", total.json()).add("byDifficulty", breakdown).add("predictions", predictions).build();
    }
    private static final class Metrics {
        int count, first, hit; double rr;
        void add(int rank) { count++; if (rank == 1) first++; if (rank > 0 && rank <= 10) { hit++; rr += 1.0 / rank; } }
        JsonObject json() {
            JsonObjectBuilder value = Json.createObjectBuilder().add("size", count).add("correctFirst", first).add("foundWithin10", hit);
            if (count == 0) return value.addNull("accuracy").addNull("hitAt10").addNull("mrrAt10").build();
            return value.add("accuracy", (double) first / count).add("hitAt10", (double) hit / count).add("mrrAt10", rr / count).build();
        }
    }
}
