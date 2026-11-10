package main;

import jakarta.json.*;
import java.io.IOException;
import java.util.*;
import static main.PackCatalog.*;

/** Explicit private content pins. Recovery does not resolve today's catalog or source files. */
final class GameContent {
    private GameContent() {}
    static String key(Pack pack) { return pack.id + "@" + pack.version + "#" + pack.digest + "/" + pack.collection.scope.digest; }
    static String key(PackCatalog.Collection collection) { return collection.scope.key() + "#" + collection.scope.digest; }

    static JsonObject write(PackCatalog.Collection collection) {
        JsonArrayBuilder sources = Json.createArrayBuilder();
        for (Source s : collection.sources.values()) {
            JsonObjectBuilder row = Json.createObjectBuilder().add("id", s.id).add("title", s.title).add("category", s.category)
                    .add("url", s.url).add("referenceTitle", s.referenceTitle).add("publisher", s.publisher)
                    .add("attribution", s.attribution).add("snapshotKind", s.snapshotKind).add("snapshotSha256", s.snapshotSha256)
                    .add("capturedOn", s.capturedOn).add("revisionNote", s.revisionNote).add("body", s.text);
            if (s.revisionId == null) row.addNull("revisionId"); else row.add("revisionId", s.revisionId);
            sources.add(row);
        }
        return Json.createObjectBuilder().add("id", collection.scope.id).add("version", collection.scope.version)
                .add("digest", collection.scope.digest).add("description", collection.description).add("language", collection.language)
                .add("sources", sources).build();
    }
    static JsonObject write(Pack pack) {
        JsonArrayBuilder clues = Json.createArrayBuilder();
        for (Clue c : pack.clues) clues.add(Json.createObjectBuilder().add("id", c.id).add("topicId", c.topicId)
                .add("subtopicId", c.subtopicId).add("category", c.category).add("text", c.text).add("hint", c.hint.isEmpty() ? JsonValue.NULL : Json.createValue(c.hint))
                .add("difficulty", c.difficulty).add("answerType", c.answerType).add("answerSourceId", c.answerSourceId)
                .add("aliases", Json.createArrayBuilder(c.aliases)).add("support", Json.createObjectBuilder()
                        .add("sourceId", c.supportingSource.id).add("start", c.passageStart).add("end", c.passageEnd)));
        return Json.createObjectBuilder().add("id", pack.id).add("version", pack.version).add("digest", pack.digest)
                .add("topicId", pack.topicId).add("label", pack.label).add("description", pack.description).add("language", pack.language)
                .add("collection", key(pack.collection)).add("review", pack.review).add("clues", clues).build();
    }
    static PackCatalog.Collection collection(JsonObject row) throws IOException {
        keys(row, "saved collection", "id version digest description language sources");
        Map<String, Source> sources = new LinkedHashMap<>(); Set<String> titles = new HashSet<>();
        require(array(row, "sources").size() <= 4096, "Saved collection exceeds 4096 sources");
        for (JsonValue value : array(row, "sources")) {
            JsonObject s = object(value, "saved source"); String body = s.getString("body");
            require(!body.isBlank() && body.length() <= 256 * 1024 && body.indexOf('\0') < 0, "Invalid saved source body");
            Source source = new Source(Json.createObjectBuilder(s).remove("body").build(), body);
            require(source.snapshotSha256.equals(sha256(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))), "Saved source fingerprint mismatch");
            require(sources.put(source.id, source) == null && titles.add(Evaluation.normalize(source.title)), "Duplicate saved source/title");
        }
        require(!sources.isEmpty(), "Empty saved collection");
        return new PackCatalog.Collection(IntakeFiles.slug(row, "id"), IntakeFiles.version(row), digest(row),
                string(row, "description", 2000), string(row, "language", 30), sources);
    }
    static Pack pack(JsonObject row, Map<String, PackCatalog.Collection> collections) throws IOException {
        keys(row, "saved pack", "id version digest topicId label description language collection review clues");
        PackCatalog.Collection collection = collections.get(row.getString("collection")); require(collection != null, "Saved pack has missing collection");
        Set<String> subtopics = new HashSet<>(), difficulties = new HashSet<>(), ids = new HashSet<>();
        JsonArray clues = array(row, "clues"); require(!clues.isEmpty() && clues.size() <= 4096, "Invalid saved clue count");
        for (JsonValue value : clues) {
            JsonObject c = object(value, "saved clue"); subtopics.add(c.getString("subtopicId")); difficulties.add(c.getString("difficulty"));
        }
        List<Clue> loaded = new ArrayList<>(); String topic = IntakeFiles.slug(row, "topicId");
        for (JsonValue value : clues) {
            Clue clue = new Clue(object(value, "saved clue"), topic, subtopics, difficulties, collection);
            require(ids.add(clue.id), "Duplicate saved clue ID"); loaded.add(clue);
        }
        Pack pack = new Pack(IntakeFiles.slug(row, "id"), IntakeFiles.version(row), topic, string(row, "label", 100),
                string(row, "description", 2000), string(row, "language", 30), digest(row), collection, loaded, object(row, "review"));
        require(pack.reviewed, "Saved game references an ineligible draft"); return pack;
    }
    private static String digest(JsonObject row) throws IOException {
        String digest = string(row, "digest", 64); require(digest.matches("[a-f0-9]{64}"), "Invalid saved content digest"); return digest;
    }
}
