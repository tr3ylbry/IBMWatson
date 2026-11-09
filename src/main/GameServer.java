package main;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.json.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.*;

/** Deliberately loopback-only HTTP adapter with a fixed public asset allowlist. */
public final class GameServer implements AutoCloseable {
    private final HttpServer http;
    private final GameService game;
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), GameService.daemonThreads("cluelab-http"));
    private final String authority;
    private TopicRequestApi topics;

    public GameServer(int port, GameService game) throws IOException {
        this.game = game;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        authority = "127.0.0.1:" + http.getAddress().getPort();
        http.createContext("/", this::handle);
        http.setExecutor(requests);
    }
    GameServer(int port, GameService game, GameConfig config, TopicRequests requests) throws IOException {
        this(port, game);
        try { topics = new TopicRequestApi(requests, config, game); }
        catch (IOException error) { http.stop(0); this.requests.shutdownNow(); throw error; }
    }

    public String url() { return "http://" + authority; }
    public void start() {
        http.start();
        if (topics != null) try { topics.player.started(); } catch (IOException e) { close(); throw new IllegalStateException("Could not register the player catalog",e); }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
            if (!authority.equals(exchange.getRequestHeaders().getFirst("Host"))) {
                throw new GameService.GameError(403, "Use the printed 127.0.0.1 address.");
            }
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String site = exchange.getRequestHeaders().getFirst("Sec-Fetch-Site");
            if ((origin != null && !url().equals(origin)) || "cross-site".equals(site)) {
                throw new GameService.GameError(403, "Cross-origin requests are not allowed.");
            }
            String path = exchange.getRequestURI().getRawPath();
            if (exchange.getRequestURI().getRawQuery() != null) throw new GameService.GameError(400, "Query parameters are not supported.");
            String method = exchange.getRequestMethod();
            if (path.startsWith("/api/")) {
                if (!method.equals("GET") && !method.equals("POST")) throw new GameService.GameError(405, "Method not allowed.");
                if (method.equals("POST") && !"1".equals(exchange.getRequestHeaders().getFirst("X-ClueLab-Request"))) {
                    throw new GameService.GameError(403, "Missing same-origin request header.");
                }
                api(exchange, method, path);
            } else {
                if (!method.equals("GET")) throw new GameService.GameError(405, "Method not allowed.");
                String resource, type;
                switch (path) {
                    case "/": resource = "index.html"; type = "text/html"; break;
                    case "/app.js": resource = "app.js"; type = "text/javascript"; break;
                    case "/presentation.mjs": resource = "presentation.mjs"; type = "text/javascript"; break;
                    case "/style.css": resource = "style.css"; type = "text/css"; break;
                    case "/favicon.svg": resource = "favicon.svg"; type = "image/svg+xml"; break;
                    default: throw new GameService.GameError(404, "Not found.");
                }
                try (InputStream input = GameServer.class.getResourceAsStream("/web/" + resource)) {
                    if (input == null) throw new IOException("Missing packaged web resource.");
                    send(exchange, 200, type, readLimited(input, 128 * 1024));
                }
            }
        } catch (GameService.GameError error) {
            json(exchange, error.status, Json.createObjectBuilder().add("error", error.getMessage()).build());
        } catch (JsonException | IllegalArgumentException error) {
            json(exchange, 400, Json.createObjectBuilder().add("error", "Invalid JSON request.").build());
        } catch (Exception error) {
            System.err.println("ClueLab HTTP failure (" + error.getClass().getSimpleName() + ").");
            json(exchange, 500, Json.createObjectBuilder().add("error", "The local server could not complete this request.").build());
        } finally { exchange.close(); }
    }

    private void api(HttpExchange exchange, String method, String path) throws IOException {
        String owner = path.equals("/api/session/identity") ? "" : cookie(exchange);
        if (topics != null && path.startsWith("/api/topic-requests")) { json(exchange,200,topics.handle(exchange,method,path,owner,url())); return; }
        if (path.equals("/api/session/identity") && method.equals("POST")) {
            JsonObject body = body(exchange); fields(body, "confirmed");
            if (body.get("confirmed") != JsonValue.TRUE) throw new GameService.GameError(400, "Explicitly confirm a new game session.");
            String session = game.openSession(null);
            sessionCookie(exchange, session);
            json(exchange, 200, sessionView(session));
        } else if (path.equals("/api/session") && method.equals("GET")) {
            String session = game.openSession(owner);
            sessionCookie(exchange, session);
            json(exchange, 200, sessionView(session));
        } else if (path.equals("/api/games") && method.equals("POST")) {
            JsonObject body = body(exchange);
            fields(body, "requestId", "topicId", "difficulty", "packVersion", "replay");
            JsonValue replay = body.get("replay");
            if (replay != JsonValue.TRUE && replay != JsonValue.FALSE) throw new GameService.GameError(400, "replay must be a boolean.");
            json(exchange, 200, game.startGame(owner, string(body, "requestId"), string(body, "topicId"),
                    string(body, "difficulty"), string(body, "packVersion"), body.getBoolean("replay")));
        } else if (path.matches("/api/games/[a-f0-9-]{36}") && method.equals("GET")) {
            json(exchange, 200, game.getGame(owner, path.substring("/api/games/".length())));
        } else if (path.matches("/api/games/[a-f0-9-]{36}/(answer|advance)") && method.equals("POST")) {
            JsonObject body = body(exchange);
            int suffix = path.lastIndexOf('/');
            String id = path.substring("/api/games/".length(), suffix);
            if (path.endsWith("/answer")) {
                fields(body, "roundId", "answer");
                json(exchange, 200, game.answerGame(owner, id, string(body, "roundId"), string(body, "answer")));
            } else {
                fields(body, "roundId");
                json(exchange, 200, game.advanceGame(owner, id, string(body, "roundId")));
            }
        } else if (path.equals("/api/rounds") && method.equals("POST")) {
            JsonObject body = body(exchange);
            if (game.usesCatalog()) {
                if (!body.keySet().equals(new HashSet<>(Arrays.asList("topicId", "difficulty", "packVersion", "replay")))
                        || body.get("topicId").getValueType() != JsonValue.ValueType.STRING
                        || body.get("difficulty").getValueType() != JsonValue.ValueType.STRING
                        || body.get("packVersion").getValueType() != JsonValue.ValueType.STRING
                        || (body.get("replay") != JsonValue.TRUE && body.get("replay") != JsonValue.FALSE)) {
                    throw new GameService.GameError(400, "Send topicId, difficulty, packVersion, and a boolean replay flag.");
                }
                json(exchange, 200, game.start(owner, body.getString("topicId"), body.getString("difficulty"),
                        body.getString("packVersion"), body.getBoolean("replay")));
                return;
            }
            if (!body.keySet().equals(Collections.singleton("difficulty")) || body.get("difficulty").getValueType() != JsonValue.ValueType.STRING) {
                throw new GameService.GameError(400, "Choose a difficulty to start a round.");
            }
            json(exchange, 200, game.start(owner, body.getString("difficulty")));
        } else if (path.matches("/api/rounds/[a-f0-9-]{36}") && method.equals("GET")) {
            json(exchange, 200, game.get(owner, path.substring("/api/rounds/".length())));
        } else if (path.matches("/api/rounds/[a-f0-9-]{36}/answer") && method.equals("POST")) {
            JsonObject body = body(exchange);
            if (!body.keySet().equals(Collections.singleton("answer")) || body.get("answer").getValueType() != JsonValue.ValueType.STRING) {
                throw new GameService.GameError(400, "Only a string answer is accepted.");
            }
            String id = path.substring("/api/rounds/".length(), path.length() - "/answer".length());
            json(exchange, 200, game.submit(owner, id, body.getString("answer")));
        } else throw new GameService.GameError(404, "Endpoint not found.");
    }

    private JsonObject sessionView(String session) {
        JsonObject snapshot = game.current(session);
        return topics == null ? snapshot : Json.createObjectBuilder(snapshot).add("topicRequestsEnabled",true).build();
    }
    private void sessionCookie(HttpExchange exchange, String session) {
        exchange.getResponseHeaders().add("Set-Cookie", game.cookieName() + "=" + session + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + game.remainingSeconds(session));
    }

    static void fields(JsonObject body, String... names) {
        if (!body.keySet().equals(new HashSet<>(Arrays.asList(names)))) {
            throw new GameService.GameError(400, "Expected exactly: " + String.join(", ", names) + ".");
        }
    }

    static String string(JsonObject body, String field) {
        if (body.get(field).getValueType() != JsonValue.ValueType.STRING) throw new GameService.GameError(400, field + " must be a string.");
        return body.getString(field);
    }

    private String cookie(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Cookie");
        String found = null, old = null;
        if (header != null) for (String value : header.split(";")) {
            String[] pair=value.trim().split("=",2);
            if(pair.length==2&&pair[0].equals("cluelab")&&!game.cookieName().equals("cluelab"))old=pair[1];
            if(pair.length==2&&pair[0].equals(game.cookieName())){
                String token=pair[1];if(token.startsWith("\"")&&token.endsWith("\""))token=token.substring(1,token.length()-1);
                if (found != null || !TopicBuilder.uuid(token)) throw new GameService.GameError(410, "Game recovery cookie is invalid or duplicated. Explicitly start a new game session.");
                found = token;
            }
        }
        if (found == null && old != null) throw new GameService.GameError(410, "This browser has an old memory-only game cookie. It cannot restore pre-upgrade scores. Explicitly start a new game session.");
        return found == null ? "" : found;
    }

    static JsonObject body(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
            throw new GameService.GameError(415, "Use application/json.");
        }
        byte[] bytes = readLimited(exchange.getRequestBody(), 4096);
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException error) { throw new GameService.GameError(400, "Use valid UTF-8 JSON."); }
        try { return PackCatalog.parse(text, "request"); }
        catch (IOException invalid) { throw new GameService.GameError(400, "Use one valid JSON object without duplicate properties."); }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > limit) throw new GameService.GameError(413, "Request too large.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void json(HttpExchange exchange, int status, JsonObject body) throws IOException {
        send(exchange, status, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override public void close() {
        http.stop(0);
        requests.shutdownNow();
        game.close();
        if (topics != null) try { topics.close(); } catch (IOException ignored) { System.err.println("Player catalog lease could not be closed cleanly."); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length <= 1) {
            GameConfig config = GameConfig.load(Paths.get(args.length == 0 ? "data/game.json" : args[0]));
            CollectionIndex indexes = new CollectionIndex(config);
            GameStore store = new GameStore(GameStore.configuredRoot(), PlayerReadiness.targetKey(config.configPath));
            run(config.port, new GameService(config.catalog, indexes::ask).durable(store), config);
            return;
        }
        if (args.length != 0 && args.length != 5 && args.length != 6) throw new IllegalArgumentException("Expected: [index-base index-type scorer questions-file port [easy-questions-file]]");
        String base = args.length == 0 ? "indexes/demo" : args[0];
        int type = args.length == 0 ? 1 : Integer.parseInt(args[1]);
        int scorer = args.length == 0 ? 4 : Integer.parseInt(args[2]);
        String questions = args.length == 0 ? "data/demo/questions.txt" : args[3];
        int port = args.length == 0 ? 8765 : Integer.parseInt(args[4]);
        if (type < 1 || type > 3 || scorer < 1 || scorer > 4 || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Index type 1-3, scorer 1-4, and port 1-65535 are required.");
        }
        java.nio.file.Path easyQuestions = args.length == 0 ? Paths.get("data/demo/easy-questions.txt")
                : args.length == 6 ? Paths.get(args[5]) : null;
        GameService game = new GameService(Paths.get(questions), easyQuestions, (category, clue) -> {
            try (Indexer indexer = new Indexer(base, type); QueryEngine engine = new QueryEngine(type, scorer, indexer)) {
                return engine.ask(category + " " + clue, 10);
            }
        });
        String target = GameStore.identity("legacy:" + Paths.get(questions).toRealPath() + ":" + (easyQuestions == null ? "" : easyQuestions.toRealPath())
                + ":" + Paths.get(base).toAbsolutePath().normalize() + ":" + type + ":" + scorer);
        game.durable(new GameStore(GameStore.configuredRoot(), target));
        run(port, game);
    }

    private static void run(int port, GameService game) throws Exception {
        run(port,game,null);
    }
    private static void run(int port, GameService game, GameConfig config) throws Exception {
        try (game; GameServer server = config == null ? new GameServer(port,game) : new GameServer(port,game,config,new TopicRequests(TopicRequests.DEFAULT_ROOT))) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            server.start();
            System.out.println("ClueLab local prototype: " + server.url());
            System.out.println("Private durable games; absolute two-hour sessions. No archive writes. Ctrl+C to stop.");
            new CountDownLatch(1).await();
        }
    }
}
