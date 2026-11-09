package main;

import com.sun.net.httpserver.*;
import jakarta.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static main.PackCatalog.*;

/** Explicitly started private admin origin. It shares no cookies, assets or routes with GameServer. */
public final class TopicBuilderServer implements AutoCloseable {
    record Session(String csrf, long expires) {}
    private final HttpServer http;
    private final TopicBuilder builder;
    private final String authority, code;
    private final Map<String, Session> sessions = new HashMap<>();
    private final ThreadPoolExecutor requests = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), GameService.daemonThreads("cluelab-admin-http"));
    private int failedLogins;
    private long loginWindow = System.currentTimeMillis();
    static final int MAX_BODY = 2 * 1024 * 1024;
    private static final String COOKIE = "cluelab_operator";

    TopicBuilderServer(int port, TopicBuilder builder, String code) throws IOException {
        this.builder = builder; this.code = code;
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        authority = "127.0.0.1:" + http.getAddress().getPort(); http.createContext("/", this::handle); http.setExecutor(requests);
    }
    String url() { return "http://" + authority; }
    void start() { http.start(); }
    static String secret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean equal(String a, String b) { return a != null && b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static final class HttpError extends IOException {
        final int status; HttpError(int status, String message) { super(message); this.status = status; }
    }
    private void handle(HttpExchange x) throws IOException {
        try {
            x.getResponseHeaders().set("Cache-Control", "no-store"); x.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            x.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'");
            if (!authority.equals(single(x, "Host"))) throw new HttpError(403, "Use the printed 127.0.0.1 admin address");
            String origin = single(x, "Origin"), site = single(x, "Sec-Fetch-Site");
            if (origin != null && !origin.equals(url()) || site != null && !Set.of("same-origin", "none").contains(site)) throw new HttpError(403, "Cross-origin administration is forbidden");
            if (x.getRequestURI().getRawQuery() != null) throw new HttpError(400, "Query strings are not supported");
            String path = x.getRequestURI().getRawPath(), method = x.getRequestMethod();
            if (path.startsWith("/api/")) {
                if (!Set.of("GET", "POST").contains(method)) throw new HttpError(405, "Method not allowed");
                if (method.equals("POST") && !url().equals(origin)) throw new HttpError(403, "An exact same-origin request is required");
                if (path.equals("/api/login") && method.equals("POST")) { login(x, body(x)); return; }
                Session session = session(x);
                if (method.equals("POST") && !equal(session.csrf, single(x, "X-ClueLab-CSRF"))) throw new HttpError(403, "Operator CSRF token is missing or invalid");
                if (path.equals("/api/session") && method.equals("GET")) { json(x, 200, Json.createObjectBuilder().add("csrf", session.csrf).add("catalog", builder.catalog()).build()); return; }
                if (path.equals("/api/jobs") && method.equals("POST")) { json(x, 202, builder.submit(body(x))); return; }
                if (path.equals("/api/player-requests") && method.equals("GET")) { json(x,200,builder.inbox()); return; }
                if (path.equals("/api/player-requests/transition") && method.equals("POST")) { json(x,200,builder.requestTransition(body(x))); return; }
                if (path.matches("/api/jobs/[a-f0-9-]{36}") && method.equals("GET")) { json(x, 200, builder.status(path.substring(10))); return; }
                if (path.matches("/api/jobs/[a-f0-9-]{36}/cancel") && method.equals("POST")) { JsonObject b = body(x); keys(b, "cancel", ""); json(x, 200, builder.cancel(path.substring(10, path.length() - 7))); return; }
                if (path.matches("/api/artifacts/[a-z0-9-]{1,100}") && method.equals("GET")) { json(x, 200, builder.open(path.substring(15))); return; }
                throw new HttpError(404, "No such admin endpoint");
            }
            if (!method.equals("GET")) throw new HttpError(405, "Method not allowed");
            String file = switch (path) { case "/" -> "index.html"; case "/builder.js" -> "builder.js"; case "/builder.css" -> "builder.css"; default -> null; };
            if (file == null) throw new HttpError(404, "Not found");
            try (InputStream in = TopicBuilderServer.class.getResourceAsStream("/admin/" + file)) {
                if (in == null) throw new IOException("Missing admin asset");
                byte[] bytes = in.readAllBytes(); x.getResponseHeaders().set("Content-Type", file.endsWith(".js") ? "text/javascript; charset=utf-8" : file.endsWith(".css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
                x.sendResponseHeaders(200, bytes.length); x.getResponseBody().write(bytes);
            }
        } catch (HttpError e) { json(x, e.status, Json.createObjectBuilder().add("error", e.getMessage()).build()); }
        catch (IOException | IllegalArgumentException | JsonException e) {
            String message = e.getMessage() == null ? "Invalid managed request" : e.getMessage();
            message = message.replace(Path.of("").toAbsolutePath().toString(), "[managed workspace]");
            json(x, 400, Json.createObjectBuilder().add("error", message.substring(0, Math.min(1500, message.length()))).build());
        } catch (RuntimeException e) { json(x, 500, Json.createObjectBuilder().add("error", "Admin request failed; reconnect and inspect job state before retrying").build()); }
        finally { x.close(); }
    }
    private static String single(HttpExchange x, String name) throws HttpError {
        List<String> values = x.getRequestHeaders().get(name); if (values == null) return null;
        if (values.size() != 1) throw new HttpError(400, "Duplicate request header"); return values.get(0);
    }
    private synchronized Session session(HttpExchange x) throws IOException {
        String cookie = single(x, "Cookie"), token = null;
        if (cookie != null) for (String part : cookie.split(";")) {
            String[] pair = part.trim().split("=", 2); if (pair.length == 2 && pair[0].equals(COOKIE)) { if (token != null) throw new HttpError(401, "Duplicate operator cookie"); token = pair[1]; if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) token = token.substring(1, token.length()-1); }
        }
        sessions.values().removeIf(s -> s.expires <= System.currentTimeMillis()); Session s = sessions.get(token);
        if (s == null) throw new HttpError(401, "Unlock the private workbench with the access code printed by the admin server"); return s;
    }
    private synchronized void login(HttpExchange x, JsonObject body) throws IOException {
        keys(body, "operator login", "code");
        if (System.currentTimeMillis() - loginWindow > 60000) { failedLogins = 0; loginWindow = System.currentTimeMillis(); }
        if (failedLogins >= 10) throw new HttpError(429, "Too many unlock attempts; wait one minute");
        if (!equal(code, string(body, "code", 200))) { failedLogins++; throw new HttpError(401, "Access code did not match"); }
        sessions.values().removeIf(s -> s.expires <= System.currentTimeMillis()); if (sessions.size() >= 8) throw new HttpError(429, "Operator session limit reached; restart or wait for expiry");
        String token = secret(); Session session = new Session(secret(), System.currentTimeMillis() + Duration.ofHours(2).toMillis()); sessions.put(token, session);
        x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + token + "; HttpOnly; SameSite=Strict; Path=/api/; Max-Age=7200");
        json(x, 200, Json.createObjectBuilder().add("csrf", session.csrf).build());
    }
    private static JsonObject body(HttpExchange x) throws IOException {
        String contentType = single(x, "Content-Type"); if (contentType == null || !contentType.matches("application/json(?:;\\s*charset=utf-8)?")) throw new HttpError(415, "Use a JSON request");
        byte[] bytes = x.getRequestBody().readNBytes(MAX_BODY + 1); if (bytes.length > MAX_BODY) throw new HttpError(413, "Import exceeds the 2 MiB request limit");
        return IntakeFiles.parse(IntakeFiles.utf8(bytes), "admin request");
    }
    private static void json(HttpExchange x, int status, JsonObject data) throws IOException {
        byte[] bytes = IntakeFiles.json(data); x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); x.sendResponseHeaders(status, bytes.length); x.getResponseBody().write(bytes);
    }
    @Override public void close() throws IOException { http.stop(1); requests.shutdownNow(); builder.close(); }

    /** Operator-only launch settings; browser requests still cannot supply filesystem paths. */
    record Launch(Path root, int port, int playerPort) {
        static final Path DEFAULT_ROOT = Path.of("drafts/local/topic-builder");
        static Launch from(Map<String, String> env) throws IOException {
            String configured = env.getOrDefault("CLUELAB_WORKSHOP_ROOT", "");
            Path root = configured.isBlank() ? DEFAULT_ROOT : Path.of(configured);
            Path allowed = Path.of("drafts/local").toAbsolutePath().normalize(), absolute = root.toAbsolutePath().normalize();
            require(!root.toString().contains("..") && absolute.startsWith(allowed) && !absolute.equals(allowed), "Workshop root must be a private subdirectory below this clone's ignored drafts/local workspace");
            IntakeFiles.safe(root);
            int port = port(env, "CLUELAB_WORKSHOP_PORT", 8766), player = port(env, "CLUELAB_ISOLATED_PLAYER_PORT", 8787);
            require(port != player, "Workshop and isolated player require different ports");
            return new Launch(root, port, player);
        }
        private static int port(Map<String, String> env, String key, int fallback) throws IOException {
            String value = env.getOrDefault(key, "");
            try { int port = value.isBlank() ? fallback : Integer.parseInt(value); require(port > 0 && port <= 65535, key + ": use a port from 1 to 65535"); return port; }
            catch (NumberFormatException error) { throw new IOException(key + ": use an integer port from 1 to 65535", error); }
        }
        boolean defaults() { return root.toAbsolutePath().normalize().equals(DEFAULT_ROOT.toAbsolutePath().normalize()); }
        Path workspace() { return defaults() ? Path.of("drafts") : root; }
        void verifyExisting(GameConfig config, Map<String, String> env) throws IOException {
            if (!env.getOrDefault("CLUELAB_ISOLATED_PLAYER_PORT", "").isBlank())
                require(config.port == playerPort, "Existing isolated configuration has a different port; it was not changed. Use its recorded port or a fresh workspace");
            require(config.port != port, "Workshop port conflicts with the existing isolated player configuration");
        }
    }
    public static void main(String[] args) throws Exception {
        require(args.length == 0, "No paths or commands are accepted. Launch TopicBuilderServer from this repository root.");
        Launch settings = Launch.from(System.getenv());
        Path root = settings.root(), config = root.resolve("isolated/game.json");
        TopicBuilder.privateWorkspace(root);
        if (!Files.exists(config)) PackActivation.sandbox(Path.of("data/game.json"), config.getParent(), settings.defaults() ? Path.of("indexes/topic-builder") : root.resolve("indexes"), settings.playerPort());
        settings.verifyExisting(GameConfig.load(config), System.getenv());
        TopicBuilder builder = new TopicBuilder(root, settings.workspace(), Map.of("isolated", config, "normal", Path.of("data/game.json")), TopicBuilder.examples(),
                () -> new WikiApi(new WikipediaTransport(), settings.defaults() ? Path.of("corpus/source-intake-cache") : root.resolve("source-cache"), Clock.systemUTC(),
                        System.getenv().getOrDefault("CLUELAB_WIKI_USER_AGENT", "ClueLabSourceIntake/1.0 (https://github.com/tr3ylbry/IBMWatson)"), 1000),new TopicRequests(TopicRequests.DEFAULT_ROOT));
        String code = secret(); TopicBuilderServer server;
        try { server = new TopicBuilderServer(settings.port(), builder, code); } catch (IOException error) { builder.close(); throw error; }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (IOException ignored) {} }));
        server.start(); System.out.println("Private Topic Builder: " + server.url() + "/\nOperator access code (keep private): " + code
                + "\nDefault target: isolated workshop. Normal game is never restarted automatically.");
        new CountDownLatch(1).await();
    }
}
