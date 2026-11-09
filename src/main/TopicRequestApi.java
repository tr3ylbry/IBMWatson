package main;

import com.sun.net.httpserver.HttpExchange;
import jakarta.json.*;
import java.io.IOException;
import java.util.*;

/** Public allowlisted request DTOs, with request ownership separate from two-hour game ownership. */
final class TopicRequestApi implements AutoCloseable {
    static final String COOKIE = "cluelab_topics";
    final TopicRequests store;
    final PlayerReadiness player;
    final GameService game;
    TopicRequestApi(TopicRequests store, GameConfig config, GameService game) throws IOException {
        this.store = store; this.player = new PlayerReadiness(store.root, config); this.game = game;
    }
    private static String credential(HttpExchange exchange) {
        String token = null, cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals(COOKIE)) {
                if (token != null) throw new GameService.GameError(401, "Duplicate topic recovery cookie.");
                token = pair[1]; if (token.length() >= 2 && token.startsWith("\"") && token.endsWith("\"")) token = token.substring(1, token.length()-1);
            }
        }
        return token;
    }
    private String issue(HttpExchange exchange) throws IOException {
        String token = store.createOwner(); exchange.getResponseHeaders().add("Set-Cookie", COOKIE+"="+token+"; Path=/api/topic-requests; HttpOnly; SameSite=Strict; Max-Age=604800"); return token;
    }
    JsonObject handle(HttpExchange x, String method, String path, String session, String origin) throws IOException {
        String site = x.getRequestHeaders().getFirst("Sec-Fetch-Site");
        if (site != null && !Set.of("same-origin", "none").contains(site) || method.equals("POST") && !origin.equals(x.getRequestHeaders().getFirst("Origin")))
            throw new GameService.GameError(403, "Topic-request mutations require the exact player origin.");
        if (path.equals("/api/topic-requests/identity") && method.equals("POST")) {
            JsonObject body = GameServer.body(x); GameServer.fields(body, "confirmed");
            if (body.get("confirmed") != JsonValue.TRUE) throw new GameService.GameError(400, "Explicitly confirm a new recovery identity.");
            return list(issue(x));
        }
        String credential = credential(x);
        if (path.equals("/api/topic-requests") && method.equals("GET")) return list(credential == null ? issue(x) : credential);
        if (path.equals("/api/topic-requests") && method.equals("POST")) {
            JsonObject body = GameServer.body(x); GameServer.fields(body, "retryId", "topic", "difficulty");
            return view(store.submit(credential, player.key, GameServer.string(body,"retryId"), GameServer.string(body,"topic"), GameServer.string(body,"difficulty")));
        }
        if (path.matches("/api/topic-requests/[a-f0-9-]{36}") && method.equals("GET")) return view(store.get(credential, player.key, path.substring(20)));
        if (path.matches("/api/topic-requests/[a-f0-9-]{36}/(cancel|choose|play)") && method.equals("POST")) {
            int end = path.lastIndexOf('/'); String id = path.substring(20, end); JsonObject body = GameServer.body(x);
            if (path.endsWith("/cancel")) { GameServer.fields(body, "retryId"); return view(store.cancel(credential, player.key, id, GameServer.string(body,"retryId"))); }
            if (path.endsWith("/choose")) {
                GameServer.fields(body, "retryId", "selectionId"); JsonObject row = store.get(credential, player.key, id);
                String selection = GameServer.string(body, "selectionId");
                JsonObject selected = choices(row).stream().filter(c -> c.getString("selectionId").equals(selection)).findFirst().orElseThrow(() -> new GameService.GameError(409, "Pack offer changed or is not compatible. Refresh and explicitly choose again."));
                return view(store.choose(credential, player.key, id, GameServer.string(body, "retryId"), selected));
            }
            GameServer.fields(body,"retryId","selectionId","replay");
            if (body.get("replay") != JsonValue.TRUE && body.get("replay") != JsonValue.FALSE) throw new GameService.GameError(400,"replay must be an explicit boolean.");
            String retry = GameServer.string(body,"retryId"), selection = GameServer.string(body,"selectionId");
            // Only a hash of the game credential belongs on disk, never its bearer cookie.
            String gameOwner = game.authorizedIdentity(session);
            return store.play(credential,player.key,id,retry,selection,body.getBoolean("replay"),player.boot,gameOwner,(row,intention)->{
                if (!intention.getString("session").equals(gameOwner)) throw new GameService.GameError(410,"That Play intention belongs to a different or expired game identity. It cannot create a replacement game.");
                JsonObject previous = game.retryGame(session,retry); if (previous != null) return previous;
                if (!intention.getString("boot").equals(player.boot)) throw new GameService.GameError(410,"That Play intention was interrupted before a recoverable game committed. Reconnect and use a new deliberate Play intention; no replacement was created.");
                if (TopicRequests.TERMINAL.contains(row.getString("phase"))) throw new GameService.GameError(409,"This request is closed.");
                JsonObject binding = row.getJsonObject("offered"); JsonObject check = PlayerReadiness.check(store.root,player.loaded.configPath,binding);
                if (!check.getBoolean("ready")) throw new GameService.GameError(409,check.getString("message"));
                return game.startGame(session,retry,binding.getString("topicId"),row.getString("difficulty"),binding.getString("packVersion"),body.getBoolean("replay"));
            });
        }
        throw new GameService.GameError(404,"Topic request endpoint not found.");
    }
    JsonObject list(String credential) throws IOException {
        JsonArrayBuilder rows = Json.createArrayBuilder(); for (JsonObject row : store.list(credential,player.key)) rows.add(view(row));
        return Json.createObjectBuilder().add("requests",rows).add("ownerLifetimeDays",7).add("maxPending",4).add("maxRetained",8).build();
    }
    private List<JsonObject> choices(JsonObject row) {
        List<JsonObject> choices = new ArrayList<>();
        for (PackCatalog.Pack pack : player.loaded.catalog.topics.values()) if (pack.eligible(row.getString("difficulty")).size()>=5 && PlayerReadiness.compatible(pack,row.getString("topic"))) choices.add(PlayerReadiness.binding(pack,row.getString("difficulty")));
        if (!row.isNull("offered") && !choices.contains(row.getJsonObject("offered"))) choices.add(row.getJsonObject("offered"));
        return choices;
    }
    JsonObject view(JsonObject row) {
        JsonObject check = row.isNull("offered") ? PlayerReadiness.result(false,"choice-required","Explicitly choose an active match, or wait for manual preparation. No automatic fetching, approval or activation.") : PlayerReadiness.check(store.root,player.loaded.configPath,row.getJsonObject("offered"));
        if (TopicRequests.TERMINAL.contains(row.getString("phase"))) check = PlayerReadiness.result(false,"request-closed","This request is closed. Content and previously started games are unchanged.");
        else if (check.getBoolean("ready") && row.getString("accepted").isEmpty()) check = PlayerReadiness.result(false,"choice-required","The operator's pack is available. Inspect its identity and explicitly select it before playing.");
        return PlayerReadiness.publicView(row,check,choices(row));
    }
    @Override public void close() throws IOException { player.close(); }
}
