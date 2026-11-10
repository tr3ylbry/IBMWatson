package main;

import org.apache.lucene.queryparser.classic.ParseException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;

/** Validates the complete answer key before evaluating any question. */
public final class Evaluation {
    private final int total;
    private final int correct;
    private final int found;
    private final double reciprocalRankSum;
    private JsonObject report;

    private Evaluation(int total, int correct, int found, double reciprocalRankSum) {
        this.total = total;
        this.correct = correct;
        this.found = found;
        this.reciprocalRankSum = reciprocalRankSum;
    }

    public int getTotal() { return total; }
    public int getCorrect() { return correct; }
    public int getFound() { return found; }
    public JsonObject toJson() { return report; }
    public double getPrecisionAt1() { return (double) correct / total; }
    public double getHitRateAt10() { return (double) found / total; }
    /** Historical name retained for callers; this is a per-question hit rate, not document recall. */
    public double getRecallAt10() { return getHitRateAt10(); }
    public double getMeanReciprocalRankAt10() { return reciprocalRankSum / total; }

    public static boolean accepts(String alternatives, String answer) {
        if (answer == null || normalize(answer).isEmpty()) return false;
        for (String accepted : alternatives.split("\\|", -1)) {
            if (normalize(accepted).equals(normalize(answer))) return true;
        }
        return false;
    }

    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    static List<String[]> readQuestions(byte[] bytes) throws IOException {
        String content = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        List<String> lines = java.util.Arrays.asList(content.split("\\R", -1));
        List<String[]> questions = new ArrayList<>();
        int line = 0;
        while (line < lines.size()) {
            if (lines.get(line).trim().isEmpty()) { line++; continue; }
            int start = line;
            if (line + 2 >= lines.size()) {
                throw new IOException("Incomplete question at line " + (start + 1));
            }
            String category = lines.get(line++).trim();
            String clue = lines.get(line++).trim();
            String answers = lines.get(line++).trim();
            if (clue.isEmpty() || answers.isEmpty() || clue.length() + category.length() + 1 > 4096) {
                throw new IOException("Invalid question at line " + (start + 1));
            }
            for (String answer : answers.split("\\|", -1)) {
                if (answer.trim().isEmpty()) throw new IOException("Empty answer alias at line " + line);
            }
            if (line < lines.size() && !lines.get(line).trim().isEmpty()) {
                throw new IOException("Expected a blank question separator at line " + (line + 1));
            }
            questions.add(new String[]{category, clue, answers});
        }
        if (questions.isEmpty()) throw new IOException("Question file is empty.");
        return questions;
    }

    public static Evaluation evaluate(QueryEngine engine, Path path, PrintStream output)
            throws IOException, ParseException {
        return evaluate(engine, path, output, false);
    }

    public static Evaluation evaluate(QueryEngine engine, Path path, PrintStream output, boolean json)
            throws IOException, ParseException {
        byte[] bytes = Files.readAllBytes(path);
        List<String[]> questions = readQuestions(bytes);
        JsonArrayBuilder predictions = Json.createArrayBuilder();
        int correct = 0, found = 0;
        double reciprocalRankSum = 0;
        for (int i = 0; i < questions.size(); i++) {
            String[] question = questions.get(i);
            // Only category and clue enter retrieval; the answer key is scoring-only.
            List<Result> hits = engine.search(question[0] + " " + question[1], 10);
            int rank = 0;
            for (int candidate = 0; candidate < hits.size(); candidate++) {
                if (accepts(question[2], hits.get(candidate).getDocName().get("title"))) {
                    rank = candidate + 1;
                    break;
                }
            }
            if (rank == 1) correct++;
            if (rank > 0) { found++; reciprocalRankSum += 1.0 / rank; }
            String predicted = hits.isEmpty() ? "<no result>" : hits.get(0).getDocName().get("title");
            predictions.add(Json.createObjectBuilder().add("question", i + 1).add("predicted", predicted)
                    .add("acceptedAnswers", question[2]).add("acceptedRank", rank));
            if (!json) output.printf(Locale.ROOT, "%d. predicted=%s; expected=%s; accepted rank=%d%n",
                        i + 1, predicted, question[2], rank);
        }
        Evaluation result = new Evaluation(questions.size(), correct, found, reciprocalRankSum);
        result.report = Json.createObjectBuilder().add("index", engine.describeIndex())
                .add("questionsPath", path.toAbsolutePath().toString()).add("questionsSha256", sha256(bytes))
                .add("total", result.total).add("correctFirst", correct).add("foundWithin10", found)
                .add("topOneAccuracy", result.getPrecisionAt1()).add("hitRateAt10", result.getRecallAt10())
                .add("mrrAt10", result.getMeanReciprocalRankAt10()).add("predictions", predictions).build();
        if (json) output.println(result.report);
        else {
            output.println("Index: " + engine.describeIndex());
            output.println("Questions SHA-256: " + sha256(bytes));
            output.printf(Locale.ROOT, "Questions: %d; correct first answers: %d%nTop-one accuracy (P@1): %.4f%nAccepted-answer hit rate@10: %.4f%nMRR@10: %.4f%n",
                    result.total, result.correct, result.getPrecisionAt1(), result.getHitRateAt10(),
                    result.getMeanReciprocalRankAt10());
        }
        return result;
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
