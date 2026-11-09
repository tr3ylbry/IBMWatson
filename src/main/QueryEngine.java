package main;

import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.BooleanSimilarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.tartarus.snowball.ext.PorterStemmer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.util.Comparator;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

/** Reads an existing index. Searching never builds or changes documents. */
public class QueryEngine implements AutoCloseable {
    private final int iType;
    private final Indexer indexer;
    private final Directory directory;
    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final int scorer;
    private final RetrievalProfile profile;

    public QueryEngine(int indexType, int queryType, Indexer indexer) throws IOException {
        this(indexType, queryType, indexer, RetrievalProfile.BASELINE);
    }

    public QueryEngine(int indexType, int queryType, Indexer indexer, RetrievalProfile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("A retrieval profile is required.");
        this.profile = profile;
        if (indexType < 1 || indexType > 3 || queryType < 1 || queryType > 4) {
            throw new IllegalArgumentException("Index type must be 1-3 and query type must be 1-4.");
        }
        this.iType = indexType;
        this.scorer = queryType;
        if (indexer.getIndexType() != indexType) throw new IllegalArgumentException("Index type mismatch.");
        this.indexer = indexer;
        if (!Files.isDirectory(Paths.get(indexer.getIndexPath()))) {
            throw new IOException("Index does not exist; run the index command first: " + indexer.getIndexPath());
        }
        directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
        try {
            reader = DirectoryReader.open(directory);
        } catch (org.apache.lucene.index.IndexFormatTooOldException error) {
            try { directory.close(); } catch (IOException closeError) { error.addSuppressed(closeError); }
            throw new IOException("Historical index format: use the isolated legacy-baseline CLI, or build a new modern index in a separate directory. The old index was not changed.", error);
        } catch (IOException | RuntimeException error) {
            try { directory.close(); }
            catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        try {
            Map<String, String> metadata = ((DirectoryReader) reader).getIndexCommit().getUserData();
            RuntimeInfo.requireIndexIdentity(metadata, indexType, true);
        } catch (IOException | RuntimeException error) {
            try { close(); }
            catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new BM25Similarity());
        if (queryType == 2) {
            searcher.setSimilarity(new BooleanSimilarity());
        } else if (queryType == 3) {
            searcher.setSimilarity(new ClassicSimilarity());
        } else if (queryType == 4) {
            searcher.setSimilarity(new LMJelinekMercerSimilarity(0.5f));
        }
    }

    public JsonObject describeIndex() throws IOException {
        Map<String, String> metadata = ((DirectoryReader) reader).getIndexCommit().getUserData();
        JsonObjectBuilder details = Json.createObjectBuilder();
        metadata.forEach(details::add);
        return Json.createObjectBuilder().add("path", Paths.get(indexer.getIndexPath()).toAbsolutePath().toString())
                .add("documents", reader.numDocs()).add("indexType", iType).add("scorer", scorer)
                .add("origin", metadata.containsKey("cluelab.schema") ? "cluelab-rebuilt" : "legacy-unverified")
                .add("commit", ((DirectoryReader) reader).getIndexCommit().getSegmentsFileName())
                .add("retrievalProfile", profile.id)
                .add("runtime", RuntimeInfo.json()).add("metadata", details).build();
    }

    public QueryResponse ask(String text, int limit) throws IOException, ParseException {
        long started = System.nanoTime();
        List<Result> hits = search(text, limit);
        List<QueryResponse.Candidate> candidates = new ArrayList<>();
        for (Result hit : hits) {
            Document doc = hit.getDocName();
            boolean original = doc.get("evidence") != null;
            String title = doc.get("displayTitle") == null ? doc.get("title") : doc.get("displayTitle");
            String evidence = original ? doc.get("evidence") : doc.get("body");
            // Archived indexes contain only processed text; never present that as a source quotation.
            String source = original ? doc.get("sourceFile") + ":" + doc.get("sourceLine") : "legacy stored body";
            candidates.add(new QueryResponse.Candidate(candidates.size() + 1, title, hit.getDocScore(),
                    excerpt(evidence, text), original ? "source-excerpt" : "processed-index-text", source));
        }
        return new QueryResponse(text, (System.nanoTime() - started) / 1_000_000.0, candidates, describeIndex());
    }

    static String excerpt(String body, String clue) {
        if (body == null) return "";
        if (body.length() <= 480) return body;
        int start = 0;
        String lower = body.toLowerCase(Locale.ROOT);
        for (String word : clue.toLowerCase(Locale.ROOT).split("\\W+")) {
            int match = word.length() >= 4 ? lower.indexOf(word) : -1;
            if (match >= 0) { start = Math.max(0, match - 100); break; }
        }
        int end = Math.min(body.length(), start + 480);
        return (start > 0 ? "..." : "") + body.substring(start, end) + (end < body.length() ? "..." : "");
    }

    /** Diagnostic only; the document ID is local to this reader's snapshot. */
    public String explain(String text, int documentId) throws IOException, ParseException {
        return searcher.explain(parseQuery(text), documentId).toString();
    }

    private Query parseQuery(String text) throws ParseException {
        String literal = QueryParser.escape(queryMethodinator(text));
        if (profile == RetrievalProfile.TITLE_CATEGORY) {
            return new MultiFieldQueryParser(new String[]{"body", "titleText", "category"}, indexer.getAnalyzer(),
                    Map.of("body", 1f, "titleText", 2f, "category", 1.5f)).parse(literal);
        }
        return new QueryParser("body", indexer.getAnalyzer()).parse(literal);
    }

    public List<Result> search(String text, int limit) throws IOException, ParseException {
        if (text == null || text.trim().isEmpty() || text.length() > 4096) {
            throw new IllegalArgumentException("A clue must contain 1-4096 characters.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Result limit must be 1-100.");
        }
        Query query = parseQuery(text);
        TopDocs docs = searcher.search(query, profile == RetrievalProfile.COVERAGE ? Math.max(30, limit) : limit);
        List<Result> results = new ArrayList<>();
        for (ScoreDoc hit : docs.scoreDocs) {
            Document document = searcher.storedFields().document(hit.doc);
            Result result = new Result();
            result.setDocumentId(hit.doc);
            result.setDocName(document);
            result.setDocScore(hit.score);
            results.add(result);
        }
        if (profile == RetrievalProfile.COVERAGE) {
            Set<String> terms = contentTerms(queryMethodinator(text));
            Map<Integer, Double> coverage = new java.util.HashMap<>();
            for (Result result : results) {
                Set<String> matched = contentTerms(result.getDocName().get("body"));
                matched.retainAll(terms);
                coverage.put(result.getDocumentId(), terms.isEmpty() ? 0 : (double) matched.size() / terms.size());
            }
            // Candidate text only: no accepted title, source support or evaluation label.
            results.sort(Comparator.<Result>comparingDouble(r -> coverage.get(r.getDocumentId())).reversed()
                    .thenComparing(Comparator.comparingDouble(Result::getDocScore).reversed())
                    .thenComparingInt(Result::getDocumentId));
        }
        return new ArrayList<>(results.subList(0, Math.min(limit, results.size())));
    }

    private static final Set<String> QUESTION_WORDS = new HashSet<>(Arrays.asList(
            "what", "which", "who", "name", "give", "term", "called", "named", "type", "whose", "does", "did", "its"));
    private Set<String> contentTerms(String text) throws IOException {
        Set<String> terms = new HashSet<>();
        try (var stream = indexer.getAnalyzer().tokenStream("body", text == null ? "" : text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class); stream.reset();
            while (stream.incrementToken()) {
                String value = term.toString();
                if (value.length() > 1 && value.chars().anyMatch(Character::isLetter)
                        && !EnglishAnalyzer.ENGLISH_STOP_WORDS_SET.contains(value) && !QUESTION_WORDS.contains(value)) terms.add(value);
            }
            stream.end();
        }
        return terms;
    }

    /** Compatibility entry point for the original school evaluator. */
    public void sherlockinator() {
        try {
            Evaluation.evaluate(this, Paths.get("questions.txt"), System.out);
        } catch (IOException | ParseException error) {
            throw new IllegalStateException("Evaluation failed: " + error.getMessage(), error);
        }
    }

    public String queryMethodinator(String query) {
        Sentence sentence = new Sentence(query.toLowerCase(Locale.ROOT));
        StringBuilder result = new StringBuilder();
        if (iType == 2) {
            for (String lemma : sentence.lemmas()) {
                result.append(lemma).append(' ');
            }
        } else if (iType == 3) {
            PorterStemmer stemmer = new PorterStemmer();
            for (String word : sentence.words()) {
                stemmer.setCurrent(word);
                stemmer.stem();
                result.append(stemmer.getCurrent()).append(' ');
            }
        } else {
            for (String word : sentence.words()) {
                result.append(word).append(' ');
            }
        }
        return result.toString();
    }

    @Override
    public void close() throws IOException {
        try {
            reader.close();
        } finally {
            directory.close();
        }
    }
}
