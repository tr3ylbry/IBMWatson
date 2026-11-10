package main;

import org.junit.jupiter.api.*;
import jakarta.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GameServerTest {
    private GameServer server;
    private Path questions;
    private String cookie;

    @BeforeEach void setup() throws Exception {
        questions = Files.createTempFile("cluelab-http-", ".txt");
        Files.write(questions, Arrays.asList("CATEGORY", "Visible clue", "Server-only secret|Alternative"));
        server = new GameServer(0, new GameService(questions, (category, clue) -> GameServiceTest.response("Computer-only secret")));
        server.start();
        request("GET", "/api/session", null, null, 200);
    }

    @AfterEach void cleanup() throws Exception { server.close(); Files.delete(questions); }

    private String request(String method, String path, String body, String origin, int expected) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(server.url() + path).openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        connection.setRequestMethod(method);
        if (cookie != null) connection.setRequestProperty("Cookie", cookie);
        if (origin != null) connection.setRequestProperty("Origin", origin);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("X-ClueLab-Request", "1");
            try (OutputStream output = connection.getOutputStream()) { output.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        try {
            assertEquals(expected, connection.getResponseCode(), path);
            assertEquals("no-store", connection.getHeaderField("Cache-Control"));
            assertNotNull(connection.getHeaderField("Content-Security-Policy"));
            String setCookie = connection.getHeaderField("Set-Cookie");
            if (setCookie != null) {
                assertTrue(setCookie.contains("HttpOnly; SameSite=Strict"));
                cookie = setCookie.split(";", 2)[0];
            }
            try (InputStream input = expected >= 400 ? connection.getErrorStream() : connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                return output.toString("UTF-8");
            }
        } finally { connection.disconnect(); }
    }

    private static JsonObject json(String value) {
        try (JsonReader reader = Json.createReader(new StringReader(value))) { return reader.readObject(); }
    }

    private void catalogServer() throws Exception {
        server.close(); cookie = null;
        PackCatalog catalog = PackCatalog.load(Paths.get("data/packs/catalog.json"));
        server = new GameServer(0, new GameService(catalog, (scope, category, clue) -> GameServiceTest.response(null)));
        server.start(); request("GET", "/api/session", null, null, 200);
    }

    private String gameChoice(String requestId, boolean replay) {
        return Json.createObjectBuilder().add("requestId", requestId).add("topicId", "music")
                .add("difficulty", "easy").add("packVersion", "1.0.0").add("replay", replay).build().toString();
    }

    private JsonObject readyGame(String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonObject match = json(request("GET", "/api/games/" + id, null, null, 200));
            if (!match.getJsonObject("round").getString("state").equals("THINKING")) return match;
            Thread.sleep(5);
        }
        fail("Game round did not become ready"); return null;
    }

    @Test void fiveClueHttpLifecycleRecoversSummaryAndOldRequestsAcrossReplay() throws Exception {
        catalogServer();
        PackCatalog.Pack pack = PackCatalog.load(Paths.get("data/packs/catalog.json")).topics.get("music");
        String choice = gameChoice(UUID.randomUUID().toString(), false);
        JsonObject match = json(request("POST", "/api/games", choice, null, 200)); String id = match.getString("id");
        assertEquals(id, json(request("POST", "/api/games", choice, null, 200)).getString("id"));
        assertFalse(match.containsKey("rounds")); assertFalse(match.toString().contains("curatedEvidence"));
        String oldRound = null, oldAnswer = null;
        for (int i = 0; i < 5; i++) {
            match = readyGame(id); JsonObject round = match.getJsonObject("round"); String roundId = round.getString("id");
            String alias = pack.clues.stream().filter(clue -> clue.text.equals(round.getString("clue"))).findFirst().get().aliases.get(0);
            String answer = Json.createObjectBuilder().add("roundId", roundId).add("answer", alias).build().toString();
            if (i == 0) { oldRound = roundId; oldAnswer = answer; }
            String scored = request("POST", "/api/games/" + id + "/answer", answer, null, 200);
            assertEquals(scored, request("POST", "/api/games/" + id + "/answer", answer, null, 200));
            match = json(scored);
            assertEquals(match, json(request("GET", "/api/session", null, null, 200)).getJsonObject("game"));
            if (i < 4) {
                String advance = Json.createObjectBuilder().add("roundId", roundId).build().toString();
                JsonObject next = json(request("POST", "/api/games/" + id + "/advance", advance, null, 200));
                JsonObject retry = json(request("POST", "/api/games/" + id + "/advance", advance, null, 200));
                assertEquals(i + 2, retry.getInt("clueNumber"));
                assertEquals(next.getJsonObject("round").getString("id"), retry.getJsonObject("round").getString("id"));
            }
        }
        assertEquals("COMPLETED", match.getString("state")); assertEquals(500, match.getInt("playerScore"));
        assertEquals(0, match.getInt("computerScore")); assertEquals(5, match.getJsonArray("rounds").size());
        JsonObject replay = json(request("POST", "/api/games", gameChoice(UUID.randomUUID().toString(), true), null, 200));
        assertNotEquals(id, replay.getString("id")); assertEquals(0, replay.getInt("playerScore"));
        assertEquals(match, json(request("GET", "/api/games/" + id, null, null, 200)));
        assertEquals(match, json(request("POST", "/api/games", choice, null, 200)));
        assertEquals(match, json(request("POST", "/api/games/" + id + "/answer", oldAnswer, null, 200)));
        String advance = Json.createObjectBuilder().add("roundId", oldRound).build().toString();
        assertEquals(match, json(request("POST", "/api/games/" + id + "/advance", advance, null, 200)));
        JsonObject session = json(request("GET", "/api/session", null, null, 200));
        assertEquals(replay.getString("id"), session.getJsonObject("game").getString("id"));
        assertEquals(500, session.getJsonObject("sessionTotals").getInt("player"));
        assertEquals(1, session.getJsonArray("completedGames").size());
    }

    @Test void gameHttpBodiesTransitionsAndOwnershipAreStrict() throws Exception {
        catalogServer(); String intention = UUID.randomUUID().toString(), choice = gameChoice(intention, false);
        for (String malformed : Arrays.asList("{broken", "[]", "{}{}", "{\"requestId\":\"one\",\"requestId\":\"two\"}",
                choice.replace("false", "\"false\""), choice.replace(intention, "invalid"),
                choice.replace("\"replay\":false", "\"replay\":false,\"playerScore\":500"), choice.replace("\"topicId\":\"music\"", "\"topicId\":null"))) {
            request("POST", "/api/games", malformed, null, 400);
        }
        JsonObject match = json(request("POST", "/api/games", choice, null, 200));
        String id = match.getString("id"), roundId = match.getJsonObject("round").getString("id");
        request("POST", "/api/games", gameChoice(UUID.randomUUID().toString(), false), null, 409);
        request("POST", "/api/rounds", "{\"topicId\":\"music\",\"difficulty\":\"easy\",\"packVersion\":\"1.0.0\",\"replay\":false}", null, 409);
        request("POST", "/api/games/" + id + "/advance", "{\"roundId\":\"" + roundId + "\"}", null, 409);
        request("POST", "/api/games/" + id + "/advance", "{}", null, 400);
        request("POST", "/api/games/" + id + "/answer", "{\"roundId\":\"" + roundId + "\",\"answer\":\"x\",\"points\":100}", null, 400);
        cookie = null; request("GET", "/api/session", null, null, 200);
        request("GET", "/api/games/" + id, null, null, 404);
        request("GET", "/api/rounds/" + roundId, null, null, 404);
        request("POST", "/api/games/" + id + "/advance", "{\"roundId\":\"" + roundId + "\"}", null, 404);
        request("POST", "/api/games/" + id + "/answer", "{\"roundId\":\"" + roundId + "\",\"answer\":\"x\"}", null, 404);
    }

    @Test void legacyServerKeepsSingleRoundModeAndRejectsGameCreationClearly() throws Exception {
        assertFalse(json(request("GET", "/api/session", null, null, 200)).containsKey("gameLength"));
        request("POST", "/api/games", gameChoice(UUID.randomUUID().toString(), false), null, 400);
        JsonObject round = json(request("POST", "/api/rounds", "{\"difficulty\":\"standard\"}", null, 200));
        assertFalse(round.containsKey("gameId"));
    }

    @Test void catalogHttpFlowPinsConfigurationAndRecoversReceipt() throws Exception {
        catalogServer();
        String choice = "{\"topicId\":\"music\",\"difficulty\":\"easy\",\"packVersion\":\"1.0.0\",\"replay\":false}";
        JsonObject round = json(request("POST", "/api/rounds", choice, null, 200));
        String id = round.getString("id");
        assertEquals(id, json(request("POST", "/api/rounds", choice, null, 200)).getString("id"));
        request("POST", "/api/rounds", choice.replace("music", "geography"), null, 409);
        request("POST", "/api/rounds", choice.replace("1.0.0", "2.0.0"), null, 409);
        assertFalse(round.containsKey("acceptedAnswers")); assertFalse(round.containsKey("clueId"));
        for (int i = 0; i < 100; i++) {
            if (json(request("GET", "/api/rounds/" + id, null, null, 200)).getString("state").equals("READY")) break;
            Thread.sleep(5);
        }
        String result = request("POST", "/api/rounds/" + id + "/answer", "{\"answer\":\"Wrong\"}", null, 200);
        assertEquals(result, request("POST", "/api/rounds/" + id + "/answer", "{\"answer\":\"Changed\"}", null, 200));
        assertEquals(json(result), json(request("GET", "/api/session", null, null, 200)).getJsonObject("round"));
        assertNotNull(json(result).getJsonObject("curatedEvidence"));
    }

    @Test void privatePackPathsUnavailableSelectionsAndAmbiguousBodiesAreRejected() throws Exception {
        catalogServer();
        for (String path : Arrays.asList("/data/packs/catalog.json", "/data/packs/private/music-clues.json", "/data/game.json", "/data/packs/snapshots/music/piano.txt",
                "/docs/review/starter-ledger.json", "/docs/review/observations.csv", "/reviews/local/receipt.json",
                "/data/retrieval-lab/v1/private/held-out.json", "/data/retrieval-lab/v1/experiment.json",
                "/data/external-challenge/v1/private/test.json", "/data/external-challenge/v1/selection.json",
                "/drafts/jovian-v1/export/private/pack.json", "/drafts/jovian-v1/export/private/review.json",
                "/drafts/jovian-v1/export/provenance/capture.json", "/corpus/source-intake-cache/response.json",
                "/drafts/jovian-v2/preview/preview.html", "/drafts/jovian-v2/review.json", "/data/packs/.activation-history/receipt.json",
                "/builder.js", "/builder.css", "/admin/index.html", "/api/jobs", "/api/artifacts/jovian-decision",
                "/corpus/external-triviaqa-v1/members.jsonl", "/target/modernization-diagnosis.json")) request("GET", path, null, null, 404);
        String choice = "{\"topicId\":\"coursework-demo\",\"difficulty\":\"hard\",\"packVersion\":\"1.0.0\",\"replay\":false}";
        request("POST", "/api/rounds", choice, null, 409);
        request("POST", "/api/rounds", choice.replace("false", "\"false\""), null, 400);
        request("POST", "/api/rounds", "{\"difficulty\":\"easy\"}", null, 400);
        request("POST", "/api/rounds", choice.replace("\"replay\":false", "\"replay\":false,\"answer\":\"spoof\""), null, 400);
        assertTrue(json(request("GET", "/api/session", null, null, 200)).isNull("round"));
    }

    @Test void publicAssetsAndPreRevealApiContainNoPrivateAnswers() throws Exception {
        for (String path : Arrays.asList("/", "/app.js", "/presentation.mjs", "/style.css", "/favicon.svg")) {
            String body = request("GET", path, null, null, 200);
            assertFalse(body.contains("Server-only secret"));
            assertFalse(body.contains("Computer-only secret"));
            assertFalse(body.contains("Alternative"));
        }
        String started = request("POST", "/api/rounds", "{\"difficulty\":\"standard\"}", server.url(), 200);
        String id = json(started).getString("id");
        assertFalse(started.contains("secret"));
        String pending = request("GET", "/api/rounds/" + id, null, null, 200);
        assertFalse(pending.contains("secret"));
        assertFalse(pending.contains("evidence"));
        for (String path : Arrays.asList("/questions.txt", "/data/demo/questions.txt", "/data/demo/easy-questions.txt", "/../questions.txt", "/web/../questions.txt", "/pom.xml")) {
            request("GET", path, null, null, 404);
        }
    }

    @Test void httpSubmissionIsScoredOnceAndRecoversOnReload() throws Exception {
        String id = json(request("POST", "/api/rounds", "{\"difficulty\":\"standard\"}", server.url(), 200)).getString("id");
        for (int i = 0; i < 100; i++) {
            if (json(request("GET", "/api/rounds/" + id, null, null, 200)).getString("state").equals("READY")) break;
            Thread.sleep(5);
        }
        String first = request("POST", "/api/rounds/" + id + "/answer", "{\"answer\":\"Alternative\"}", server.url(), 200);
        String second = request("POST", "/api/rounds/" + id + "/answer", "{\"answer\":\"Wrong\"}", server.url(), 200);
        assertEquals(first, second);
        assertEquals(100, json(first).getInt("playerPoints"));
        assertEquals(0, json(first).getInt("computerPoints"));
        assertEquals(json(first), json(request("GET", "/api/session", null, null, 200)).getJsonObject("round"));
    }

    @Test void crossOriginExtraFieldsOversizeAndInvalidJsonAreRejected() throws Exception {
        // HttpURLConnection silently strips Origin. Use a real wire header for this check.
        try (Socket socket = new Socket("127.0.0.1", new URL(server.url()).getPort())) {
            socket.setSoTimeout(2000);
            socket.getOutputStream().write(("POST /api/rounds HTTP/1.1\r\nHost: " + new URL(server.url()).getAuthority()
                    + "\r\nOrigin: https://untrusted.example\r\nContent-Type: application/json\r\n"
                    + "X-ClueLab-Request: 1\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
                    .getBytes(StandardCharsets.UTF_8));
            assertTrue(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine().contains("403"));
        }
        request("POST", "/api/rounds", "{\"points\":100}", server.url(), 400);
        request("POST", "/api/rounds", "{\"difficulty\":\"hard\"}", server.url(), 400);
        request("POST", "/api/rounds", "{\"difficulty\":\"standard\",\"answer\":\"spoof\"}", server.url(), 400);
        request("POST", "/api/rounds", "{broken", server.url(), 400);
        request("POST", "/api/rounds", String.join("", Collections.nCopies(4097, "x")), server.url(), 413);
        request("POST", "/api/rounds", null, null, 403);
        request("GET", "/api/rounds?answer=spoof", null, null, 400);
    }
}
