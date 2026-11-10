package main;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Paths;

/** Runs all twelve historical configurations against existing indexes, without writing them. */
public final class Benchmark {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected index-base questions-file");
        JsonArrayBuilder reports = Json.createArrayBuilder();
        for (int type = 1; type <= 3; type++) {
            for (int scorer = 1; scorer <= 4; scorer++) {
                try (Indexer indexer = new Indexer(args[0], type);
                     QueryEngine engine = new QueryEngine(type, scorer, indexer);
                     PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
                    Evaluation report = Evaluation.evaluate(engine, Paths.get(args[1]), sink, true);
                    reports.add(report.toJson());
                    System.err.printf(java.util.Locale.ROOT, "type=%d scorer=%d P@1=%.4f Hit@10=%.4f MRR@10=%.4f%n",
                            type, scorer, report.getPrecisionAt1(), report.getRecallAt10(), report.getMeanReciprocalRankAt10());
                }
            }
        }
        System.out.println(reports.build());
    }
}
