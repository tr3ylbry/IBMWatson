package main;

import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Transport values only: no Lucene objects or evaluation answers cross this boundary. */
public final class QueryResponse {
    public static final class Candidate {
        public final int rank;
        public final String title;
        public final double score;
        public final String evidence;
        public final String evidenceKind;
        public final String source;

        Candidate(int rank, String title, double score, String evidence, String evidenceKind, String source) {
            this.rank = rank;
            this.title = title;
            this.score = score;
            this.evidence = evidence;
            this.evidenceKind = evidenceKind;
            this.source = source;
        }
    }

    public final String query;
    public final double responseTimeMs;
    public final List<Candidate> candidates;
    public final JsonObject index;

    QueryResponse(String query, double responseTimeMs, List<Candidate> candidates, JsonObject index) {
        this.query = query;
        this.responseTimeMs = responseTimeMs;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.index = index;
    }

    public JsonObject toJson() {
        JsonArrayBuilder results = Json.createArrayBuilder();
        for (Candidate candidate : candidates) {
            results.add(Json.createObjectBuilder().add("rank", candidate.rank).add("title", candidate.title)
                    .add("score", candidate.score).add("evidence", candidate.evidence)
                    .add("evidenceKind", candidate.evidenceKind).add("source", candidate.source));
        }
        return Json.createObjectBuilder().add("query", query).add("responseTimeMs", responseTimeMs)
                .add("index", index).add("candidates", results).build();
    }
}
