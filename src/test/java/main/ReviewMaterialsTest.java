package main;

import org.junit.jupiter.api.Test;
import jakarta.json.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReviewMaterialsTest {
    private JsonArray ledger() throws Exception {
        JsonObject packet = PackCatalog.read(Paths.get("docs/review/starter-ledger.json"));
        assertEquals(1, packet.getInt("schemaVersion"));
        assertEquals("cluelab-starter-review-ledger", packet.getString("recordType"));
        return packet.getJsonArray("records");
    }

    @Test void ledgerPinsEveryStarterClueAndItsExactPrivateReviewMaterial() throws Exception {
        PackCatalog catalog = PackCatalog.load(Paths.get("data/packs/catalog.json"));
        Set<String> expected = new HashSet<>(), seen = new HashSet<>();
        catalog.topics.values().stream().filter(pack -> !pack.topicId.equals("coursework-demo"))
                .forEach(pack -> pack.clues.forEach(clue -> expected.add(clue.id)));
        for (JsonValue value : ledger()) {
            JsonObject row = (JsonObject) value;
            PackCatalog.Pack pack = catalog.topics.get(row.getString("topicId"));
            assertNotNull(pack); assertTrue(seen.add(row.getString("clueId")));
            PackCatalog.Clue clue = pack.clues.stream().filter(c -> c.id.equals(row.getString("clueId"))).findFirst().get();
            assertEquals(pack.id, row.getString("packId"));
            assertEquals(pack.version, row.getString("packVersion"));
            assertEquals(pack.digest, row.getString("packSha256"));
            assertEquals(pack.collection.scope.id, row.getString("collectionId"));
            assertEquals(pack.collection.scope.version, row.getString("collectionVersion"));
            assertEquals(clue.difficulty, row.getString("difficulty"));
            assertEquals(clue.subtopicId, row.getString("subtopicId"));
            assertEquals(clue.category, row.getString("category"));
            assertEquals(clue.text, row.getString("clue"));
            assertEquals(clue.hint, row.getString("hint"));
            assertEquals(clue.answerType, row.getString("answerType"));
            assertEquals(clue.answerSourceId, row.getString("answerSourceId"));
            List<String> aliases = new ArrayList<>();
            row.getJsonArray("acceptedAliases").forEach(alias -> aliases.add(((JsonString) alias).getString()));
            assertEquals(clue.aliases, aliases);
            JsonObject support = row.getJsonObject("support");
            assertEquals(clue.supportingSource.id, support.getString("sourceId"));
            assertEquals(clue.passageStart, support.getInt("start"));
            assertEquals(clue.passageEnd, support.getInt("end"));
            assertEquals(clue.supportingPassage(), support.getString("passage"));
            JsonObject source = row.getJsonObject("source");
            JsonObject original = PackCatalog.read(Paths.get("data/packs/collections/" + pack.collection.scope.id + ".json"))
                    .getJsonArray("sources").getValuesAs(JsonObject.class).stream()
                    .filter(s -> s.getString("id").equals(clue.supportingSource.id)).findFirst().get();
            for (String key : original.keySet()) {
                if (!key.equals("snapshot")) assertEquals(original.get(key), source.get(key), clue.id + ": " + key);
            }
            assertEquals(clue.supportingSource.text, new String(Files.readAllBytes(
                    Paths.get("docs/review").resolve(source.getString("snapshot"))), StandardCharsets.UTF_8));
        }
        assertEquals(60, seen.size()); assertEquals(expected, seen);
    }

    @Test void preparationDoesNotInventHumanEvidenceOrHideSourceAccessLimits() throws Exception {
        int readable = 0, unavailable = 0;
        for (JsonValue value : ledger()) {
            JsonObject row = (JsonObject) value;
            JsonObject assistant = row.getJsonObject("assistantReview");
            assertEquals("Codex (AI assistant)", assistant.getString("reviewer"));
            assertFalse(assistant.getString("checkedOn").isEmpty());
            assertFalse(assistant.getString("locator").isEmpty());
            assertFalse(assistant.getString("findings").isEmpty());
            if (assistant.getString("access").equals("context-read")) readable++;
            else { assertEquals("context-unavailable", assistant.getString("access")); unavailable++; }
            row.getJsonObject("humanReview").forEach((key, entry) -> {
                if (key.equals("evidenceRefs")) assertEquals(Json.createArrayBuilder().build(), entry);
                else { assertEquals(JsonValue.ValueType.STRING, entry.getValueType()); assertEquals("", ((JsonString) entry).getString()); }
            });
            assertTrue(row.getJsonArray("playtestObservationRefs").isEmpty());
            assertTrue(row.getJsonArray("calibrationEvidenceRefs").isEmpty());
        }
        assertEquals(49, readable); assertEquals(11, unavailable);
    }

    @Test void pilotPlansSixGamesButLeavesActualPeopleDatesAndResultsEmpty() throws Exception {
        List<String> pilot = Files.readAllLines(Paths.get("docs/review/six-game-pilot.csv"), StandardCharsets.UTF_8);
        assertEquals(7, pilot.size());
        List<String> topics = Arrays.asList("general-knowledge", "world-history", "geography", "music", "", "");
        for (int i = 0; i < 6; i++) {
            String[] cells = pilot.get(i + 1).split(",", -1);
            assertEquals(15, cells.length);
            assertEquals(Integer.toString(i + 1), cells[0]); assertEquals(topics.get(i), cells[1]);
            assertEquals(i < 4 ? "standard" : i == 4 ? "easy" : "hard", cells[2]);
            for (int field = 3; field < cells.length; field++) assertEquals("", cells[field]);
        }
        List<String> observations = Files.readAllLines(Paths.get("docs/review/observations.csv"), StandardCharsets.UTF_8);
        assertEquals(1, observations.size());
        for (String field : Arrays.asList("actual_date", "participant_pseudonym", "reviewer_pseudonym", "topic_familiarity",
                "clue_id", "pack_version", "collection_version", "hint_opened", "perceived_difficulty_before_reveal",
                "missing_alias", "factual_concern", "editorial_or_ambiguity_concern", "usability_feedback")) {
            assertTrue(Arrays.asList(observations.get(0).split(",")).contains(field));
        }
    }
}
