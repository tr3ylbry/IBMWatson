package main;

import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
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
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

/** Reads an existing index. Searching never builds or changes documents. */
public class QueryEngine implements AutoCloseable {
    private final int iType;
    private final Indexer indexer;
    private final Directory directory;
    private final IndexReader reader;
    private final IndexSearcher searcher;
    private final int scorer;

    public QueryEngine(int indexType, int queryType, Indexer indexer) throws IOException {
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
        } catch (IOException | RuntimeException error) {
            try { directory.close(); }
            catch (IOException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
        try {
            Map<String, String> metadata = ((DirectoryReader) reader).getIndexCommit().getUserData();
            if (metadata.containsKey("cluelab.schema") && !"1".equals(metadata.get("cluelab.schema"))) {
                throw new IOException("Unsupported ClueLab index schema: " + metadata.get("cluelab.schema"));
            }
            if (metadata.containsKey("indexType") && !Integer.toString(indexType).equals(metadata.get("indexType"))) {
                throw new IOException("Index processing metadata does not match the requested index type.");
            }
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
                .add("metadata", details).build();
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
        return new QueryParser("body", indexer.getAnalyzer()).parse(QueryParser.escape(queryMethodinator(text)));
    }

    public List<Result> search(String text, int limit) throws IOException, ParseException {
        if (text == null || text.trim().isEmpty() || text.length() > 4096) {
            throw new IllegalArgumentException("A clue must contain 1-4096 characters.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Result limit must be 1-100.");
        }
        Query query = parseQuery(text);
        TopDocs docs = searcher.search(query, limit);
        List<Result> results = new ArrayList<>();
        for (ScoreDoc hit : docs.scoreDocs) {
            Document document = searcher.doc(hit.doc);
            Result result = new Result();
            result.setDocumentId(hit.doc);
            result.setDocName(document);
            result.setDocScore(hit.score);
            results.add(result);
        }
        return results;
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
