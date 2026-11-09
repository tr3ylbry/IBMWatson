package main;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class RetrievalFoundationTest {
    private Path root;
    private Path corpus;
    private Indexer indexer;

    @BeforeEach void setup() throws IOException {
        root = Files.createTempDirectory("watson-test-");
        corpus = Files.createDirectory(root.resolve("wiki"));
        Files.write(corpus.resolve("pages.txt"), Arrays.asList(
                "preamble should not become an article", "[[Alpha]]", "CATEGORIES: First category",
                "First article about alpha astronomy.", "[[Beta]]", "Second article about beta biology."), StandardCharsets.UTF_8);
        indexer = new Indexer(root.resolve("indexes").toString(), 1);
    }

    @AfterEach void cleanup() throws IOException {
        indexer.close();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
        }
    }

    private int count() throws IOException {
        try (Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            return reader.numDocs();
        }
    }

    @Test void rebuildDoesNotDuplicateAndRemovesDeletedArticles() throws Exception {
        indexer.parseWikis(corpus);
        assertThrows(IOException.class, () -> indexer.parseWikis(corpus));
        indexer.parseWikis(corpus, true);
        assertEquals(2, count());
        Files.write(corpus.resolve("pages.txt"), Arrays.asList("[[Beta]]", "Only surviving article."));
        indexer.parseWikis(corpus, true);
        assertEquals(1, count());
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            assertEquals("beta", engine.search("surviving", 10).get(0).getDocName().get("title"));
        }
    }

    @Test void categoryDoesNotLeakIntoFollowingArticle() throws Exception {
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            Result beta = engine.search("beta biology", 10).get(0);
            assertEquals("beta", beta.getDocName().get("title"));
            assertEquals(".", beta.getDocName().get("category"));
            assertFalse(beta.getDocName().get("body").contains("first category"));
        }
    }

    @Test void malformedBoundariesDoNotCreateArticles() throws Exception {
        Files.write(corpus.resolve("pages.txt"), Arrays.asList(
                "[[Alpha]]", "First article.", "[[Linked page]] trailing text", "[[]]", "[[File:Photo.jpg]]"));
        indexer.parseWikis(corpus);
        assertEquals(1, count());
    }

    @Test void failedRebuildKeepsPreviousCommittedIndex() throws Exception {
        indexer.parseWikis(corpus);
        Files.write(corpus.resolve("pages.txt"), Collections.singletonList("No valid article headers"));
        assertThrows(IOException.class, () -> indexer.parseWikis(corpus, true));
        assertEquals(2, count());
        Files.delete(corpus.resolve("pages.txt"));
        assertThrows(IOException.class, () -> indexer.parseWikis(corpus, true));
        assertEquals(2, count());
    }

    @Test void ignoresMetadataAndNestedDirectories() throws Exception {
        Files.write(corpus.resolve(".DS_Store"), new byte[]{0,1,2});
        Files.createDirectory(corpus.resolve("nested.txt"));
        indexer.parseWikis(corpus);
        assertEquals(2, count());
    }

    @Test void missingCorpusAndIndexHaveClearFailures() throws Exception {
        assertThrows(IOException.class, () -> indexer.parseWikis(root.resolve("missing")));
        assertThrows(IOException.class, () -> new QueryEngine(1, 1, indexer));
        assertFalse(Files.exists(Paths.get(indexer.getIndexPath())));
    }

    @Test void searchTreatsPunctuationAsTextAndDoesNotAlterIndex() throws Exception {
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            assertFalse(engine.search("alpha: (astronomy) + [first]", 10).isEmpty());
            assertEquals("alpha", engine.search("unknownfield: astronomy", 10).get(0).getDocName().get("title"));
            assertTrue(engine.search("zzzxxyyunknown", 10).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> engine.search(" ", 10));
            assertThrows(IllegalArgumentException.class, () -> engine.search("alpha", 0));
        }
        assertEquals(2, count());
    }

    @Test void aliasesAreCaseAndWhitespaceInsensitiveWithoutFuzzyGuessing() {
        assertTrue(Evaluation.accepts("The Salvation Army| Salvation Army", "SALVATION  ARMY"));
        assertFalse(Evaluation.accepts("Taiwan", "China"));
        assertFalse(Evaluation.accepts("Alpha|Beta", ""));
        assertFalse(Evaluation.accepts("Alpha|Beta", null));
    }

    @Test void evaluationIncludesIncorrectQuestionsAndAcceptsMissingFinalBlank() throws Exception {
        indexer.parseWikis(corpus);
        Path questions = root.resolve("questions.txt");
        Files.write(questions, Arrays.asList("SCIENCE", "alpha astronomy", "An alias|ALPHA", "",
                "UNKNOWN", "zzzxxyyunknown", "Not found"));
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            Evaluation report = Evaluation.evaluate(engine, questions, new PrintStream(new ByteArrayOutputStream()));
            assertEquals(2, report.getTotal());
            assertEquals(1, report.getCorrect());
            assertEquals(0.5, report.getPrecisionAt1());
            assertEquals(0.5, report.getRecallAt10());
            assertEquals(0.5, report.getMeanReciprocalRankAt10());
        }
    }

    @Test void reciprocalRankRecognizesAnAnswerBelowFirstPlace() throws Exception {
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            String second = engine.search("article", 10).get(1).getDocName().get("title");
            Path questions = root.resolve("rank.txt");
            Files.write(questions, Arrays.asList("ARTICLE", "article", second));
            Evaluation report = Evaluation.evaluate(engine, questions, new PrintStream(new ByteArrayOutputStream()));
            assertEquals(0.0, report.getPrecisionAt1());
            assertEquals(1.0, report.getRecallAt10());
            assertEquals(0.5, report.getMeanReciprocalRankAt10());
        }
    }

    @Test void malformedQuestionFilesAreRejectedBeforePartialResults() throws Exception {
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            for (List<String> lines : Arrays.asList(Collections.<String>emptyList(), Arrays.asList("CATEGORY", "Clue"),
                    Arrays.asList("CATEGORY", "Clue", "Answer|"),
                    Arrays.asList("CATEGORY", "Clue", "Answer", "Missing separator", "Clue", "Answer"))) {
                Path questions = root.resolve("invalid.txt");
                Files.write(questions, lines);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                assertThrows(IOException.class, () -> Evaluation.evaluate(engine, questions, new PrintStream(output)));
                assertEquals(0, output.size());
            }
        }
    }

    @Test void commandLineSearchAndEvaluateReuseTheSameIndex() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(output);
        String base = root.resolve("indexes").toString();
        assertEquals(0, Watson.run(new String[]{"index", corpus.toString(), base, "1"}, stream, stream));
        assertEquals(1, Watson.run(new String[]{"index", corpus.toString(), base, "1"}, stream, stream));
        assertEquals(0, Watson.run(new String[]{"index", corpus.toString(), base, "1", "--rebuild"}, stream, stream));
        assertEquals(0, Watson.run(new String[]{"search", base, "1", "1", "alpha astronomy"}, stream, stream));
        assertTrue(output.toString("UTF-8").contains("alpha"));
        assertEquals(2, count());
        assertEquals(1, Watson.run(new String[]{"search", base, "99", "1", "alpha"}, stream, stream));
        assertEquals(1, Watson.run(new String[]{"unknown"}, stream, stream));
    }

    @Test void titleDetectionRejectsShortNestedAndPipedBoundaries() {
        for (String invalid : Arrays.asList("[[", "[[ ]]", "[[]]", "[[[x]]]", "[[x|label]]", "[[x]] trailing", "[[x]")) {
            assertFalse(indexer.isTitle(invalid), invalid);
            assertThrows(IllegalArgumentException.class, () -> indexer.parseTitle(invalid));
        }
        assertTrue(indexer.isTitle("[[Bob Young (businessman)]]"));
    }

    @Test void structuredResponseHasReadableEvidenceAndSafeJson() throws Exception {
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            QueryResponse result = engine.ask("alpha \"astronomy\"\n", 10);
            assertEquals("Alpha", result.candidates.get(0).title);
            assertEquals(1, result.candidates.get(0).rank);
            assertTrue(result.candidates.get(0).score > 0);
            assertTrue(result.responseTimeMs >= 0);
            assertTrue(result.candidates.get(0).evidence.contains("First article"));
            assertEquals("source-excerpt", result.candidates.get(0).evidenceKind);
            assertEquals("pages.txt:2", result.candidates.get(0).source);
            try (jakarta.json.JsonReader reader = jakarta.json.Json.createReader(new java.io.StringReader(result.toJson().toString()))) {
                assertEquals(result.query, reader.readObject().getString("query"));
            }
            assertThrows(UnsupportedOperationException.class, () -> result.candidates.clear());
            assertTrue(engine.ask("zzzxxyyunknown", 10).candidates.isEmpty());
            assertThrows(IllegalArgumentException.class, () -> engine.search(String.join("", Collections.nCopies(4097, "a")), 10));
            assertThrows(IllegalArgumentException.class, () -> new QueryEngine(2, 1, indexer));
        }
    }

    @Test void answerKeyNeverEntersRetrievalAndJsonReportsProvenance() throws Exception {
        indexer.parseWikis(corpus);
        Path questions = root.resolve("leak.txt");
        Files.write(questions, Arrays.asList("ZZZ", "zzzxxyyunknown", "Alpha"));
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            Evaluation result = Evaluation.evaluate(engine, questions, new PrintStream(output), true);
            assertEquals(0, result.getFound());
            assertEquals(64, result.toJson().getString("questionsSha256").length());
            assertEquals(2, result.toJson().getJsonObject("index").getInt("documents"));
            assertEquals("cluelab-rebuilt", result.toJson().getJsonObject("index").getString("origin"));
        }
    }

    @Test void errorAfterIndexingOneFileRollsBackAndReleasesLock() throws Exception {
        indexer.parseWikis(corpus);
        Files.write(corpus.resolve("z-invalid.txt"), Arrays.asList("not an article"));
        assertThrows(IOException.class, () -> indexer.parseWikis(corpus, true));
        assertEquals(2, count());
        Files.delete(corpus.resolve("z-invalid.txt"));
        indexer.parseWikis(corpus, true);
        assertEquals(2, count());
    }

    @Test void invalidUtf8RebuildFailsWithoutPublishingPartialDocuments() throws Exception {
        indexer.parseWikis(corpus);
        Files.write(corpus.resolve("z-invalid.txt"), new byte[]{(byte) 0xc3, (byte) 0x28});
        assertThrows(IOException.class, () -> indexer.parseWikis(corpus, true));
        assertEquals(2, count());
    }

    @Test void malformedUtf8QuestionsAreRejected() throws Exception {
        indexer.parseWikis(corpus);
        Path questions = root.resolve("invalid-utf8.txt");
        Files.write(questions, new byte[]{(byte) 0xc3, (byte) 0x28});
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            assertThrows(IOException.class, () -> Evaluation.evaluate(engine, questions, System.out));
        }
    }

    @Test void answerAtRankElevenIsAMissAtTen() throws Exception {
        List<String> pages = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pages.add("[[Page " + i + "]]");
            pages.add("sharedword");
        }
        Files.write(corpus.resolve("pages.txt"), pages);
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 2, indexer)) {
            String eleventh = engine.search("sharedword", 12).get(10).getDocName().get("title");
            Path questions = root.resolve("cutoff.txt");
            Files.write(questions, Arrays.asList("SHAREDWORD", "sharedword", eleventh));
            Evaluation result = Evaluation.evaluate(engine, questions, new PrintStream(new ByteArrayOutputStream()));
            assertEquals(0, result.getFound());
            assertEquals(0.0, result.getMeanReciprocalRankAt10());
        }
    }

    @Test void legacyIndexEvidenceIsLabeledAndUnknownSchemasAreRejected() throws Exception {
        try (Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
             org.apache.lucene.index.IndexWriter writer = new org.apache.lucene.index.IndexWriter(directory,
                     new org.apache.lucene.index.IndexWriterConfig(indexer.getAnalyzer()))) {
            org.apache.lucene.document.Document doc = new org.apache.lucene.document.Document();
            doc.add(new org.apache.lucene.document.StringField("title", "legacy", org.apache.lucene.document.Field.Store.YES));
            doc.add(new org.apache.lucene.document.TextField("body", "run jump", org.apache.lucene.document.Field.Store.YES));
            writer.addDocument(doc);
        }
        try (QueryEngine engine = new QueryEngine(1, 1, indexer)) {
            QueryResponse response = engine.ask("jump", 10);
            assertEquals("processed-index-text", response.candidates.get(0).evidenceKind);
            assertEquals("legacy-unverified", response.index.getString("origin"));
        }
        try (Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
             org.apache.lucene.index.IndexWriter writer = new org.apache.lucene.index.IndexWriter(directory,
                     new org.apache.lucene.index.IndexWriterConfig(indexer.getAnalyzer()))) {
            writer.setLiveCommitData(Collections.singletonMap("cluelab.schema", "999").entrySet());
        }
        assertThrows(IOException.class, () -> new QueryEngine(1, 1, indexer));
    }

    @Test void allScorersRankAnUnambiguousMatchAndUseCompatibleLengthNorms() throws Exception {
        indexer.parseWikis(corpus);
        for (int scorer = 1; scorer <= 4; scorer++) {
            try (QueryEngine engine = new QueryEngine(1, scorer, indexer)) {
                Result hit = engine.search("astronomy", 10).get(0);
                assertEquals("alpha", hit.getDocName().get("title"));
                assertTrue(Double.isFinite(hit.getDocScore()) && hit.getDocScore() > 0);
            }
        }
        org.apache.lucene.index.FieldInvertState state = new org.apache.lucene.index.FieldInvertState(
                org.apache.lucene.util.Version.LATEST.major, "body", org.apache.lucene.index.IndexOptions.DOCS_AND_FREQS_AND_POSITIONS);
        state.setLength(100);
        assertEquals(new org.apache.lucene.search.similarities.BM25Similarity().computeNorm(state),
                new org.apache.lucene.search.similarities.ClassicSimilarity().computeNorm(state));
    }

    @Test void tfidfCanPreferShortPartialMatchWithoutBrokenNorms() throws Exception {
        Files.write(corpus.resolve("pages.txt"), Arrays.asList("[[Short]]", "needle", "[[Long]]",
                "needle haystack " + String.join(" ", Collections.nCopies(1000, "filler"))));
        indexer.parseWikis(corpus);
        try (QueryEngine engine = new QueryEngine(1, 3, indexer)) {
            assertEquals("short", engine.search("needle haystack", 10).get(0).getDocName().get("title"));
        }
    }
}
