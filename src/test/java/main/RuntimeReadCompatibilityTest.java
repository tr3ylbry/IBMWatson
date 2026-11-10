package main;

import jakarta.json.JsonObject;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeReadCompatibilityTest {
    @TempDir Path root;
    private Indexer indexer;
    private Path base, questions;

    @BeforeEach void buildNativeIndex() throws Exception {
        base = root.resolve("index");
        indexer = new Indexer(base.toString(), 1);
        indexer.indexArticles(List.of(new Indexer.Article("alpha", "Alpha", "Science", "quartz mineral")), Map.of(), false);
        questions = root.resolve("questions.txt");
        Files.writeString(questions, "Science\nquartz\nAlpha\n");
    }

    @AfterEach void close() { indexer.close(); }

    private static Map<String, String> identity() {
        return Map.of("cluelab.schema", RuntimeInfo.INDEX_SCHEMA, "indexType", "1",
                "luceneVersion", RuntimeInfo.lucene(), "nlpVersion", RuntimeInfo.nlp());
    }

    private void declare(Map<String, String> metadata) throws Exception {
        try (Directory directory = FSDirectory.open(Path.of(indexer.getIndexPath()));
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(indexer.getAnalyzer()))) {
            writer.setLiveCommitData(metadata.entrySet());
            writer.commit();
        }
    }

    private Map<String, String> files() throws Exception {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> paths = Files.list(Path.of(indexer.getIndexPath()))) {
            for (Path path : paths.toList()) hashes.put(path.getFileName().toString(), PackCatalog.sha256(Files.readAllBytes(path)));
        }
        return hashes;
    }

    private record Cli(int status, String output, String error) {}
    private Cli cli(String command) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(), error = new ByteArrayOutputStream();
        String[] args = command.equals("ask") ? new String[]{command, base.toString(), "1", "4", "quartz"}
                : new String[]{command, base.toString(), "1", "4", questions.toString(), "--json"};
        int status = Watson.run(args, new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Cli(status, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private void rejectedReadIsUnchanged() throws Exception {
        Map<String, String> before = files();
        for (RetrievalProfile profile : RetrievalProfile.values()) {
            IOException error = assertThrows(IOException.class, () -> new QueryEngine(1, 4, indexer, profile));
            assertTrue(error.getMessage().contains("declared {"));
            assertTrue(error.getMessage().contains("running {"));
            assertTrue(error.getMessage().contains("separate index base"));
        }
        for (String command : List.of("ask", "evaluate")) {
            Cli result = cli(command);
            assertEquals(1, result.status, command);
            assertEquals("", result.output, command);
            assertTrue(result.error.contains("Incompatible index identity"), result.error);
            assertTrue(result.error.contains("declared {"), result.error);
            assertTrue(result.error.contains("running {"), result.error);
        }
        assertEquals(before, files(), "Reads must not change any index file or its commit");
    }

    @Test void matchingDeclarationsAndCliReadsSucceedWithoutChangingTheCommit() throws Exception {
        Map<String, String> before = files();
        try (QueryEngine engine = new QueryEngine(1, 4, indexer)) {
            assertEquals("Alpha", engine.ask("quartz", 10).candidates.get(0).title);
            assertEquals("cluelab-rebuilt", engine.describeIndex().getString("origin"));
        }
        Cli ask = cli("ask"), evaluate = cli("evaluate");
        assertEquals(0, ask.status, ask.error); assertEquals(0, evaluate.status, evaluate.error);
        JsonObject report = PackCatalog.parse(evaluate.output, "evaluation");
        assertEquals(1, report.getInt("correctFirst"));
        assertEquals(before, files());
    }

    @ParameterizedTest
    @CsvSource({"nlpVersion,3.9.2", "luceneVersion,10.5.2"})
    void mismatchedRuntimesAreRejectedByEveryProfileAndCli(String field, String value) throws Exception {
        Map<String, String> metadata = new HashMap<>(identity()); metadata.put(field, value); declare(metadata);
        rejectedReadIsUnchanged();
        Cli error = cli("ask");
        assertTrue(error.error.contains(field + "=" + value));
        assertTrue(error.error.contains(field + "=" + identity().get(field)));
    }

    static Stream<Arguments> invalidDeclarations() {
        List<Arguments> cases = new ArrayList<>();
        for (String key : identity().keySet()) {
            Map<String, String> missing = new HashMap<>(identity()); missing.remove(key);
            cases.add(Arguments.of("missing " + key, missing));
            Map<String, String> malformed = new HashMap<>(identity()); malformed.put(key, "");
            cases.add(Arguments.of("empty " + key, malformed));
        }
        for (Map<String, String> partial : List.of(Map.of("nlpVersion", RuntimeInfo.nlp()), Map.of("indexType", "1"),
                Map.of("cluelab.schema", RuntimeInfo.INDEX_SCHEMA), Map.of("luceneVersion", RuntimeInfo.lucene()))) {
            cases.add(Arguments.of("partial " + partial.keySet(), partial));
        }
        Map<String, String> malformed = new HashMap<>(identity()); malformed.put("nlpVersion", "4.6.0-dev");
        cases.add(Arguments.of("malformed runtime", malformed));
        Map<String, String> wrongType = new HashMap<>(identity()); wrongType.put("indexType", "2");
        cases.add(Arguments.of("different processing", wrongType));
        Map<String, String> wrongSchema = new HashMap<>(identity()); wrongSchema.put("cluelab.schema", "999");
        cases.add(Arguments.of("unsupported schema", wrongSchema));
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDeclarations")
    void incompleteAndMalformedIdentitiesNeverFallBack(String name, Map<String, String> metadata) throws Exception {
        declare(metadata); rejectedReadIsUnchanged();
    }

    @Test void fullyUntaggedNativeLegacyReadsStayUnverifiedAndCannotBeRebuilt() throws Exception {
        try (Directory directory = FSDirectory.open(Path.of(indexer.getIndexPath()));
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(indexer.getAnalyzer())
                     .setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            Document doc = new Document();
            doc.add(new StringField("title", "alpha", Field.Store.YES));
            doc.add(new TextField("body", "quartz mineral", Field.Store.YES));
            writer.addDocument(doc); writer.setLiveCommitData(Map.<String, String>of().entrySet()); writer.commit();
        }
        Map<String, String> before = files();
        try (QueryEngine engine = new QueryEngine(1, 4, indexer)) {
            var response = engine.ask("quartz", 10);
            assertEquals("legacy-unverified", response.index.getString("origin"));
            assertTrue(response.index.getJsonObject("metadata").isEmpty());
            assertEquals("processed-index-text", response.candidates.get(0).evidenceKind);
        }
        assertEquals(0, cli("ask").status); assertEquals(0, cli("evaluate").status);
        assertThrows(IOException.class, () -> indexer.indexArticles(List.of(new Indexer.Article("new", "New", "Science", "quartz")), Map.of(), true));
        assertEquals(before, files());
    }

    @Test void collectionReadsKeepTheirStricterVerifiedIdentityRequirement() throws Exception {
        PackCatalog catalog = PackCatalog.load(Path.of("data/packs/catalog.json"));
        CollectionIndex indexes = new CollectionIndex(catalog.collections, root.resolve("collections"), 1, 4);
        indexes.build(false);
        PackCatalog.Collection music = catalog.topics.get("music").collection;
        assertFalse(indexes.ask(music.scope, "Music", "piano").candidates.isEmpty());
        try (Indexer source = new Indexer(indexes.base(music.scope).toString(), 1);
             Directory directory = FSDirectory.open(Path.of(source.getIndexPath()));
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(source.getAnalyzer()))) {
            writer.setLiveCommitData(Map.<String, String>of().entrySet()); writer.commit();
        }
        assertThrows(IOException.class, () -> indexes.ask(music.scope, "Music", "piano"));
    }
}
