package main;

import org.junit.jupiter.api.*;
import jakarta.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class FiveClueGameTest {
    private PackCatalog catalog;
    private GameService service;
    private String owner;
    private Path fixture;

    @BeforeEach void setup() throws Exception { catalog = PackCatalog.load(Paths.get("data/packs/catalog.json")); }
    @AfterEach void cleanup() throws Exception {
        if (service != null) service.close();
        if (fixture != null) try (Stream<Path> paths = Files.walk(fixture)) {
            for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
        }
    }
    private void open(GameService.ScopedComputer computer) {
        service = new GameService(catalog, computer); owner = service.openSession(null);
    }
    private JsonObject start(String request, String topic, String difficulty, boolean replay) {
        return service.startGame(owner, request, topic, difficulty, "1.0.0", replay);
    }
    private JsonObject start(boolean replay) { return start(UUID.randomUUID().toString(), "music", "easy", replay); }
    private PackCatalog.Clue clue(JsonObject round) {
        return catalog.topics.get(round.getString("topicId")).clues.stream()
                .filter(clue -> clue.text.equals(round.getString("clue"))).findFirst().get();
    }
    private JsonObject answer(JsonObject match, boolean correct) throws Exception {
        JsonObject round = match.getJsonObject("round");
        GameServiceTest.ready(service, owner, round.getString("id"));
        String guess = correct ? clue(round).aliases.get(0) : "Wrong on purpose";
        return service.answerGame(owner, match.getString("id"), round.getString("id"), guess);
    }
    private JsonObject complete(JsonObject match) throws Exception {
        while (match.getString("state").equals("ACTIVE")) {
            String roundId = match.getJsonObject("round").getString("id");
            match = answer(match, true);
            if (match.getString("state").equals("ACTIVE")) match = service.advanceGame(owner, match.getString("id"), roundId);
        }
        return match;
    }
    private List<JsonObject> concurrent(Callable<JsonObject> work) throws Exception {
        ExecutorService clients = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<JsonObject>> futures = new ArrayList<>();
            for (int i = 0; i < 20; i++) futures.add(clients.submit(() -> { start.await(); return work.call(); }));
            start.countDown();
            List<JsonObject> responses = new ArrayList<>();
            for (Future<JsonObject> future : futures) responses.add(future.get(3, TimeUnit.SECONDS));
            return responses;
        } finally { clients.shutdownNow(); }
    }
    private JsonObject level(String topic, String difficulty) {
        for (JsonValue value : service.current(owner).getJsonObject("catalog").getJsonArray("topics")) {
            JsonObject item = (JsonObject) value;
            if (item.getString("id").equals(topic)) for (JsonValue choice : item.getJsonArray("difficulties")) {
                if (((JsonObject) choice).getString("id").equals(difficulty)) return (JsonObject) choice;
            }
        }
        throw new AssertionError("Missing selection");
    }

    @Test void exactlyFiveFinalizedRoundsProduceAnImmutableSummaryAndMissedClueReceipts() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        open((scope, category, text) -> { searches.incrementAndGet(); return GameServiceTest.response("Piano"); });
        JsonObject match = start(false); String id = match.getString("id");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            JsonObject round = match.getJsonObject("round"); String roundId = round.getString("id");
            assertTrue(seen.add(roundId)); assertEquals(i + 1, match.getInt("clueNumber"));
            assertEquals(i, match.getInt("completedRounds"));
            match = answer(match, i % 2 == 0);
            if (i < 4) {
                assertEquals("ACTIVE", match.getString("state"));
                assertEquals(i + 1, searches.get());
                match = service.advanceGame(owner, id, roundId);
            }
        }
        assertEquals("COMPLETED", match.getString("state")); assertTrue(match.isNull("round"));
        assertEquals(300, match.getInt("playerScore")); assertEquals(100, match.getInt("computerScore"));
        assertEquals(5, match.getInt("completedRounds")); assertEquals(5, match.getJsonArray("rounds").size());
        assertEquals(5, searches.get());
        int misses = 0;
        for (JsonValue value : match.getJsonArray("rounds")) {
            JsonObject receipt = (JsonObject) value;
            if (receipt.getInt("playerPoints") == 0) misses++;
            assertNotNull(receipt.getJsonObject("curatedEvidence"));
            assertEquals(receipt, service.get(owner, receipt.getString("id")));
            assertEquals(receipt, service.submit(owner, receipt.getString("id"), "Changed"));
            assertEquals(match, service.advanceGame(owner, id, receipt.getString("id")));
        }
        assertEquals(2, misses);
        assertEquals(match, service.getGame(owner, id));
        assertEquals(match, service.current(owner).getJsonObject("game"));
    }

    @Test void concurrentDuplicateStartsReserveOneGameAndOneFiveClueSelection() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        open((scope, category, text) -> { searches.incrementAndGet(); return GameServiceTest.response(null); });
        String intention = UUID.randomUUID().toString();
        List<JsonObject> results = concurrent(() -> start(intention, "music", "easy", false));
        String id = results.get(0).getString("id");
        for (JsonObject result : results) assertEquals(id, result.getString("id"));
        GameServiceTest.ready(service, owner, results.get(0).getJsonObject("round").getString("id"));
        assertEquals(1, searches.get()); assertEquals(0, level("music", "easy").getInt("remaining"));
        assertEquals(0, service.current(owner).getJsonArray("completedGames").size());
    }

    @Test void distinctConcurrentStartsAllowOnlyOneActiveGame() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        AtomicInteger successes = new AtomicInteger(), conflicts = new AtomicInteger();
        concurrent(() -> {
            try { JsonObject match = start(false); successes.incrementAndGet(); return match; }
            catch (GameService.GameError conflict) {
                assertEquals(409, conflict.status); conflicts.incrementAndGet(); return Json.createObjectBuilder().build();
            }
        });
        assertEquals(1, successes.get()); assertEquals(19, conflicts.get());
    }

    @Test void repeatedConcurrentAdvancementCannotSkipAClueEvenAfterLaterScoring() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        open((scope, category, text) -> { searches.incrementAndGet(); return GameServiceTest.response(null); });
        JsonObject match = start(false); String id = match.getString("id");
        String previous = match.getJsonObject("round").getString("id"); answer(match, true);
        List<JsonObject> results = concurrent(() -> service.advanceGame(owner, id, previous));
        String nextId = results.get(0).getJsonObject("round").getString("id");
        for (JsonObject result : results) {
            assertEquals(2, result.getInt("clueNumber")); assertEquals(nextId, result.getJsonObject("round").getString("id"));
        }
        answer(results.get(0), true);
        JsonObject repeated = service.advanceGame(owner, id, previous);
        assertEquals(2, repeated.getInt("clueNumber")); assertEquals(2, searches.get());
        assertEquals("FINALIZED", repeated.getJsonObject("round").getString("state"));
    }

    @Test void concurrentAnswersAwardOneScoreAndPreserveTheWinningGuess() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject match = start(false); String id = match.getString("id"), roundId = match.getJsonObject("round").getString("id");
        GameServiceTest.ready(service, owner, roundId);
        AtomicInteger attempts = new AtomicInteger(); String alias = clue(match.getJsonObject("round")).aliases.get(0);
        List<JsonObject> results = concurrent(() -> service.answerGame(owner, id, roundId, attempts.getAndIncrement() == 0 ? alias : "Wrong"));
        JsonObject receipt = results.get(0).getJsonObject("round");
        for (JsonObject result : results) assertEquals(receipt, result.getJsonObject("round"));
        assertEquals(receipt.getInt("playerPoints"), service.getGame(owner, id).getInt("playerScore"));
        assertEquals(receipt, service.submit(owner, roundId, "Changed"));
    }

    @Test void completedStartAndOldAnswersStayStableAcrossReplay() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        String request = UUID.randomUUID().toString();
        JsonObject original = complete(start(request, "music", "easy", false));
        JsonObject replay = start(true); String newId = replay.getString("id");
        assertNotEquals(original.getString("id"), newId); assertEquals(0, replay.getInt("playerScore"));
        JsonObject oldRound = original.getJsonArray("rounds").getJsonObject(0);
        assertEquals(original, service.answerGame(owner, original.getString("id"), oldRound.getString("id"), "Changed"));
        assertEquals(original, start(request, "music", "easy", false));
        assertEquals(oldRound, service.submit(owner, oldRound.getString("id"), "Changed again"));
        assertEquals(newId, service.current(owner).getJsonObject("game").getString("id"));
        assertEquals(0, service.getGame(owner, newId).getInt("playerScore"));
        assertEquals(500, service.current(owner).getJsonObject("sessionTotals").getInt("player"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(request, "geography", "easy", false)).status);
    }

    @Test void gameScoresAreIsolatedFromStandaloneAndPreviousGameScores() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject single = service.start(owner, "geography", "easy", "1.0.0", false);
        GameServiceTest.ready(service, owner, single.getString("id"));
        JsonObject singleReceipt = service.submit(owner, single.getString("id"), clue(single).aliases.get(0));
        JsonObject first = complete(start(false));
        assertEquals(500, first.getInt("playerScore"));
        assertEquals(600, service.current(owner).getJsonObject("sessionTotals").getInt("player"));
        JsonObject second = start(true);
        assertEquals(0, second.getInt("playerScore")); assertEquals(0, second.getInt("computerScore"));
        assertEquals(singleReceipt, service.get(owner, single.getString("id")));
        assertEquals(100, singleReceipt.getInt("playerTotal"));
    }

    @Test void selectionAndStandaloneRoundCreationAreLockedUntilGameCompletion() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject match = start(false); answer(match, true);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(UUID.randomUUID().toString(), "geography", "hard", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> service.start(owner, "music", "easy", "1.0.0", false)).status);
        JsonObject completed = complete(service.getGame(owner, match.getString("id")));
        for (JsonValue value : completed.getJsonArray("rounds")) {
            JsonObject round = (JsonObject) value;
            assertEquals("music", round.getString("topicId")); assertEquals("easy", round.getString("difficulty"));
            assertEquals("1.0.0", round.getString("packVersion")); assertEquals("music-sources", round.getString("collectionId"));
        }
    }

    @Test void legacyRoundRequestsCannotBypassGamesAndStillUseTheSameScoringAuthority() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject single = service.start(owner, "music", "easy", "1.0.0", false);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(false)).status);
        GameServiceTest.ready(service, owner, single.getString("id"));
        service.submit(owner, single.getString("id"), clue(single).aliases.get(0));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(false)).status);
        JsonObject match = start(true);
        for (int i = 0; i < 5; i++) {
            JsonObject round = match.getJsonObject("round");
            GameServiceTest.ready(service, owner, round.getString("id"));
            JsonObject scored = service.submit(owner, round.getString("id"), clue(round).aliases.get(0));
            assertEquals(scored, service.submit(owner, round.getString("id"), "Changed"));
            match = service.getGame(owner, match.getString("id"));
            if (i < 4) match = service.advanceGame(owner, match.getString("id"), round.getString("id"));
        }
        assertEquals("COMPLETED", match.getString("state")); assertEquals(500, match.getInt("playerScore"));
        assertEquals(600, service.current(owner).getJsonObject("sessionTotals").getInt("player"));
    }

    @Test void fewerThanFiveRemainingRequiresExplicitResetAndDoesNotConsumeOnFailure() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject first = complete(start(UUID.randomUUID().toString(), "coursework-demo", "easy", false));
        assertEquals(3, level("coursework-demo", "easy").getInt("remaining"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(UUID.randomUUID().toString(), "coursework-demo", "easy", false)).status);
        assertEquals(3, level("coursework-demo", "easy").getInt("remaining"));
        assertEquals(first, service.current(owner).getJsonObject("game"));
        JsonObject replay = complete(start(UUID.randomUUID().toString(), "coursework-demo", "easy", true));
        assertEquals(2, level("coursework-demo", "easy").getInt("cycle"));
        Set<String> ids = new HashSet<>();
        for (JsonValue round : replay.getJsonArray("rounds")) assertTrue(ids.add(((JsonObject) round).getString("clueId")));
        assertEquals(5, ids.size()); assertEquals(3, level("coursework-demo", "easy").getInt("remaining"));
    }

    @Test void aShortPackCannotBeMadePlayableByResettingOrFallingBack() throws Exception {
        fixture = Files.createTempDirectory("cluelab-short-pack-");
        Path sourceRoot = Paths.get("data/packs");
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                Path target = fixture.resolve(sourceRoot.relativize(path));
                if (Files.isDirectory(path)) Files.createDirectories(target); else Files.copy(path, target);
            }
        }
        Path file = fixture.resolve("private/music-clues.json"); JsonObject pack = PackCatalog.read(file);
        JsonArrayBuilder clues = Json.createArrayBuilder(); int easy = 0;
        for (JsonValue value : pack.getJsonArray("clues")) {
            if (!((JsonObject) value).getString("difficulty").equals("easy") || easy++ < 4) clues.add(value);
        }
        JsonObjectBuilder changed = Json.createObjectBuilder(); pack.forEach(changed::add);
        Files.write(file, changed.add("clues", clues).build().toString().getBytes(StandardCharsets.UTF_8));
        catalog = PackCatalog.load(fixture.resolve("catalog.json"));
        open((scope, category, text) -> { fail("Insufficient content must not reach retrieval"); return null; });
        for (boolean reset : Arrays.asList(false, true)) assertEquals(409, assertThrows(GameService.GameError.class, () -> start(reset)).status);
        assertTrue(service.current(owner).isNull("game")); assertTrue(service.current(owner).isNull("round"));
        assertEquals(4, level("music", "easy").getInt("remaining"));
    }

    @Test void invalidStartChoicesAndResetFlagsDoNotChangeTheSession() {
        open((scope, category, text) -> { fail("Invalid selection reached retrieval"); return null; });
        assertEquals(400, assertThrows(GameService.GameError.class, () -> start("invalid", "music", "easy", false)).status);
        assertEquals(400, assertThrows(GameService.GameError.class, () -> start(UUID.randomUUID().toString(), "missing", "easy", false)).status);
        assertEquals(400, assertThrows(GameService.GameError.class, () -> start(UUID.randomUUID().toString(), "music", "impossible", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(UUID.randomUUID().toString(), "coursework-demo", "hard", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> service.startGame(owner, UUID.randomUUID().toString(), "music", "easy", "2.0.0", false)).status);
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(true)).status);
        assertTrue(service.current(owner).isNull("game")); assertEquals(5, level("music", "easy").getInt("remaining"));
    }

    @Test void futureCluesAndAnswerMetadataRemainPrivateAndRetrievalRunsOnlyWhenOpened() throws Exception {
        List<String> inputs = new CopyOnWriteArrayList<>();
        open((scope, category, text) -> { inputs.add(scope.key()); inputs.add(category); inputs.add(text); return GameServiceTest.response("Scott Joplin"); });
        JsonObject match = start(false), visible = match.getJsonObject("round");
        GameServiceTest.ready(service, owner, visible.getString("id"));
        match = service.getGame(owner, match.getString("id"));
        String json = service.current(owner).toString();
        for (String secret : Arrays.asList("Scott Joplin", "acceptedAnswers", "aliases", "answerSourceId", "support", "curatedEvidence", "computerAnswer", "clueId")) assertFalse(json.contains(secret), secret);
        for (PackCatalog.Clue clue : catalog.topics.get("music").clues) if (!clue.text.equals(visible.getString("clue"))) assertFalse(json.contains(clue.text));
        PackCatalog.Clue clue = clue(visible);
        assertEquals(Arrays.asList("music-sources@1.0.0", clue.category, clue.retrievalText()), inputs);
        assertEquals(0, match.getInt("completedRounds")); assertEquals(1, match.getInt("clueNumber"));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> service.advanceGame(owner, visible.getString("gameId"), visible.getString("id"))).status);
    }

    @Test void foreignGamesAndRoundsCannotBeReadAnsweredOrAdvanced() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject match = start(false); String id = match.getString("id"), roundId = match.getJsonObject("round").getString("id");
        String other = service.openSession(null);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.getGame(other, id)).status);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.answerGame(other, id, roundId, "Wrong")).status);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.advanceGame(other, id, roundId)).status);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.answerGame(owner, id, UUID.randomUUID().toString(), "Wrong")).status);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.advanceGame(owner, id, UUID.randomUUID().toString())).status);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.get(other, roundId)).status);
    }

    @Test void engineExceptionsAllowAllFiveRoundsToFinish() throws Exception {
        open((scope, category, text) -> { throw new IOException("private index path"); });
        JsonObject result = complete(start(false));
        assertEquals(500, result.getInt("playerScore")); assertEquals(0, result.getInt("computerScore"));
        for (JsonValue value : result.getJsonArray("rounds")) {
            JsonObject round = (JsonObject) value;
            assertEquals("unavailable", round.getString("computerStatus")); assertTrue(round.isNull("evidence"));
            assertNotNull(round.getJsonObject("curatedEvidence"));
        }
        assertFalse(result.toString().contains("private index path"));
    }

    @Test void noMatchesAllowCompletionWithZeroComputerPoints() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject result = complete(start(false));
        assertEquals(500, result.getInt("playerScore")); assertEquals(0, result.getInt("computerScore"));
        result.getJsonArray("rounds").forEach(value -> assertEquals("no-match", ((JsonObject) value).getString("computerStatus")));
    }

    @Test void aLateEngineResponseCannotRewriteAPreviousRoundOrGameTotals() throws Exception {
        CountDownLatch release = new CountDownLatch(1), returned = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        service = new GameService(catalog, (scope, category, text) -> {
            if (calls.getAndIncrement() == 0) {
                try { release.await(); } catch (InterruptedException ignored) { release.await(); }
                returned.countDown(); return GameServiceTest.response("Piano");
            }
            return GameServiceTest.response(null);
        }, Clock.systemUTC(), 50);
        owner = service.openSession(null);
        try {
            JsonObject match = answer(start(false), true), sealed = match.getJsonObject("round");
            assertEquals("timeout", sealed.getString("computerStatus"));
            match = service.advanceGame(owner, match.getString("id"), sealed.getString("id"));
            release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS));
            JsonObject result = complete(match);
            assertEquals(0, result.getInt("computerScore")); assertEquals(500, result.getInt("playerScore"));
            assertEquals(sealed, result.getJsonArray("rounds").getJsonObject(0));
            assertEquals(result, service.getGame(owner, result.getString("id")));
        } finally { release.countDown(); }
    }

    @Test void receiptsAreRetainedUntilTheDocumentedCapacityAndNoPartialGameStarts() throws Exception {
        open((scope, category, text) -> GameServiceTest.response(null));
        JsonObject first = null;
        for (int i = 0; i < 12; i++) {
            JsonObject result = complete(start(i > 0));
            if (first == null) first = result;
        }
        assertEquals(12, service.current(owner).getJsonArray("completedGames").size());
        assertEquals(first, service.getGame(owner, first.getString("id")));
        assertEquals(409, assertThrows(GameService.GameError.class, () -> start(true)).status);
        assertEquals(0, level("music", "easy").getInt("remaining"));
    }

    @Test void sessionExpiryClearsGameRecoveryAndOldOwnership() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T12:00:00Z"));
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        service = new GameService(catalog, (scope, category, text) -> GameServiceTest.response(null), clock, 15000);
        owner = service.openSession(null); JsonObject original = complete(start(false));
        now.set(now.get().plusSeconds(7200));
        String replacement = service.openSession(owner); assertNotEquals(owner, replacement);
        assertEquals(410, assertThrows(GameService.GameError.class, () -> service.getGame(owner, original.getString("id"))).status);
        assertEquals(404, assertThrows(GameService.GameError.class, () -> service.getGame(replacement, original.getString("id"))).status);
        assertTrue(service.current(replacement).isNull("game"));
        assertEquals(0, service.current(replacement).getJsonArray("completedGames").size());
    }
}
