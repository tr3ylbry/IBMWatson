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

class GameRecoveryCookieTest {
    @TempDir Path root;
    GameService game;
    GameServer server;
    HttpClient client;
    PackCatalog catalog;
    TopicRequestsTest.MutableClock clock = new TopicRequestsTest.MutableClock();
    String owner, other, cookie;
    JsonObject match, receipt, otherMatch;
    Path state;

    @BeforeEach void setup() throws Exception {
        String target = GameStore.identity("cookie-http-test");
        root = root.toRealPath();
        state = root.resolve("saves").resolve(target).resolve("state.json");
        catalog = PackCatalog.load(Path.of("data/packs/catalog.json"));
        game = new GameService(catalog,
                (scope, category, clue) -> GameServiceTest.response(null), clock, 15000)
                .durable(new GameStore(root.resolve("saves"), target));
        owner = game.openSession(null); other = game.openSession(null); cookie = game.cookieName();
        match = start(owner); otherMatch = start(other);
        String clue = match.getJsonObject("round").getString("clue");
        String answer = catalog.topics.get("music").clues.stream().filter(c -> c.text.equals(clue)).findFirst().orElseThrow().aliases.get(0);
        receipt = game.answerGame(owner, match.getString("id"), round(match), answer);
        server = new GameServer(0, game); server.start(); client = HttpClient.newHttpClient();
    }

    private String round(JsonObject match) { return match.getJsonObject("round").getString("id"); }
    private JsonObject start(String owner) throws Exception {
        JsonObject started = game.startGame(owner, UUID.randomUUID().toString(), "music", "easy", "1.0.0", false);
        GameServiceTest.ready(game, owner, round(started));
        return game.getGame(owner, started.getString("id"));
    }
    @AfterEach void close() { if (client != null) client.close(); if (server != null) server.close(); else if (game != null) game.close(); }

    HttpResponse<String> request(String path, String rawCookie, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(server.url() + path));
        if (rawCookie != null) request.header("Cookie", rawCookie);
        if (body != null) request.header("Origin", server.url()).header("X-ClueLab-Request", "1")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonObject json(HttpResponse<String> response) throws Exception { return PackCatalog.parse(response.body(), "http response"); }
    void rejected(String path, String rawCookie, String body) throws Exception {
        byte[] before = Files.readAllBytes(state);
        HttpResponse<String> response = request(path, rawCookie, body);
        assertEquals(410, response.statusCode(), response.body());
        assertTrue(json(response).getString("error").contains("session"));
        assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
        assertFalse(response.body().contains(match.getString("id")));
        assertFalse(response.body().contains(otherMatch.getString("id")));
        assertArrayEquals(before, Files.readAllBytes(state), "Rejected credentials must not create identities, score or modify saves");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"", "\"\"", "\"partial", "partial\"", "", "not-a-uuid", "null", "'quoted'"})
    void malformedRecoveryValuesReturnGoneWithoutAnyMutation(String value) throws Exception {
        String credential = cookie + "=" + value;
        rejected("/api/session", credential, null);
        rejected("/api/games/" + otherMatch.getString("id"), credential, null);
        rejected("/api/games/" + otherMatch.getString("id") + "/answer", credential,
                Json.createObjectBuilder().add("roundId", round(otherMatch)).add("answer", "Piano").build().toString());
        assertEquals(receipt, game.getGame(owner, match.getString("id")));
        assertEquals(otherMatch, game.getGame(other, otherMatch.getString("id")));
    }

    @Test void malformedQuotingAroundARealOwnerNeverNormalizesIntoAuthorization() throws Exception {
        for (String value : List.of("\"" + owner, owner + "\"", "\"\"" + owner + "\"\""))
            rejected("/api/session", cookie + "=" + value, null);
        rejected("/api/session", cookie + "=" + owner + "; " + cookie + "=" + other, null);
    }

    @Test void unknownAndExpiredCredentialsNeverCreateReplacementIdentities() throws Exception {
        rejected("/api/session", cookie + "=" + UUID.randomUUID(), null);
        clock.millis += GameService.SESSION_MS;
        rejected("/api/session", cookie + "=" + owner, null);
    }

    @Test void validQuotedAndUnquotedCookiesRecoverOnlyTheirOwnExistingGame() throws Exception {
        JsonObject saved = PackCatalog.read(state).getJsonObject("state");
        server.close();
        game = new GameService(catalog, (scope, category, clue) -> {
            throw new AssertionError("Recovery must not repeat a committed search");
        }, clock, 15000).durable(new GameStore(root.resolve("saves"), GameStore.identity("cookie-http-test")));
        server = new GameServer(0, game); server.start();
        assertEquals(cookie, game.cookieName());
        assertEquals(saved, PackCatalog.read(state).getJsonObject("state"));
        // Startup may reserialize map order; the HTTP reads themselves must be byte-stable.
        byte[] before = Files.readAllBytes(state);
        for (String value : List.of(owner, "\"" + owner + "\"")) {
            HttpResponse<String> response = request("/api/session", cookie + "=" + value, null);
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(receipt, json(response).getJsonObject("game"));
            assertEquals(404, request("/api/games/" + otherMatch.getString("id"), cookie + "=" + value, null).statusCode());
        }
        assertArrayEquals(before, Files.readAllBytes(state));
    }

    @Test void confirmedNewSessionBypassesRejectedCookieOnlyOnTheExplicitIdentityEndpoint() throws Exception {
        String invalid = cookie + "=\"";
        rejected("/api/session", invalid, null);
        byte[] before = Files.readAllBytes(state);
        JsonObject originalOwners = PackCatalog.read(state).getJsonObject("state").getJsonObject("sessions");
        assertEquals(400, request("/api/session/identity", invalid, "{\"confirmed\":false}").statusCode());
        assertArrayEquals(before, Files.readAllBytes(state));
        HttpResponse<String> created = request("/api/session/identity", invalid, "{\"confirmed\":true}");
        assertEquals(200, created.statusCode(), created.body());
        String freshCookie = created.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        assertTrue(freshCookie.startsWith(cookie + "=")); assertNotEquals(cookie + "=" + owner, freshCookie);
        assertTrue(json(created).isNull("game"));
        assertEquals(0, json(created).getJsonObject("sessionTotals").getInt("player"));
        assertEquals(404, request("/api/games/" + match.getString("id"), freshCookie, null).statusCode());
        assertEquals(receipt, game.getGame(owner, match.getString("id")));
        assertEquals(otherMatch, game.getGame(other, otherMatch.getString("id")));
        JsonObject saved = PackCatalog.read(state).getJsonObject("state");
        assertEquals(3, saved.getJsonObject("sessions").size());
        originalOwners.forEach((id, savedOwner) -> assertEquals(savedOwner, saved.getJsonObject("sessions").get(id)));
    }

    @Test void missingCookieStillCreatesAnInitialSessionButCannotReadAnExistingGame() throws Exception {
        rejected("/api/games/" + match.getString("id"), null, null);
        HttpResponse<String> initial = request("/api/session", null, null);
        assertEquals(200, initial.statusCode(), initial.body());
        assertTrue(json(initial).isNull("game"));
        assertTrue(initial.headers().firstValue("Set-Cookie").orElseThrow().startsWith(cookie + "="));
        assertEquals(receipt, game.getGame(owner, match.getString("id")));
    }
}
