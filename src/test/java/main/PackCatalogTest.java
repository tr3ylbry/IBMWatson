package main;

import org.junit.jupiter.api.*;
import jakarta.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class PackCatalogTest {
    private Path root;
    @BeforeEach void setup() throws Exception {
        root = Files.createTempDirectory("cluelab-packs-");
        try (Stream<Path> paths = Files.walk(Paths.get("data/packs"))) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = root.resolve(Paths.get("data/packs").relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
    }
    @AfterEach void cleanup() throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    private Path file(String relative) { return root.resolve(relative); }
    private JsonObjectBuilder copy(JsonObject value) {
        JsonObjectBuilder builder = Json.createObjectBuilder(); value.forEach(builder::add); return builder;
    }
    private JsonArrayBuilder copy(JsonArray value) {
        JsonArrayBuilder builder = Json.createArrayBuilder(); value.forEach(builder::add); return builder;
    }
    private void replace(String path, String old, String replacement) throws Exception {
        Path file = file(path); String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text.contains(old)); Files.write(file, text.replace(old, replacement).getBytes(StandardCharsets.UTF_8));
    }
    private void invalid(String contains) {
        IOException error = assertThrows(IOException.class, () -> PackCatalog.load(file("catalog.json")));
        assertTrue(error.getMessage().contains(contains), error.getMessage());
    }
    private static final String PACK = "private/general-knowledge-clues.json";

    @Test void starterCountsDistinctAnswersAndReviewAreExplicit() throws Exception {
        PackCatalog catalog = PackCatalog.load(file("catalog.json"));
        assertEquals(5, catalog.topics.size()); assertEquals(5, catalog.collections.size());
        for (PackCatalog.Pack pack : catalog.topics.values()) {
            if (pack.topicId.equals("coursework-demo")) continue;
            assertEquals(15, pack.clues.size());
            for (String level : Arrays.asList("easy", "standard", "hard")) assertEquals(5, pack.eligible(level).size());
            Set<String> answers = new HashSet<>();
            pack.clues.forEach(clue -> assertTrue(answers.add(clue.answerSourceId)));
            assertEquals("source-checked", pack.review.getString("status"));
            assertTrue(pack.review.getString("limitations").contains("No independent human"));
        }
    }
    @Test void legacyCluesAliasesHintsAndArticleTextArePreserved() throws Exception {
        PackCatalog.Pack pack = PackCatalog.load(file("catalog.json")).topics.get("coursework-demo");
        for (String level : Arrays.asList("easy", "standard")) {
            String name = level.equals("easy") ? "easy-questions.txt" : "questions.txt";
            List<String[]> original = Evaluation.readQuestions(Files.readAllBytes(Paths.get("data/demo/" + name)));
            List<PackCatalog.Clue> migrated = pack.eligible(level);
            assertEquals(original.size(), migrated.size());
            for (int i = 0; i < original.size(); i++) {
                assertEquals(original.get(i)[0], migrated.get(i).category);
                assertEquals(original.get(i)[1], migrated.get(i).retrievalText());
                assertEquals(original.get(i)[2], migrated.get(i).answers());
            }
        }
        assertTrue(pack.eligible("hard").isEmpty());
        String original = new String(Files.readAllBytes(Paths.get("data/demo/corpus/articles.txt")), StandardCharsets.UTF_8);
        pack.collection.sources.values().forEach(source -> {
            assertTrue(original.contains(source.text.trim())); assertNull(source.revisionId);
            assertEquals("legacy-adaptation", source.snapshotKind);
        });
        assertEquals(100, Evaluation.readQuestions(Files.readAllBytes(Paths.get("questions.txt"))).size());
    }
    @Test void unsupportedSchemaIsRejected() throws Exception {
        replace(PACK, "\"schemaVersion\": 1", "\"schemaVersion\": 2"); invalid("schemaVersion");
    }
    @Test void duplicateClueIdsAreRejected() throws Exception {
        replace(PACK, "general-knowledge-moon-01", "general-knowledge-sun-01"); invalid("Duplicate clue ID");
    }
    @Test void duplicateSourceIdsAreRejected() throws Exception {
        replace("collections/general-knowledge-sources.json", "\"id\": \"general-knowledge-moon\"", "\"id\": \"general-knowledge-sun\""); invalid("Duplicate source ID");
    }
    @Test void duplicateCatalogIdsAreRejected() throws Exception {
        replace("catalog.json", "\"id\": \"standard\"", "\"id\": \"easy\""); invalid("Duplicate difficulty");
    }
    @Test void duplicateJsonPropertiesAreRejected() throws Exception {
        replace(PACK, "\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"); invalid("duplicate JSON property");
    }
    @Test void missingSnapshotIsActionable() throws Exception {
        Files.delete(file("snapshots/general-knowledge/sun.txt")); invalid("sun.txt");
    }
    @Test void changedSnapshotRequiresReviewedFingerprint() throws Exception {
        Files.write(file("snapshots/general-knowledge/sun.txt"), "tampered".getBytes(StandardCharsets.UTF_8)); invalid("SHA-256 mismatch");
    }
    @Test void missingSupportSourceIsRejected() throws Exception {
        replace(PACK, "\"sourceId\": \"general-knowledge-sun\"", "\"sourceId\": \"missing\""); invalid("support references a missing source");
    }
    @Test void answerTitleMustBeInAliasesAndCollection() throws Exception {
        replace(PACK, "\"Sun\",", "\"Wrong Title\","); invalid("aliases must include the searchable article title");
    }
    @Test void answerSourceCannotComeFromAnotherTopicCollection() throws Exception {
        replace(PACK, "\"answerSourceId\": \"general-knowledge-sun\"", "\"answerSourceId\": \"geography-amazon\""); invalid("missing answer source");
    }
    @Test void normalizedDuplicateAliasesAreRejected() throws Exception {
        replace(PACK, "\"The Sun\"", "\"SUN\""); invalid("aliases must be nonempty, unique");
    }
    @Test void emptyAliasesAreRejected() throws Exception {
        replace(PACK, "\"The Sun\"", "\"\""); invalid("aliases must be nonempty");
    }
    @Test void pipeAliasesAreRejected() throws Exception {
        replace(PACK, "\"The Sun\"", "\"Sun|Sol\""); invalid("aliases must be nonempty");
    }
    @Test void unavailableAnswerExtractionIsRejected() throws Exception {
        replace(PACK, "\"article-title\"", "\"date\""); invalid("answerType");
    }
    @Test void inconsistentTopicAndDifficultyAreRejected() throws Exception {
        replace(PACK, "\"topicId\": \"general-knowledge\"", "\"topicId\": \"music\""); invalid("topic");
    }
    @Test void undeclaredDifficultyIsRejected() throws Exception {
        replace(PACK, "\"difficulty\": \"easy\"", "\"difficulty\": \"impossible\""); invalid("difficulty is not declared");
    }
    @Test void unknownSubtopicIsRejected() throws Exception {
        replace(PACK, "\"subtopicId\": \"space\"", "\"subtopicId\": \"missing\""); invalid("unknown subtopicId");
    }
    @Test void packAndCollectionVersionsMustMatch() throws Exception {
        replace(PACK, "\"collectionVersion\": \"1.0.0\"", "\"collectionVersion\": \"2.0.0\""); invalid("collectionVersion mismatch");
    }
    @Test void malformedJsonAndUnknownFieldsAreRejected() throws Exception {
        replace(PACK, "\"schemaVersion\": 1", "\"schemaVersion\": 1, \"typo\": 1"); invalid("typo");
        Files.write(file(PACK), "{broken".getBytes(StandardCharsets.UTF_8)); invalid("JSON");
    }
    @Test void malformedUtf8IsRejected() throws Exception {
        Files.write(file("snapshots/general-knowledge/sun.txt"), new byte[]{(byte) 0xc3, 0x28}); invalid("UTF-8");
    }
    @Test void trailingJsonIsRejected() throws Exception {
        Files.write(file(PACK), "{}".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        invalid("JSON");
    }
    @Test void aNewTopicAndPackCanReuseACollectionWithoutCodeChanges() throws Exception {
        JsonObject original = PackCatalog.read(file("private/music-clues.json"));
        JsonArrayBuilder clues = Json.createArrayBuilder();
        for (JsonValue value : original.getJsonArray("clues")) {
            JsonObject clue = (JsonObject) value;
            clues.add(copy(clue).add("id", "new-" + clue.getString("id")).add("topicId", "new-topic"));
        }
        JsonObject pack = copy(original).add("id", "new-pack").add("topicId", "new-topic")
                .add("label", "New Topic").add("clues", clues).build();
        Files.write(file("private/new-pack.json"), pack.toString().getBytes(StandardCharsets.UTF_8));
        JsonObject oldCatalog = PackCatalog.read(file("catalog.json"));
        JsonArrayBuilder packs = copy(oldCatalog.getJsonArray("packs"));
        packs.add(Json.createObjectBuilder().add("id", "new-pack").add("version", "1.0.0").add("path", "private/new-pack.json"));
        JsonArrayBuilder topics = copy(oldCatalog.getJsonArray("topics"));
        topics.add(Json.createObjectBuilder().add("id", "new-topic").add("packId", "new-pack").add("packVersion", "1.0.0"));
        Files.write(file("catalog.json"), copy(oldCatalog).add("packs", packs).add("topics", topics)
                .build().toString().getBytes(StandardCharsets.UTF_8));
        PackCatalog expanded = PackCatalog.load(file("catalog.json"));
        assertEquals(6, expanded.topics.size()); assertEquals(5, expanded.collections.size());
        assertSame(expanded.topics.get("music").collection, expanded.topics.get("new-topic").collection);
        try (GameService game = new GameService(expanded, (scope, category, clue) -> { throw new IOException("test engine"); })) {
            String owner = game.openSession(null);
            assertEquals("new-topic", game.start(owner, "new-topic", "hard", "1.0.0", false).getString("topicId"));
        }
    }
    @Test void pathTraversalIsRejected() throws Exception {
        replace("catalog.json", "collections/general-knowledge-sources.json", "../outside.json"); invalid("outside");
    }
    @Test void invalidPassageOffsetsAreRejected() throws Exception {
        replace(PACK, "\"start\": 0", "\"start\": -1"); invalid("passage offsets");
    }
    @Test void draftPackRemainsVisibleButHasNoEligibleClues() throws Exception {
        replace("private/music-clues.json", "\"source-checked\"", "\"draft\"");
        PackCatalog.Pack pack = PackCatalog.load(file("catalog.json")).topics.get("music");
        assertEquals(15, pack.clues.size()); assertTrue(pack.eligible("easy").isEmpty());
    }
}
