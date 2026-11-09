package main;

import jakarta.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalLabTest {
    @TempDir Path temporary;
    private final Path dataset = Paths.get("data/retrieval-lab/v1");
    private JsonObjectBuilder copy(JsonObject object) { JsonObjectBuilder builder = Json.createObjectBuilder(); object.forEach(builder::add); return builder; }

    @Test void frozenPilotHasDisjointCasesAndMostlyAdditionalSourceDocuments() throws Exception {
        JsonObject frozen = RetrievalLab.freeze(dataset), source = RetrievalLab.manifest(dataset, frozen);
        var dev = RetrievalLab.split(dataset, frozen, source, "development");
        var held = RetrievalLab.split(dataset, frozen, source, "held-out");
        RetrievalLab.disjoint(dev, held);
        assertEquals(645, source.getInt("documentCount")); assertEquals(20, dev.size()); assertEquals(20, held.size());
        assertTrue(source.isNull("revisionId"));
        for (var clue : dev) { assertTrue(clue.isNull("revisionId")); assertTrue(clue.getString("reviewStatus").contains("not-human-approved")); }
        for (var document : source.getJsonArray("documents").getValuesAs(JsonObject.class)) {
            assertFalse(document.containsKey("clue")); assertFalse(document.containsKey("aliases")); assertFalse(document.containsKey("support"));
        }
    }

    @Test void changedSplitFailsFingerprintBeforeRetrieval() throws Exception {
        Path changed = temporary.resolve("changed.json"); Files.writeString(changed, "{}", StandardCharsets.UTF_8);
        IOException error = assertThrows(IOException.class, () -> RetrievalLab.verified(changed, "0".repeat(64)));
        assertTrue(error.getMessage().contains("new dataset version"));
    }

    @Test void overlapIsRejectedRatherThanCountedAsHeldOut() throws Exception {
        var frozen = RetrievalLab.freeze(dataset); var source = RetrievalLab.manifest(dataset, frozen);
        var dev = RetrievalLab.split(dataset, frozen, source, "development");
        assertThrows(IOException.class, () -> RetrievalLab.disjoint(dev, Collections.singletonList(dev.get(0))));
    }

    private void invalidClue(String field, JsonValue replacement, String expected) throws Exception {
        var frozen = RetrievalLab.freeze(dataset); var source = RetrievalLab.manifest(dataset, frozen);
        var original = PackCatalog.read(dataset.resolve("private/development.json"));
        var cases = original.getJsonArray("cases").getValuesAs(JsonObject.class);
        JsonArrayBuilder changed = Json.createArrayBuilder(); changed.add(copy(cases.get(0)).add(field, replacement));
        cases.subList(1, cases.size()).forEach(changed::add);
        Path root = temporary.resolve(field); Files.createDirectories(root.resolve("private"));
        Path file = root.resolve("private/development.json"); Files.writeString(file, copy(original).add("cases", changed).build().toString());
        var newFreeze = copy(frozen).add("developmentSha256", RetrievalLab.sha(Files.readAllBytes(file))).build();
        IOException error = assertThrows(IOException.class, () -> RetrievalLab.split(root, newFreeze, source, "development"));
        assertTrue(error.getMessage().contains(expected), error.getMessage());
    }
    @Test void unsupportedAnswerTypeIsRejected() throws Exception {
        invalidClue("answerType", Json.createValue("date"), "answer type");
    }
    @Test void missingSourcesAndInvalidAliasesAreRejected() throws Exception {
        invalidClue("answerDocumentId", Json.createValue("missing"), "Missing");
        invalidClue("aliases", Json.createArrayBuilder().add("").build(), "alias");
    }

    private JsonObject metric(String profile, double mrr, int first, int hit) {
        return Json.createObjectBuilder().add("profile", profile).add("mrrAt10", mrr).add("correctFirst", first).add("foundWithin10", hit).build();
    }
    @Test void developmentSelectionIsDeterministicAndTiesPreferBaseline() {
        JsonObject baseline = metric("baseline", .8, 15, 20), weighted = metric("title-category", .8, 15, 20), coverage = metric("coverage", .8, 15, 20);
        assertEquals("baseline", RetrievalLab.select(Arrays.asList(baseline, weighted, coverage)));
        assertEquals("coverage", RetrievalLab.select(Arrays.asList(baseline, weighted, metric("coverage", .85, 16, 20))));
        assertEquals("title-category", RetrievalLab.select(Arrays.asList(baseline, metric("title-category", .8, 16, 20), coverage)));
    }
    @Test void latencyPercentilesUseNearestRankWithoutPretendingSamplesAreIndependent() {
        assertEquals(2, RetrievalLab.percentile(Arrays.asList(1d, 2d, 3d, 4d), .5));
        assertEquals(4, RetrievalLab.percentile(Arrays.asList(1d, 2d, 3d, 4d), .95));
    }

    @Test void indexBuildDoesNotRequireOrReadPrivateClueFiles() throws Exception {
        Path root = temporary.resolve("public-dataset"), corpus = temporary.resolve("corpus"), base = temporary.resolve("index");
        Files.createDirectories(root); Files.createDirectories(corpus);
        String text = "[[Alpha]]\nA public source passage.\n"; byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Files.write(corpus.resolve("source.txt"), bytes);
        var document = Json.createObjectBuilder().add("id", "one").add("title", "Alpha").add("firstLine", 1).add("lastLine", 3).add("rawSha256", RetrievalLab.sha(bytes)).build();
        var source = Json.createObjectBuilder().add("schemaVersion", 1).add("id", "public-only").add("version", "1.0.0")
                .add("sourceBytes", bytes.length).add("sourceSha256", RetrievalLab.sha(bytes)).add("documentCount", 1).add("documents", Json.createArrayBuilder().add(document)).build();
        Files.writeString(root.resolve("collection.json"), source.toString());
        var frozen = copy(RetrievalLab.freeze(dataset)).add("collectionSha256", RetrievalLab.sha(Files.readAllBytes(root.resolve("collection.json")))).build();
        Files.writeString(root.resolve("experiment.json"), frozen.toString());
        RetrievalLab.main(new String[]{"index", root.toString(), corpus.toString(), base.toString()});
        assertFalse(Files.exists(root.resolve("private")));
        try (Indexer indexer = new Indexer(base.toString(), 1); QueryEngine engine = new QueryEngine(1, 4, indexer)) {
            assertEquals("Alpha", engine.ask("public passage", 10).candidates.get(0).title);
            RetrievalLab.provenance(engine.describeIndex(), source, frozen);
        }
    }

    @Test void profilesKeepLabelsOutsideRankingAndReportRawRetrievalScores() throws Exception {
        try (Indexer indexer = new Indexer(temporary.resolve("profiles").toString(), 1)) {
            indexer.indexArticles(Arrays.asList(new Indexer.Article("partial", "Partial", "", "alpha"),
                    new Indexer.Article("complete", "Complete", "", "alpha beta gamma " + "noise ".repeat(1000))), Collections.emptyMap(), false);
            try (QueryEngine baseline = new QueryEngine(1, 3, indexer);
                 QueryEngine coverage = new QueryEngine(1, 3, indexer, RetrievalProfile.COVERAGE);
                 QueryEngine weighted = new QueryEngine(1, 3, indexer, RetrievalProfile.TITLE_CATEGORY)) {
                assertEquals("Partial", baseline.ask("alpha beta gamma", 10).candidates.get(0).title);
                var response = coverage.ask("Which alpha beta gamma?", 10);
                assertEquals("Complete", response.candidates.get(0).title);
                assertEquals("source-excerpt", response.candidates.get(0).evidenceKind);
                assertEquals("coverage", response.index.getString("retrievalProfile"));
                var again = coverage.ask("Which alpha beta gamma?", 10);
                assertEquals(response.candidates.get(0).score, again.candidates.get(0).score);
                assertTrue(weighted.explain("Complete", 1).contains("titleText"));
                assertFalse(baseline.explain("Complete", 1).contains("titleText"));
                assertTrue(coverage.ask("zzzznonmatching", 10).candidates.isEmpty());
                assertThrows(IllegalArgumentException.class, () -> RetrievalProfile.from("answer-key"));
                assertFalse(response.toJson().toString().contains("acceptedAnswers"));
            }
        }
    }
}
