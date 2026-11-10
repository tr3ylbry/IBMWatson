package main;

import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeModernizationTest {
    @TempDir Path root;

    @Test void indexIdentityIncludesFormatAndDependencyVersions() throws Exception {
        try (Indexer indexer = new Indexer(root.toString(), 1)) {
            indexer.indexArticles(Collections.singletonList(new Indexer.Article("one", "First", "Category", "A source passage.")), Collections.emptyMap(), false);
            try (QueryEngine engine = new QueryEngine(1, 4, indexer)) {
                var description = engine.describeIndex(); var metadata = description.getJsonObject("metadata");
                assertEquals(RuntimeInfo.INDEX_SCHEMA, metadata.getString("cluelab.schema"));
                assertEquals(RuntimeInfo.lucene(), metadata.getString("luceneVersion"));
                assertEquals(RuntimeInfo.nlp(), metadata.getString("nlpVersion"));
                assertEquals(1, description.getInt("documents"));
                assertTrue(RuntimeInfo.indexNamespace().endsWith("-schema-" + RuntimeInfo.INDEX_SCHEMA));
            }
        }
    }

    @Test void explicitRebuildCannotReplaceAnIndexFromAnotherRuntime() throws Exception {
        try (Indexer indexer = new Indexer(root.toString(), 1)) {
            var articles = Collections.singletonList(new Indexer.Article("one", "Original", "", "Keep this content."));
            indexer.indexArticles(articles, Collections.emptyMap(), false);
            try (Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
                 IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(indexer.getAnalyzer()))) {
                Map<String, String> old = new HashMap<>();
                old.put("cluelab.schema", "1"); old.put("luceneVersion", "7.7.1"); old.put("nlpVersion", "3.9.2");
                writer.setLiveCommitData(old.entrySet()); writer.commit();
            }
            IOException error = assertThrows(IOException.class, () -> indexer.indexArticles(articles, Collections.emptyMap(), true));
            assertTrue(error.getMessage().contains("separate index base"));
            try (Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath())); DirectoryReader reader = DirectoryReader.open(directory)) {
                assertEquals(1, reader.numDocs());
                assertEquals("7.7.1", reader.getIndexCommit().getUserData().get("luceneVersion"));
                assertEquals("Original", reader.storedFields().document(0).get("displayTitle"));
            }
        }
    }

    @Test void publicHintAndLaterSentencesRemainInEveryPreprocessingMode() throws Exception {
        for (int type = 1; type <= 3; type++) try (Indexer indexer = new Indexer(root.resolve("type-" + type).toString(), type)) {
            indexer.indexArticles(Collections.singletonList(new Indexer.Article("one", "Original", "", "Ordinary first sentence. Electromagnetism appears later.")), Collections.emptyMap(), false);
            try (QueryEngine engine = new QueryEngine(type, 4, indexer)) {
                assertTrue(engine.queryMethodinator("First sentence. Hint: electromagnetism.").contains("electromagnet"));
                assertEquals("Original", engine.ask("electromagnetism", 10).candidates.get(0).title);
            }
        }
    }
}
