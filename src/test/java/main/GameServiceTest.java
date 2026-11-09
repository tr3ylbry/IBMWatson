package main;

import org.junit.jupiter.api.*;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class GameServiceTest {
    private Path questions;
    private GameService game;
    private String owner;

    @BeforeEach void setup() throws Exception {
        questions = Files.createTempFile("cluelab-game-", ".txt");
        Files.write(questions, Arrays.asList("SCIENCE", "A clue about a scientist.", "Private Answer| Secret Alias"));
    }

    @AfterEach void cleanup() throws Exception {
        if (game != null) game.close();
        Files.deleteIfExists(questions);
    }

    private void open(GameService.Computer computer) throws Exception {
        game = new GameService(questions, computer);
        owner = game.openSession(null);
    }

    static QueryResponse response(String title) {
        List<QueryResponse.Candidate> candidates = title == null ? Collections.emptyList() : Collections.singletonList(
                new QueryResponse.Candidate(1, title, 2.0, "Readable evidence.", "source-excerpt", "articles.txt:1"));
        return new QueryResponse("SCIENCE A clue about a scientist.", 5.0, candidates,
                Json.createObjectBuilder().add("path", "/private/index/path").build());
    }

    static JsonObject ready(GameService game, String owner, String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        JsonObject round;
        do {
            round = game.get(owner, id);
            if (!round.getString("state").equals("THINKING")) return round;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        fail("Round never became ready");
        return null;
    }

    @Test void correctAliasScoringUsesExistingNormalization() throws Exception {
        open((category, clue) -> response("PRIVATE   ANSWER"));
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        JsonObject result = game.submit(owner, id, "  secret   ALIAS  ");
        assertEquals(100, result.getInt("playerPoints"));
        assertEquals(100, result.getInt("computerPoints"));
        assertEquals("tie", result.getString("outcome"));
        assertEquals("FINALIZED", result.getString("state"));
        assertEquals("Readable evidence.", result.getJsonObject("evidence").getString("text"));
        assertFalse(result.toString().contains("/private/index/path"));
    }

    @Test void incorrectAnswersGetZeroAndNoFuzzyCredit() throws Exception {
        open((category, clue) -> response("Other person"));
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        JsonObject result = game.submit(owner, id, "Private");
        assertEquals(0, result.getInt("playerPoints"));
        assertEquals(0, result.getInt("computerPoints"));
    }

    @Test void difficultySelectsPrivatePackAndCannotChangeAnOpenRound() throws Exception {
        Path easy = Files.createTempFile("cluelab-easy-", ".txt");
        List<String> inputs = new CopyOnWriteArrayList<>();
        try {
            Files.write(easy, Arrays.asList("EASY SCIENCE", "A simpler clue. Hint: think science.", "Easy secret|Easy alias"));
            game = new GameService(questions, easy, (category, clue) -> {
                inputs.add(category); inputs.add(clue); return response("Easy secret");
            });
            owner = game.openSession(null);
            assertEquals("easy", game.current(owner).getJsonArray("difficulties").getString(0));
            JsonObject first = game.start(owner, "easy");
            assertEquals("easy", first.getString("difficulty"));
            assertFalse(first.toString().contains("Easy secret"));
            JsonObject resumed = game.start(owner, "standard");
            assertEquals(first.getString("id"), resumed.getString("id"));
            assertEquals("easy", resumed.getString("difficulty"));
            ready(game, owner, first.getString("id"));
            assertEquals(Arrays.asList("EASY SCIENCE", "A simpler clue. Hint: think science."), inputs);
            JsonObject result = game.submit(owner, first.getString("id"), "easy ALIAS");
            assertEquals(100, result.getInt("playerPoints"));
            assertEquals(100, result.getInt("computerPoints"));
            JsonObject next = game.start(owner, "standard");
            assertEquals("standard", next.getString("difficulty"));
            assertEquals("A clue about a scientist.", next.getString("clue"));
        } finally { Files.deleteIfExists(easy); }
    }

    @Test void unknownDifficultyDoesNotCreateARoundOrCallRetrieval() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        open((category, clue) -> { calls.incrementAndGet(); return response("Private Answer"); });
        for (String invalid : Arrays.asList(null, "easy", "hard", "STANDARD", "")) {
            assertEquals(400, assertThrows(GameService.GameError.class, () -> game.start(owner, invalid)).status);
        }
        assertTrue(game.current(owner).isNull("round"));
        assertEquals(0, calls.get());
        assertEquals(1, game.difficulties().size());
    }

    @Test void computerGetsOnlyCategoryAndClueAndRunsOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        List<String> inputs = new CopyOnWriteArrayList<>();
        open((category, clue) -> { calls.incrementAndGet(); inputs.add(category); inputs.add(clue); return response("Other person"); });
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        game.submit(owner, id, "Secret Alias");
        game.submit(owner, id, "Changed answer");
        assertEquals(Arrays.asList("SCIENCE", "A clue about a scientist."), inputs);
        assertEquals(1, calls.get());
    }

    @Test void thinkingAndReadyResponsesDiscloseNeitherAnswerNorEvidence() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        open((category, clue) -> { gate.await(); return response("Computer secret"); });
        JsonObject pending = game.start(owner);
        String id = pending.getString("id");
        assertEquals("THINKING", pending.getString("state"));
        Set<String> publicFields = new HashSet<>(Arrays.asList("id", "number", "state", "category", "clue", "playerTotal", "computerTotal", "difficulty"));
        assertEquals(publicFields, pending.keySet());
        assertEquals(id, game.start(owner).getString("id"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> game.submit(owner, id, "Private Answer")).status);
        gate.countDown();
        JsonObject committed = ready(game, owner, id);
        assertEquals(publicFields, committed.keySet());
        assertFalse(game.current(owner).toString().contains("Computer secret"));
        assertFalse(committed.toString().contains("Private Answer"));
    }

    @Test void duplicateAndConcurrentSubmissionsReturnExactlyTheFirstResult() throws Exception {
        open((category, clue) -> response("Private Answer"));
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        ExecutorService clients = Executors.newFixedThreadPool(2);
        try {
            Future<JsonObject> first = clients.submit(() -> game.submit(owner, id, "Secret Alias"));
            Future<JsonObject> second = clients.submit(() -> game.submit(owner, id, "Wrong"));
            JsonObject result = first.get();
            assertEquals(result, second.get());
            assertEquals(result, game.submit(owner, id, ""));
            assertEquals(result, game.get(owner, id));
            assertEquals(100, result.getInt("computerTotal"));
            assertEquals(result.getInt("playerPoints"), result.getInt("playerTotal"));
            JsonObject next = game.start(owner);
            assertNotEquals(id, next.getString("id"));
            assertEquals(2, next.getInt("number"));
            assertEquals(result, game.submit(owner, id, "Private Answer"));
            assertEquals(100, next.getInt("computerTotal"));
        } finally { clients.shutdownNow(); }
    }

    @Test void unknownAndForeignRoundsAreRejected() throws Exception {
        open((category, clue) -> response("Private Answer"));
        String id = game.start(owner).getString("id");
        assertEquals(404, assertThrows(GameService.GameError.class, () -> game.get(owner, UUID.randomUUID().toString())).status);
        String other = game.openSession(null);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> game.submit(other, id, "Private Answer")).status);
        assertEquals(410, assertThrows(GameService.GameError.class, () -> game.start("fake-session")).status);
    }

    @Test void invalidAnswersDoNotFinalize() throws Exception {
        open((category, clue) -> response("Private Answer"));
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        for (String invalid : Arrays.asList(null, " ", "line\nbreak", String.join("", Collections.nCopies(201, "a")))) {
            assertEquals(400, assertThrows(GameService.GameError.class, () -> game.submit(owner, id, invalid)).status);
            assertEquals("READY", game.get(owner, id).getString("state"));
        }
    }

    @Test void engineFailureStillAllowsPlayerScoringWithoutLeakingException() throws Exception {
        open((category, clue) -> { throw new IOException("/private/secret-index"); });
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        JsonObject result = game.submit(owner, id, "Private Answer");
        assertEquals(100, result.getInt("playerPoints"));
        assertEquals(0, result.getInt("computerPoints"));
        assertEquals("unavailable", result.getString("computerStatus"));
        assertTrue(result.isNull("evidence"));
        assertFalse(result.toString().contains("secret-index"));
    }

    @Test void emptyResultsAreDistinctFromFailure() throws Exception {
        open((category, clue) -> response(null));
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        assertEquals("no-match", game.submit(owner, id, "Wrong").getString("computerStatus"));
    }

    @Test void malformedEngineResultBecomesAnUnavailableAnswerRatherThanBreakingScoring() throws Exception {
        open((category, clue) -> new QueryResponse("clue", 1.0, Collections.singletonList(
                new QueryResponse.Candidate(1, "Private Answer", Double.NaN, "text", "source-excerpt", "file:1")),
                Json.createObjectBuilder().build()));
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        JsonObject result = game.submit(owner, id, "Private Answer");
        assertEquals("unavailable", result.getString("computerStatus"));
        assertEquals(100, result.getInt("playerTotal"));
        assertEquals(0, result.getInt("computerTotal"));
        assertEquals(result, game.submit(owner, id, "Wrong"));
    }

    @Test void timeoutSealsFailureAndLateResultCannotChangeIt() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        CountDownLatch returned = new CountDownLatch(1);
        game = new GameService(questions, (category, clue) -> {
            try { finish.await(); } catch (InterruptedException ignored) { finish.await(); }
            returned.countDown();
            return response("Private Answer");
        }, Clock.systemUTC(), 50);
        owner = game.openSession(null);
        String id = game.start(owner).getString("id");
        ready(game, owner, id);
        JsonObject sealed = game.submit(owner, id, "Private Answer");
        assertEquals("timeout", sealed.getString("computerStatus"));
        finish.countDown();
        assertTrue(returned.await(1, TimeUnit.SECONDS));
        assertEquals(sealed, game.submit(owner, id, "Wrong"));
        assertEquals(0, sealed.getInt("computerPoints"));
    }
}
