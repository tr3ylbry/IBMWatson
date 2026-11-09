package main;

import org.apache.lucene.queryparser.classic.ParseException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Scanner;

public class Watson {
    public static String dataPath = "src/resources/";

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    public static int run(String[] args, PrintStream output, PrintStream errors) {
        try {
            if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
                output.println("IBM Watson / ClueLab retrieval foundation");
                output.println("index <wiki-directory> <index-base> <index-type> [--rebuild]");
                output.println("evaluate <index-base> <index-type> <scorer> [questions-file] [--json]");
                output.println("search <index-base> <index-type> <scorer> <clue text...>");
                output.println("ask <index-base> <index-type> <scorer> <clue text...>   JSON response");
                output.println("interactive   Evaluate the default existing index using the original menus.");
                output.println("Index types: 1=default, 2=lemma, 3=stem. Scorers: 1=BM25, 2=Boolean, 3=TF-IDF, 4=Jelinek-Mercer.");
                output.println("Only index rebuilds files. Search and evaluate reuse existing indexes.");
                return 0;
            }
            if (args[0].equals("index") && (args.length == 4 || (args.length == 5 && args[4].equals("--rebuild")))) {
                try (Indexer indexer = new Indexer(args[2], Integer.parseInt(args[3]))) {
                    indexer.parseWikis(Paths.get(args[1]), args.length == 5);
                    output.println("Index rebuilt: " + indexer.getIndexPath());
                }
                return 0;
            }
            if (args[0].equals("interactive") && args.length == 1) {
                int[] options = menuOptions();
                try (Indexer indexer = new Indexer(dataPath, options[0]);
                     QueryEngine engine = new QueryEngine(options[0], options[1], indexer)) {
                    Evaluation.evaluate(engine, Paths.get("questions.txt"), output);
                }
                return 0;
            }
            boolean json = args[0].equals("evaluate") && args[args.length - 1].equals("--json");
            if (json) args = Arrays.copyOf(args, args.length - 1);
            boolean evaluate = args[0].equals("evaluate") && (args.length == 4 || args.length == 5);
            boolean search = (args[0].equals("search") || args[0].equals("ask")) && args.length >= 5;
            if (!evaluate && !search) throw new IllegalArgumentException("Invalid arguments. Run help for usage.");
            int indexType = Integer.parseInt(args[2]);
            int scorer = Integer.parseInt(args[3]);
            try (Indexer indexer = new Indexer(args[1], indexType);
                 QueryEngine engine = new QueryEngine(indexType, scorer, indexer)) {
                if (evaluate) {
                    Evaluation.evaluate(engine, Paths.get(args.length == 5 ? args[4] : "questions.txt"), output, json);
                } else {
                    String clue = String.join(" ", Arrays.copyOfRange(args, 4, args.length));
                    QueryResponse response = engine.ask(clue, 10);
                    if (args[0].equals("ask")) output.println(response.toJson());
                    else {
                        for (QueryResponse.Candidate result : response.candidates) {
                            output.printf(Locale.ROOT, "%d. %s (score %.4f)%n   %s%n   Source: %s [%s]%n", result.rank,
                                    result.title, result.score, result.evidence, result.source, result.evidenceKind);
                        }
                        if (response.candidates.isEmpty()) output.println("No matching articles.");
                        output.printf(Locale.ROOT, "Response time: %.3f ms%n", response.responseTimeMs);
                    }
                }
            }
            return 0;
        } catch (IOException | ParseException | RuntimeException error) {
            errors.println("Error: " + error.getMessage());
            return 1;
        }
    }

    public static int[] menuOptions() {
        Scanner input = new Scanner(System.in);
        System.out.println("Index type: 1=default, 2=lemma, 3=stem");
        if (!input.hasNextLine()) throw new IllegalArgumentException("Missing index type.");
        int index = Integer.parseInt(input.nextLine().trim());
        System.out.println("Scorer: 1=BM25, 2=Boolean, 3=TF-IDF, 4=Jelinek-Mercer");
        if (!input.hasNextLine()) throw new IllegalArgumentException("Missing scorer.");
        int scorer = Integer.parseInt(input.nextLine().trim());
        if (index < 1 || index > 3 || scorer < 1 || scorer > 4) {
            throw new IllegalArgumentException("Index type must be 1-3 and scorer must be 1-4.");
        }
        return new int[]{index, scorer};
    }
}
