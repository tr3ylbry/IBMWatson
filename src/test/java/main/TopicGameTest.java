package main;

import org.junit.jupiter.api.*;
import jakarta.json.*;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TopicGameTest {
    private PackCatalog catalog;
    private GameService game;
    private String owner;
    @BeforeEach void setup() throws Exception { catalog = PackCatalog.load(Paths.get("data/packs/catalog.json")); }
    @AfterEach void cleanup() { if (game != null) game.close(); }
    private void open(GameService.ScopedComputer computer) {
        game = new GameService(catalog, computer); owner = game.openSession(null);
    }
    private JsonObject start(String topic, String difficulty, boolean replay) {
        return game.start(owner, topic, difficulty, "1.0.0", replay);
    }
    private PackCatalog.Clue clue(JsonObject round) {
        return catalog.topics.get(round.getString("topicId")).clues.stream()
                .filter(c -> c.text.equals(round.getString("clue"))).findFirst().get();
    }
    private JsonObject finish(JsonObject round, String answer) throws Exception {
        GameServiceTest.ready(game, owner, round.getString("id"));
        return game.submit(owner, round.getString("id"), answer);
    }
    private JsonObject availability(String topic, String level) {
        for (JsonValue value : game.current(owner).getJsonObject("catalog").getJsonArray("topics")) {
            JsonObject item = (JsonObject) value;
            if (item.getString("id").equals(topic)) for (JsonValue choice : item.getJsonArray("difficulties")) {
                if (((JsonObject) choice).getString("id").equals(level)) return (JsonObject) choice;
            }
        }
        throw new AssertionError("Missing catalog selection");
    }

    @Test void catalogOnlyAdvertisesSafeMetadataAndAccurateAvailability() {
        open((scope, category, clue) -> GameServiceTest.response(null));
        String json = game.current(owner).toString();
        for (String secret : Arrays.asList("answerSourceId", "aliases", "support", "clueId", "snapshot", "factualNotes", "reviewer", "Sun", "Satchmo")) assertFalse(json.contains(secret), secret);
        assertEquals(5, availability("music", "hard").getInt("eligible"));
        assertEquals(0, availability("coursework-demo", "hard").getInt("eligible"));
    }
    @Test void activeConfigurationIsPinnedAndLostStartDoesNotConsumeAnotherClue() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        open((scope, category, clue) -> { gate.await(); return GameServiceTest.response(null); });
        JsonObject round = start("music", "easy", false);
        assertEquals(4, availability("music", "easy").getInt("remaining"));
        assertEquals(round, start("music", "easy", false));
        assertEquals(round, game.current(owner).getJsonObject("round"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start("geography", "easy", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start("music", "hard", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> game.start(owner, "music", "easy", "2.0.0", false)).status);
        assertEquals("music-sources", round.getString("collectionId"));
        assertEquals("1.0.0", round.getString("packVersion"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> game.submit(owner, round.getString("id"), "guess")).status);
        gate.countDown();
    }
    @Test void invalidAndUnavailableChoicesNeverFallbackOrCallRetrieval() {
        AtomicInteger calls = new AtomicInteger();
        open((scope, category, clue) -> { calls.incrementAndGet(); return GameServiceTest.response(null); });
        assertEquals(400, assertThrows(GameService.GameError.class, () -> start("missing", "easy", false)).status);
        assertEquals(400, assertThrows(GameService.GameError.class, () -> start("music", "impossible", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start("coursework-demo", "hard", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start("music", "easy", true)).status);
        assertEquals(400, assertThrows(GameService.GameError.class, () -> game.start(owner, "easy")).status);
        assertTrue(game.current(owner).isNull("round")); assertEquals(0, calls.get());
    }
    @Test void noRepeatsUntilExplicitReplayAndCyclesAreIsolated() throws Exception {
        open((scope, category, clue) -> GameServiceTest.response(null));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            JsonObject round = start("world-history", "hard", false);
            JsonObject result = finish(round, "Wrong");
            assertTrue(seen.add(result.getString("clueId")));
            assertEquals(result, game.submit(owner, round.getString("id"), "Changed"));
        }
        assertTrue(availability("world-history", "hard").getBoolean("exhausted"));
        assertEquals(5, availability("world-history", "easy").getInt("remaining"));
        assertEquals(5, availability("music", "hard").getInt("remaining"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start("world-history", "hard", false)).status);
        JsonObject replay = start("world-history", "hard", true);
        // The same retry may observe THINKING or READY as its independent search completes.
        assertEquals(replay.getString("id"), start("world-history", "hard", true).getString("id"));
        assertEquals(2, availability("world-history", "hard").getInt("cycle"));
        assertEquals(4, availability("world-history", "hard").getInt("remaining"));
        assertTrue(seen.contains(finish(replay, "Wrong").getString("clueId")));
    }
    @Test void retrievalReceivesOnlyScopeCategoryClueAndPublicHint() throws Exception {
        List<String> inputs = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        open((scope, category, clue) -> {
            inputs.add(scope.key()); inputs.add(category); inputs.add(clue); calls.incrementAndGet();
            return GameServiceTest.response(null);
        });
        JsonObject round = start("coursework-demo", "easy", false); PackCatalog.Clue clue = clue(round);
        JsonObject result = finish(round, clue.aliases.get(0));
        assertEquals(Arrays.asList("coursework-demo-sources@1.0.0", clue.category, clue.retrievalText()), inputs);
        assertEquals(100, result.getInt("playerPoints"));
        assertEquals(result, game.submit(owner, round.getString("id"), "Changed"));
        assertEquals(1, calls.get());
        assertEquals(3, PackCatalog.Scope.class.getDeclaredFields().length);
    }
    @Test void preRevealAllowlistExcludesClueIdsAnswersAndAuthoringMetadata() throws Exception {
        open((scope, category, clue) -> GameServiceTest.response("Piano"));
        JsonObject round = start("music", "standard", false);
        Set<String> allowed = new HashSet<>(Arrays.asList("id", "number", "difficulty", "state", "category", "clue", "hint",
                "playerTotal", "computerTotal", "topicId", "topicLabel", "packId", "packVersion", "collectionId", "collectionVersion"));
        assertEquals(allowed, round.keySet());
        assertEquals(allowed, GameServiceTest.ready(game, owner, round.getString("id")).keySet());
        assertFalse(game.current(owner).toString().contains("Piano"));
    }
    @Test void curatedSupportAndActualRetrievedEvidenceStayDistinct() throws Exception {
        open((scope, category, clue) -> GameServiceTest.response("Piano"));
        JsonObject round = start("music", "standard", false);
        JsonObject result = finish(round, clue(round).aliases.get(0));
        assertEquals(100, result.getInt("playerPoints")); assertEquals(0, result.getInt("computerPoints"));
        assertEquals("Piano", result.getJsonObject("evidence").getString("title"));
        assertEquals("Readable evidence.", result.getJsonObject("evidence").getString("text"));
        assertEquals("opponent-retrieved", result.getJsonObject("evidence").getString("kind"));
        assertEquals("curated-answer-support", result.getJsonObject("curatedEvidence").getString("kind"));
        assertEquals(clue(round).supportingPassage(), result.getJsonObject("curatedEvidence").getString("text"));
        assertNotEquals(result.getJsonObject("evidence").getString("sourceId"), result.getJsonObject("curatedEvidence").getString("sourceId"));
    }
    @Test void sourceScopeEscapeAndEngineFailureGiveZeroWithoutBlockingPlayer() throws Exception {
        for (boolean escape : Arrays.asList(true, false)) {
            open((scope, category, clue) -> { if (!escape) throw new IOException("private path"); return GameServiceTest.response("Sun"); });
            JsonObject round = start("music", "standard", false);
            JsonObject result = finish(round, clue(round).aliases.get(0));
            assertEquals("unavailable", result.getString("computerStatus"));
            assertEquals(0, result.getInt("computerPoints")); assertEquals(100, result.getInt("playerPoints"));
            assertTrue(result.isNull("evidence")); assertNotNull(result.getJsonObject("curatedEvidence"));
            assertFalse(result.toString().contains("private path")); game.close();
        }
    }
    @Test void concurrentDuplicateSubmissionsKeepOneGuessAndOneScore() throws Exception {
        open((scope, category, clue) -> GameServiceTest.response(null));
        JsonObject round = start("geography", "standard", false);
        GameServiceTest.ready(game, owner, round.getString("id"));
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            String alias = clue(round).aliases.get(0).toUpperCase(Locale.ROOT);
            Future<JsonObject> first = clients.submit(() -> game.submit(owner, round.getString("id"), "  " + alias + "  "));
            Future<JsonObject> second = clients.submit(() -> game.submit(owner, round.getString("id"), "Wrong"));
            JsonObject receipt = first.get(); assertEquals(receipt, second.get());
            assertEquals(receipt.getInt("playerPoints"), receipt.getInt("playerTotal"));
            assertEquals(receipt, game.submit(owner, round.getString("id"), ""));
            assertEquals(receipt, game.current(owner).getJsonObject("round"));
        } finally { clients.shutdownNow(); }
    }
}
