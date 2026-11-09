package main;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.Version;

/** Compiled separately against each runtime. No codec mixing or application changes. */
public final class ModernizationProbe {
    static void emit(String... values) {
        System.out.println(String.join("\t", Arrays.stream(values)
                .map(v -> Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))).toArray(String[]::new)));
    }
    static String tokens(StandardAnalyzer analyzer, String value) throws Exception {
        List<String> tokens = new ArrayList<>();
        try (org.apache.lucene.analysis.TokenStream stream = analyzer.tokenStream("body", value)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset(); while (stream.incrementToken()) tokens.add(term.toString()); stream.end();
        }
        return String.join(" ", tokens);
    }
    static Document stored(IndexReader reader, int id) throws Exception {
        if (Version.LATEST.major < 9) return (Document) IndexReader.class.getMethod("document", int.class).invoke(reader, id);
        Object fields = IndexReader.class.getMethod("storedFields").invoke(reader);
        return (Document) Class.forName("org.apache.lucene.index.StoredFields").getMethod("document", int.class).invoke(fields, id);
    }
    static void rank(String mode, IndexReader reader, StandardAnalyzer analyzer, String processed) throws Exception {
        Query query = new QueryParser("body", analyzer).parse(QueryParser.escape(processed));
        emit("query", mode, query.toString());
        IndexSearcher searcher = new IndexSearcher(reader);
        searcher.setSimilarity(new LMJelinekMercerSimilarity(.5f));
        int rank = 0;
        for (ScoreDoc hit : searcher.search(query, 10).scoreDocs)
            emit("hit", mode, Integer.toString(++rank), stored(reader, hit.doc).get("title"),
                    Float.toString(hit.score), searcher.explain(query, hit.doc).toString());
    }
    static StandardAnalyzer analyzer(boolean stop) {
        return new StandardAnalyzer(stop ? EnglishAnalyzer.ENGLISH_STOP_WORDS_SET : CharArraySet.EMPTY_SET);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected native-index-base base64-query");
        String query = new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8);
        try (Indexer indexer = new Indexer(args[0], 1); QueryEngine engine = new QueryEngine(1, 4, indexer);
             Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            String processed = engine.queryMethodinator(query);
            emit("runtime", Version.LATEST.toString(), edu.stanford.nlp.simple.Sentence.class.getPackage().getImplementationVersion());
            emit("processed", processed);
            emit("stopwords", Integer.toString(indexer.getAnalyzer().getStopwordSet().size()));
            emit("analyzed", tokens(indexer.getAnalyzer(), processed));
            try (StandardAnalyzer empty = analyzer(false); StandardAnalyzer english = analyzer(true)) {
                emit("controlled-query-tokens", tokens(empty, processed), tokens(english, processed));
            }
            List<Document> source = new ArrayList<>();
            for (int i = 0; i < reader.maxDoc(); i++) {
                Document doc = stored(reader, i); source.add(doc);
                emit("document", Integer.toString(i), doc.get("title"), doc.get("category"), doc.get("body"), tokens(indexer.getAnalyzer(), doc.get("body")));
                try (StandardAnalyzer empty = analyzer(false); StandardAnalyzer english = analyzer(true)) {
                    emit("controlled-document-tokens", doc.get("title"), tokens(empty, doc.get("body")), tokens(english, doc.get("body")));
                }
                for (LeafReaderContext leaf : reader.leaves()) if (i >= leaf.docBase && i < leaf.docBase + leaf.reader().maxDoc()) {
                    NumericDocValues norms = leaf.reader().getNormValues("body"); norms.advanceExact(i - leaf.docBase);
                    emit("norm", doc.get("title"), Long.toString(norms.longValue()));
                }
            }
            rank("native", reader, indexer.getAnalyzer(), processed);
            for (boolean indexStop : new boolean[]{false, true}) {
                Path scratch = Files.createTempDirectory("cluelab-native-analysis-");
                try (Directory rebuilt = FSDirectory.open(scratch); StandardAnalyzer analysis = analyzer(indexStop)) {
                    try (IndexWriter writer = new IndexWriter(rebuilt, new IndexWriterConfig(analysis).setSimilarity(new BM25Similarity()))) {
                        for (Document input : source) {
                            Document doc = new Document();
                            doc.add(new StringField("title", input.get("title"), Field.Store.YES));
                            doc.add(new TextField("body", input.get("body"), Field.Store.YES));
                            writer.addDocument(doc);
                        }
                    }
                    try (DirectoryReader replay = DirectoryReader.open(rebuilt)) {
                        for (boolean queryStop : new boolean[]{false, true})
                            try (StandardAnalyzer analysisQuery = analyzer(queryStop)) {
                                rank("indexStop=" + indexStop + ",queryStop=" + queryStop, replay, analysisQuery, processed);
                            }
                    }
                } finally {
                    try (java.util.stream.Stream<Path> files = Files.walk(scratch)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) Files.delete(file);
                    }
                }
            }
        }
    }
}
