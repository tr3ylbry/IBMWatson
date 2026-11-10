package main;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NumericDocValues;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.SmallFloat;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.queryparser.classic.QueryParser;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

/** Read-only investigation of a benchmark clue, not an alternate retrieval algorithm. */
public final class ScoringDiagnostics {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected index-base index-type questions-file");
        int type = Integer.parseInt(args[1]);
        List<String[]> questions = Evaluation.readQuestions(Files.readAllBytes(Paths.get(args[2])));
        String query = questions.get(0)[0] + " " + questions.get(0)[1];
        try (Indexer indexer = new Indexer(args[0], type);
             Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            System.out.println("First benchmark clue: " + query);
            for (LeafReaderContext leaf : reader.leaves()) {
                System.out.println("segment created major=" + leaf.reader().getMetaData().getCreatedVersionMajor());
            }
            for (int scorer : new int[]{1, 3, 4}) {
                try (QueryEngine engine = new QueryEngine(type, scorer, indexer)) {
                    System.out.println("SCORER=" + scorer);
                    for (Result hit : engine.search(query, 3)) {
                        int docId = hit.getDocumentId();
                        for (LeafReaderContext leaf : reader.leaves()) {
                            if (docId >= leaf.docBase && docId < leaf.docBase + leaf.reader().maxDoc()) {
                                NumericDocValues norms = leaf.reader().getNormValues("body");
                                norms.advanceExact(docId - leaf.docBase);
                                long norm = norms.longValue();
                                System.out.println("title=" + hit.getDocName().get("title") + " score=" + hit.getDocScore()
                                        + " norm=" + norm + " decodedLength=" + SmallFloat.byte4ToInt((byte) norm));
                            }
                        }
                        System.out.println(engine.explain(query, docId));
                    }
                }
            }
            // Controlled ablation only, never selected by the production CLI.
            IndexSearcher noLength = new IndexSearcher(reader);
            noLength.setSimilarity(new ClassicSimilarity() {
                @Override public float lengthNorm(int length) { return 1.0f; }
            });
            int correct = 0, found = 0, total = 0;
            double reciprocal = 0;
            try (QueryEngine processing = new QueryEngine(type, 3, indexer)) {
                for (String[] question : questions) {
                    String clue = processing.queryMethodinator(question[0] + " " + question[1]);
                    ScoreDoc[] hits = noLength.search(new QueryParser("body", indexer.getAnalyzer())
                            .parse(QueryParser.escape(clue)), 10).scoreDocs;
                    total++;
                    for (int rank = 0; rank < hits.length; rank++) {
                        if (Evaluation.accepts(question[2], reader.document(hits[rank].doc).get("title"))) {
                            if (rank == 0) correct++;
                            found++;
                            reciprocal += 1.0 / (rank + 1);
                            break;
                        }
                    }
                }
            }
            System.out.printf(java.util.Locale.ROOT,
                    "EXPLORATORY no-length-normalization total=%d correct=%d found10=%d mrr10=%.4f%n",
                    total, correct, found, reciprocal / total);
        }
    }
}
