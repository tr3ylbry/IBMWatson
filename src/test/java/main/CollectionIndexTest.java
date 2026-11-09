package main;

import org.junit.jupiter.api.*;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class CollectionIndexTest {
    private Path root;
    private PackCatalog catalog;
    private CollectionIndex indexes;
    @BeforeEach void setup() throws Exception {
        root = Files.createTempDirectory("cluelab-collection-index-");
        catalog = PackCatalog.load(Paths.get("data/packs/catalog.json"));
        indexes = new CollectionIndex(catalog.collections, root, 1, 4);
        indexes.build(false);
    }
    @AfterEach void cleanup() throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    @Test void indexesContainSourcesOnlyAndReuseDoesNotAppend() throws Exception {
        indexes.build(false);
        for (PackCatalog.Collection collection : catalog.collections.values()) {
            indexes.verify(collection);
            try (Indexer indexer = new Indexer(indexes.base(collection.scope).toString(), 1);
                 FSDirectory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
                 DirectoryReader reader = DirectoryReader.open(directory)) {
                assertEquals(collection.sources.size(), reader.numDocs());
                for (int i = 0; i < reader.maxDoc(); i++) {
                    org.apache.lucene.document.Document document = reader.storedFields().document(i);
                    PackCatalog.Source source = collection.sources.get(document.get("sourceFile"));
                    assertNotNull(source);
                    assertEquals(source.title, document.get("displayTitle"));
                    for (String field : Arrays.asList("aliases", "clueId", "answerSourceId", "difficulty", "support", "hint")) assertNull(document.get(field));
                    assertEquals(source.text.trim().replaceAll("\\s+", " "), document.get("evidence"));
                }
            }
        }
        indexes.build(true);
        for (PackCatalog.Collection collection : catalog.collections.values()) indexes.verify(collection);
    }
    @Test void retrievalCannotCrossTopicSourceScopes() throws Exception {
        PackCatalog.Collection music = catalog.topics.get("music").collection;
        QueryResponse response = indexes.ask(music.scope, "Space", "Sun Moon Mars DNA Piano");
        for (QueryResponse.Candidate candidate : response.candidates) {
            assertNotNull(music.title(candidate.title));
            assertNotEquals("Sun", candidate.title); assertNotEquals("Moon", candidate.title);
        }
        assertEquals("Piano", response.candidates.get(0).title);
    }
    @Test void unknownChangedAndUnbuiltScopesFailExplicitly() throws Exception {
        PackCatalog.Scope scope = catalog.topics.get("music").collection.scope;
        assertThrows(IOException.class, () -> indexes.ask(new PackCatalog.Scope("missing", "1.0.0", scope.digest), "Music", "Piano"));
        assertThrows(IOException.class, () -> indexes.ask(new PackCatalog.Scope(scope.id, "2.0.0", scope.digest), "Music", "Piano"));
        assertThrows(IOException.class, () -> indexes.ask(new PackCatalog.Scope(scope.id, scope.version, "changed"), "Music", "Piano"));
        CollectionIndex missing = new CollectionIndex(catalog.collections, root.resolve("unbuilt"), 1, 4);
        assertThrows(IOException.class, () -> missing.ask(scope, "Music", "Piano"));
    }
    @Test void legacyIndexesAreNotSilentlyReusedForDifferentSources() throws Exception {
        PackCatalog.Collection music = catalog.topics.get("music").collection;
        try (Indexer indexer = new Indexer(indexes.base(music.scope).toString(), 1)) {
            indexer.parseWikis(Paths.get("data/demo/corpus"), true);
        }
        IOException failure = assertThrows(IOException.class, () -> indexes.build(false));
        assertTrue(failure.getMessage().contains("explicitly rebuild"));
        assertThrows(IOException.class, () -> indexes.ask(music.scope, "Music", "Piano"));
        indexes.build(true); indexes.verify(music);
    }
    @Test void independentEvaluationReportsIdentitySizeAndBoundedMetrics() throws Exception {
        GameConfig config = GameConfig.load(Paths.get("data/game.json"));
        PackCatalog.Pack pack = catalog.topics.get("music");
        JsonObject report = PackTool.evaluate(pack, indexes, config);
        assertEquals(pack.digest, report.getString("packSha256"));
        assertEquals("music-sources@1.0.0", report.getString("sourceScope"));
        JsonObject metrics = report.getJsonObject("metrics");
        assertEquals(15, metrics.getInt("size")); assertEquals(15, report.getInt("documents"));
        assertTrue(metrics.getJsonNumber("accuracy").doubleValue() <= metrics.getJsonNumber("hitAt10").doubleValue());
        assertTrue(metrics.getJsonNumber("mrrAt10").doubleValue() <= 1);
        assertEquals(5, report.getJsonObject("byDifficulty").getJsonObject("hard").getInt("size"));
    }
}
