package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TopicRequestCookieTest {
    @TempDir Path root;
    TopicRequestsTest fixture;
    GameService game;
    HttpClient client;
    String other, gameCookie;
    JsonObject owned, foreign, gameReceipt;
    Path ledger, save;

    @BeforeEach void setup() throws Exception {
        fixture = new TopicRequestsTest(); fixture.root = root.toRealPath(); fixture.setup();
        other = fixture.store.createOwner();
        owned = fixture.submit("Music", "easy");
        foreign = fixture.store.submit(other, fixture.key, UUID.randomUUID().toString(), "Geography", "easy");
        String target = GameStore.identity("topic-cookie-http");
        game = new GameService(fixture.loaded.catalog, (scope, category, clue) -> GameServiceTest.response(null), fixture.clock, 15000)
                .durable(new GameStore(fixture.root.resolve("saves"), target));
        String player = game.openSession(null); gameCookie = game.cookieName() + "=" + player;
        JsonObject started = game.startGame(player, UUID.randomUUID().toString(), "music", "easy", "1.0.0", false);
        String round = started.getJsonObject("round").getString("id");
        JsonObject ready = GameServiceTest.ready(game, player, round);
        String text = ready.getString("clue");
        String answer = fixture.loaded.catalog.topics.get("music").clues.stream().filter(c -> c.text.equals(text)).findFirst().orElseThrow().aliases.get(0);
        gameReceipt = game.answerGame(player, started.getString("id"), round, answer);
        ledger = fixture.store.root.resolve("ledger.json"); save = fixture.root.resolve("saves").resolve(target).resolve("state.json");
        fixture.server = new GameServer(0, game, fixture.loaded, fixture.store); fixture.server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void close() throws Exception { if (client != null) client.close(); if (fixture != null) fixture.cleanup(); if (game != null) game.close(); }
    String cookie(String value) { return TopicRequestApi.COOKIE + "=" + value; }
    HttpResponse<String> call(String path, String topicCookie, String body, String origin, boolean header) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(fixture.server.url() + path))
                .header("Cookie", gameCookie + (topicCookie == null ? "" : "; " + topicCookie));
        if (origin != null) request.header("Origin", origin);
        if (header) request.header("X-ClueLab-Request", "1");
        if (body != null) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> call(String path, String topicCookie, String body) throws Exception {
        return call(path, topicCookie, body, body == null ? null : fixture.server.url(), true);
    }
    JsonObject json(HttpResponse<String> response) throws Exception { return PackCatalog.parse(response.body(), "HTTP fixture"); }
    void rejected(String path, String topicCookie, String body) throws Exception {
        byte[] ledgerBefore = Files.readAllBytes(ledger), gameBefore = Files.readAllBytes(save);
        HttpResponse<String> response = call(path, topicCookie, body);
        assertEquals(401, response.statusCode(), response.body());
        assertTrue(json(response).getString("error").contains("identity") || json(response).getString("error").contains("cookie"));
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertFalse(response.body().contains(owned.getString("id"))); assertFalse(response.body().contains(foreign.getString("id")));
        assertArrayEquals(ledgerBefore, Files.readAllBytes(ledger)); assertArrayEquals(gameBefore, Files.readAllBytes(save));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"", "\"\"", "\"partial", "partial\"", "", "invalid", "null", "'quoted'"})
    void malformedValuesRejectReadsAndMutationsWithoutChangingOwnersRequestsOrGames(String value) throws Exception {
        rejected("/api/topic-requests", cookie(value), null);
        rejected("/api/topic-requests/" + foreign.getString("id"), cookie(value), null);
        rejected("/api/topic-requests/" + owned.getString("id") + "/cancel", cookie(value),
                Json.createObjectBuilder().add("retryId", UUID.randomUUID().toString()).build().toString());
        rejected("/api/topic-requests/" + owned.getString("id") + "/play", cookie(value),
                Json.createObjectBuilder().add("retryId", UUID.randomUUID().toString()).add("selectionId", "fixture-unselected").add("replay", false).build().toString());
    }

    @Test void duplicatesAndIncompleteQuotesAroundARealOwnerNeverAuthorize() throws Exception {
        rejected("/api/topic-requests", cookie(fixture.owner) + "; " + cookie(other), null);
        for (String value : List.of("\"" + fixture.owner, fixture.owner + "\"", "\"\"" + fixture.owner + "\"\""))
            rejected("/api/topic-requests", cookie(value), null);
    }

    @Test void unknownWrongSecretAndExpiredCredentialsNeverCreateReplacements() throws Exception {
        rejected("/api/topic-requests", cookie(UUID.randomUUID() + "." + TopicBuilderServer.secret()), null);
        rejected("/api/topic-requests", cookie(fixture.owner.split("\\.")[0] + "." + TopicBuilderServer.secret()), null);
        fixture.clock.millis += TopicRequests.TTL;
        rejected("/api/topic-requests", cookie(fixture.owner), null);
    }

    @Test void validQuotedAndUnquotedCredentialsRecoverOnlyTheirOwnRequestsAfterRestart() throws Exception {
        byte[] before = Files.readAllBytes(ledger);
        fixture.server.close();
        fixture.store = new TopicRequests(fixture.store.root, fixture.clock);
        game = new GameService(fixture.loaded.catalog, (scope, category, clue) -> {
            throw new AssertionError("Recovery must not repeat a committed search");
        }, fixture.clock, 15000).durable(new GameStore(fixture.root.resolve("saves"), GameStore.identity("topic-cookie-http")));
        fixture.server = new GameServer(0, game, fixture.loaded, fixture.store); fixture.server.start();
        byte[] gameBefore = Files.readAllBytes(save);
        for (String value : List.of(fixture.owner, "\"" + fixture.owner + "\"")) {
            HttpResponse<String> response = call("/api/topic-requests", cookie(value), null);
            assertEquals(200, response.statusCode());
            JsonArray rows = json(response).getJsonArray("requests"); assertEquals(1, rows.size());
            assertEquals(owned.getString("id"), rows.getJsonObject(0).getString("id"));
            assertEquals(404, call("/api/topic-requests/" + foreign.getString("id"), cookie(value), null).statusCode());
        }
        assertEquals(gameReceipt, json(call("/api/session", null, null)).getJsonObject("game"));
        assertArrayEquals(before, Files.readAllBytes(ledger)); assertArrayEquals(gameBefore, Files.readAllBytes(save));
    }

    @Test void confirmedNewIdentityRecoversFromMalformedOrDuplicateOldCookiesWithoutTransferringData() throws Exception {
        for (String oldCookie : List.of(cookie("\""), cookie(fixture.owner) + "; " + cookie(other))) {
            JsonObject before = IntakeFiles.read(ledger); byte[] gameBefore = Files.readAllBytes(save);
            HttpResponse<String> response = call("/api/topic-requests/identity", oldCookie, "{\"confirmed\":true}");
            assertEquals(200, response.statusCode(), response.body()); assertTrue(json(response).getJsonArray("requests").isEmpty());
            String fresh = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertTrue(fresh.startsWith(TopicRequestApi.COOKIE + "=")); assertNotEquals(cookie(fixture.owner), fresh);
            assertEquals(404, call("/api/topic-requests/" + owned.getString("id"), fresh, null).statusCode());
            JsonObject after = IntakeFiles.read(ledger);
            assertEquals(before.getJsonArray("owners").size() + 1, after.getJsonArray("owners").size());
            assertTrue(after.getJsonArray("owners").containsAll(before.getJsonArray("owners")));
            assertEquals(before.getJsonArray("requests"), after.getJsonArray("requests"));
            assertArrayEquals(gameBefore, Files.readAllBytes(save));
        }
    }

    @Test void identityReplacementStillRequiresExactOriginHeaderAndConfirmation() throws Exception {
        String bad = cookie("\""); byte[] before = Files.readAllBytes(ledger), gameBefore = Files.readAllBytes(save);
        for (String body : List.of("{\"confirmed\":false}", "{}", "{\"confirmed\":\"true\"}", "{\"confirmed\":true,\"extra\":1}"))
            assertEquals(400, call("/api/topic-requests/identity", bad, body).statusCode());
        assertEquals(403, call("/api/topic-requests/identity", bad, "{\"confirmed\":true}", null, true).statusCode());
        assertEquals(403, call("/api/topic-requests/identity", bad, "{\"confirmed\":true}", "http://127.0.0.1:8766", true).statusCode());
        assertEquals(403, call("/api/topic-requests/identity", bad, "{\"confirmed\":true}", fixture.server.url(), false).statusCode());
        assertArrayEquals(before, Files.readAllBytes(ledger)); assertArrayEquals(gameBefore, Files.readAllBytes(save));
    }

    @Test void missingTopicCookieRetainsBootstrapButDoesNotAuthorizeMutations() throws Exception {
        String submit = Json.createObjectBuilder().add("retryId", UUID.randomUUID().toString()).add("topic", "Music").add("difficulty", "easy").build().toString();
        rejected("/api/topic-requests", null, submit);
        rejected("/api/topic-requests/" + owned.getString("id"), null, null);
        HttpResponse<String> response = call("/api/topic-requests", null, null);
        assertEquals(200, response.statusCode()); assertTrue(json(response).getJsonArray("requests").isEmpty());
        assertTrue(response.headers().firstValue("Set-Cookie").orElseThrow().startsWith(TopicRequestApi.COOKIE + "="));
        assertEquals(owned, fixture.store.get(fixture.owner, fixture.key, owned.getString("id")));
    }
}
