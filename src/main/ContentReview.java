package main;

import jakarta.json.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static main.PackCatalog.*;

/** Explicit local review evidence, separate from structural checks and from human approval. */
final class ContentReview {
    record Candidate(Map<String, byte[]> files, JsonObject catalog, JsonObject review, String topicId, String digest) {}
    private ContentReview() {}

    static Candidate candidate(Path draft, JsonObject decision) throws IOException {
        PackCatalog catalog = DraftExporter.validate(draft);
        require(catalog.topics.size() == 1 && catalog.collections.size() == 1, "Review one draft pack/collection at a time");
        IntakeFiles.schema(decision, "id kind status reviewer reviewedOn draftManifestSha256 candidateVersion limitations humanReviewer humanReviewedOn clues sourceAdvisories");
        IntakeFiles.slug(decision, "id");
        require(Set.of("assistant-source-editorial", "operator-source-editorial").contains(string(decision, "kind", 100)), "Unsupported review kind; human approval is outside this workflow");
        require("source-checked".equals(string(decision, "status", 100)), "Only explicit source-checked local candidates are supported, never human-reviewed");
        String reviewer = string(decision, "reviewer", 200), date = string(decision, "reviewedOn", 10);
        try { LocalDate.parse(date); } catch (RuntimeException e) { throw new IOException("reviewedOn must be an actual YYYY-MM-DD review date", e); }
        require(decision.containsKey("humanReviewer") && decision.isNull("humanReviewer") && decision.containsKey("humanReviewedOn") && decision.isNull("humanReviewedOn"), "Do not assert human approval in a local assistant/operator decision");
        String limitations = string(decision, "limitations", 3000);
        require(sha256(IntakeFiles.read(draft.resolve("manifest.json"), 4 * 1024 * 1024)).equals(string(decision, "draftManifestSha256", 64)), "Review refers to a changed/different draft manifest");
        String version = string(decision, "candidateVersion", 40); require(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "Invalid candidateVersion");
        PackCatalog.Pack pack = catalog.topics.values().iterator().next();
        require(newer(version, pack.version), "Reviewed candidate needs a new version; never change frozen draft review status in place");
        JsonObject screening = IntakeFiles.read(draft.resolve("private/review.json"));
        Map<String, JsonObject> decisions = new LinkedHashMap<>();
        for (JsonValue value : array(decision, "clues")) {
            JsonObject row = object(value, "reviewed clue"); keys(row, "reviewed clue", "clueId checks flagDecisions blockers advisories");
            require(decisions.putIfAbsent(string(row, "clueId", 100), row) == null, "Duplicate reviewed clue ID");
            require(array(row, "blockers").isEmpty(), row.getString("clueId") + ": unresolved blocking review flags");
            JsonObject checks = object(row, "checks");
            keys(checks, "review checks", "factualSupport wording aliases hint distinctFact attribution difficulty");
            for (String key : List.of("factualSupport", "wording", "aliases", "hint", "distinctFact", "attribution", "difficulty")) string(checks, key, 3000);
            for (JsonValue advisory : array(row, "advisories")) require(advisory instanceof JsonString && validText(((JsonString) advisory).getString(), 3000), "Invalid review advisory");
        }
        require(decisions.size() == pack.clues.size(), "Review must cover every clue exactly once");
        for (JsonValue value : array(screening, "clues")) {
            JsonObject row = object(value, "screening clue"), resolved = decisions.get(row.getString("clueId"));
            require(resolved != null, "Missing review for " + row.getString("clueId"));
            Set<String> flags = new HashSet<>(); for (JsonValue flag : array(row, "flags")) flags.add(((JsonString) flag).getString());
            Set<String> handled = new HashSet<>();
            for (JsonValue entry : array(resolved, "flagDecisions")) {
                JsonObject adjudication = object(entry, "flag decision"); keys(adjudication, "flag decision", "flag disposition rationale");
                String flag = string(adjudication, "flag", 3000), disposition = string(adjudication, "disposition", 100);
                require(flags.contains(flag) && handled.add(flag), "Unknown/duplicate screening flag decision: " + flag);
                require(Set.of("resolved", "advisory").contains(disposition), "Unresolved blocking flag: " + flag);
                string(adjudication, "rationale", 3000);
            }
            require(flags.equals(handled), row.getString("clueId") + ": every screening flag needs an explicit decision");
        }
        Map<String, byte[]> files = new TreeMap<>();
        JsonObject collection = IntakeFiles.read(draft.resolve("collection.json")); JsonArrayBuilder sources = Json.createArrayBuilder();
        Map<String, String> sourceAdvisories = new HashMap<>();
        if (decision.containsKey("sourceAdvisories")) for (JsonValue value : array(decision, "sourceAdvisories")) {
            JsonObject advisory = object(value, "source advisory"); keys(advisory, "source advisory", "sourceId note referenceUrl");
            String sourceId = string(advisory, "sourceId", 100), note = string(advisory, "note", 400);
            String reference = string(advisory, "referenceUrl", 300);
            try {
                URI uri = new URI(reference);
                require("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null,
                        "source advisory referenceUrl: expected credential-free HTTPS reference");
            } catch (java.net.URISyntaxException error) { throw new IOException("source advisory referenceUrl: malformed URL", error); }
            require(sourceAdvisories.putIfAbsent(sourceId, " Editorial advisory (" + date + "; " + decision.getString("kind") + "): "
                    + note + " Context reference: " + reference) == null, "Duplicate source advisory: " + sourceId);
        }
        for (JsonValue value : array(collection, "sources")) {
            JsonObject source = object(value, "source"); byte[] bytes = IntakeFiles.read(draft.resolve(source.getString("snapshot")), 256 * 1024);
            String path = "snapshots/" + sha256(bytes) + ".txt";
            // Advisory metadata is versioned separately from the preserved source quotation.
            String advisory = sourceAdvisories.remove(source.getString("id"));
            if (advisory != null) {
                String note = source.getString("revisionNote") + advisory;
                require(validText(note, 1000), "Source revisionNote plus advisory exceeds 1000 characters");
                source = Json.createObjectBuilder(source).add("revisionNote", note).build();
            }
            files.put(path, bytes); sources.add(Json.createObjectBuilder(source).add("snapshot", path));
        }
        require(sourceAdvisories.isEmpty(), "Source advisory refers to unavailable source IDs: " + sourceAdvisories.keySet());
        collection = Json.createObjectBuilder(collection).add("version", version).add("sources", sources).build();
        JsonObject sourceChecked = Json.createObjectBuilder(IntakeFiles.read(draft.resolve("private/pack.json")))
                .add("version", version).add("collectionVersion", version)
                .add("review", Json.createObjectBuilder().add("status", "source-checked").add("reviewer", reviewer).add("reviewedOn", date)
                        .add("factualNotes", "Explicit " + decision.getString("kind") + " decisions pinned in private/review-decision.json; not independent human approval.")
                        .add("editorialNotes", "Every screening flag adjudicated; source context, wording, aliases, hints, distinct facts and attribution checked. Difficulty remains provisional.")
                        .add("limitations", limitations)).build();
        JsonObject candidateCatalog = Json.createObjectBuilder(IntakeFiles.read(draft.resolve("catalog.json"))).add("version", version)
                .add("collections", Json.createArrayBuilder().add(reference(collection.getString("id"), version, "collection.json")))
                .add("packs", Json.createArrayBuilder().add(reference(pack.id, version, "private/pack.json")))
                .add("topics", Json.createArrayBuilder().add(Json.createObjectBuilder().add("id", pack.topicId).add("packId", pack.id).add("packVersion", version))).build();
        files.put("collection.json", IntakeFiles.json(collection)); files.put("private/pack.json", IntakeFiles.json(sourceChecked));
        files.put("private/review-decision.json", IntakeFiles.json(decision)); files.put("catalog.json", IntakeFiles.json(candidateCatalog));
        String digest = sha256(IntakeFiles.json(hashes(files)));
        return new Candidate(Collections.unmodifiableMap(files), candidateCatalog, decision, pack.topicId, digest);
    }
    static JsonObject hashes(Map<String, byte[]> files) {
        JsonObjectBuilder out = Json.createObjectBuilder(); new TreeMap<>(files).forEach((name, bytes) -> out.add(name, sha256(bytes))); return out.build();
    }
    static JsonObject reference(String id, String version, String path) { return Json.createObjectBuilder().add("id", id).add("version", version).add("path", path).build(); }
    static boolean newer(String next, String previous) {
        String[] a = next.split("\\."), b = previous.split("\\.");
        for (int i = 0; i < 3; i++) { int c = new java.math.BigInteger(a[i]).compareTo(new java.math.BigInteger(b[i])); if (c != 0) return c > 0; } return false;
    }
    static void preview(Path before, Path after, Path review, Path output) throws IOException {
        PackCatalog old = DraftExporter.validate(before), revised = DraftExporter.validate(after); JsonArrayBuilder blockers = Json.createArrayBuilder();
        Candidate candidate = null;
        try { candidate = candidate(after, IntakeFiles.read(review)); }
        catch (IOException error) { blockers.add(error.getMessage()); }
        PackCatalog.Pack previous = old.topics.values().iterator().next(), next = revised.topics.values().iterator().next();
        JsonArrayBuilder rows = Json.createArrayBuilder(); Map<String, PackCatalog.Clue> originals = new HashMap<>(); previous.clues.forEach(c -> originals.put(c.id, c));
        for (PackCatalog.Clue clue : next.clues) {
            JsonObjectBuilder row = Json.createObjectBuilder().add("clueId", clue.id).add("after", view(clue));
            if (originals.containsKey(clue.id)) row.add("before", view(originals.get(clue.id))); else row.addNull("before"); rows.add(row);
        }
        JsonObjectBuilder proposed = Json.createObjectBuilder(), available = Json.createObjectBuilder(); boolean five = false;
        for (String level : revised.difficulties.keySet()) {
            int count = (int) next.clues.stream().filter(c -> c.difficulty.equals(level)).count(); proposed.add(level, count); available.add(level, candidate == null ? 0 : count); five |= count >= 5;
        }
        if (!five) blockers.add("Insufficient coverage: no difficulty has five clues; no padding or fallback");
        JsonObject json = Json.createObjectBuilder().add("schemaVersion", 1).add("privateAdminPreview", true).add("beforeVersion", previous.version)
                .add("draftVersion", next.version).add("candidateVersion", candidate == null ? "unavailable" : candidate.review.getString("candidateVersion"))
                .add("draftEligible", 0).add("proposedCounts", proposed).add("reviewedCandidateCounts", available).add("fiveClueSelectionPossible", five && candidate != null)
                .add("blockingFailures", blockers).add("advisoryLimitations", candidate == null ? "Review incomplete" : candidate.review.getString("limitations"))
                .add("beforeManifestSha256", sha256(IntakeFiles.read(before.resolve("manifest.json"), 4 * 1024 * 1024)))
                .add("afterManifestSha256", sha256(IntakeFiles.read(after.resolve("manifest.json"), 4 * 1024 * 1024)))
                .add("reviewDecision", IntakeFiles.read(review)).add("clues", rows).build();
        Map<String, byte[]> files = new TreeMap<>(); files.put("preview.json", IntakeFiles.json(json));
        String html = "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Private ClueLab Review Preview</title>"
                + "<style>body{font:1rem Georgia,serif;max-width:80rem;margin:auto;padding:2rem;background:#f8f5ed;color:#17272b}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:.9rem monospace}h1{font-size:2rem}</style>"
                + "<h1>Private Review Preview</h1><p>Answer-bearing administrator material. Draft revision, assistant source review and activation are separate; no human approval or calibrated difficulty.</p><pre>"
                + escape(pretty(json)) + "</pre></html>";
        files.put("preview.html", html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (candidate != null) candidate.files.forEach((name, bytes) -> files.put("candidate/" + name, bytes));
        IntakeFiles.publish(output, files, new WikiApi.Budget(null));
        System.out.println("Private answer-bearing review preview: " + output + "; five-clue selection possible: " + json.getBoolean("fiveClueSelectionPossible")
                + "; blocking failures: " + json.getJsonArray("blockingFailures"));
        require(json.getJsonArray("blockingFailures").isEmpty(), "Review preview recorded blocking failures; activation is unavailable");
    }
    private static JsonObject view(PackCatalog.Clue clue) {
        return Json.createObjectBuilder().add("wording", clue.text).add("hint", clue.hint).add("aliases", Json.createArrayBuilder(clue.aliases))
                .add("difficulty", clue.difficulty).add("supportStart", clue.passageStart).add("supportEnd", clue.passageEnd)
                .add("sourceEvidence", clue.supportingSource.evidence(clue.supportingPassage(), "curated-answer-support")).build();
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String pretty(JsonObject json) {
        java.io.StringWriter out = new java.io.StringWriter();
        try (JsonWriter writer = Json.createWriterFactory(Map.of("jakarta.json.stream.JsonGenerator.prettyPrinting", true)).createWriter(out)) { writer.writeObject(json); } return out.toString();
    }
}
