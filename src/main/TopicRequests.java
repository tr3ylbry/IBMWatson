package main;

import jakarta.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import static main.PackCatalog.*;

/** Bounded private ledger shared by player and workshop processes. No content or retrieval inputs. */
final class TopicRequests {
    static final Path DEFAULT_ROOT = Path.of("drafts/local/topic-requests");
    static final long TTL = Duration.ofDays(7).toMillis(), RATE_WINDOW = Duration.ofMinutes(10).toMillis();
    static final int MAX_OWNERS = 32, MAX_REQUESTS = 128, MAX_PER_OWNER = 8, MAX_PENDING = 4, MAX_OPS = 32;
    static final Set<String> TERMINAL = Set.of("cancelled", "failed", "unavailable", "clarification");
    final Path root;
    private final Clock clock;
    interface IO<T> { T run() throws IOException; }
    interface Transaction<T> { T run(State state) throws IOException; }
    static final class State {
        final Map<String, JsonObject> owners = new LinkedHashMap<>(), requests = new LinkedHashMap<>();
        final List<Long> creations = new ArrayList<>();
    }
    TopicRequests(Path root) throws IOException { this(root, Clock.systemUTC()); }
    TopicRequests(Path root, Clock clock) throws IOException { this.root = root.toAbsolutePath().normalize(); this.clock = clock; TopicBuilder.privateWorkspace(this.root); }
    static synchronized <T> T locked(Path file, IO<T> operation) throws IOException {
        IntakeFiles.safe(file); Files.createDirectories(file.getParent()); long end = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            for (;;) {
                FileLock lock = null; try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) {}
                if (lock != null) try { return operation.run(); } finally { lock.release(); }
                if (System.nanoTime() >= end) throw new IOException("Local request storage is busy; retry the same intention");
                try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Request operation interrupted"); }
            }
        }
    }
    private <T> T transaction(Transaction<T> operation) throws IOException {
        return locked(root.resolve("ledger.lock"), () -> {
            State state = new State(); Path file = root.resolve("ledger.json");
            if (Files.exists(file)) {
                JsonObject json = IntakeFiles.read(file); IntakeFiles.schema(json, "owners requests creations");
                for (JsonValue value : array(json, "owners")) {
                    JsonObject row = object(value, "owner");
                    // Old memory-only Play bindings are no longer credentials on disk. They remain unrecoverable across boots.
                    JsonArrayBuilder ops = Json.createArrayBuilder();
                    for (JsonValue savedOperation : row.getJsonArray("ops")) {
                        JsonObject op = (JsonObject) savedOperation;
                        if ("play".equals(op.getJsonObject("payload").getString("action", "")) && TopicBuilder.uuid(op.getString("session", "")))
                            op = Json.createObjectBuilder(op).add("session", GameStore.identity(op.getString("session"))).build();
                        ops.add(op);
                    }
                    row = Json.createObjectBuilder(row).add("ops", ops).build();
                    if (row.getJsonNumber("expiresAt").longValue() > clock.millis()) state.owners.put(row.getString("id"), row);
                }
                for (JsonValue value : array(json, "requests")) { JsonObject row = object(value, "request"); if (state.owners.containsKey(row.getString("ownerId"))) state.requests.put(row.getString("id"), row); }
                for (JsonValue value : array(json, "creations")) { long time = ((JsonNumber)value).longValue(); if (time > clock.millis() - 60000) state.creations.add(time); }
            }
            require(state.owners.size() <= MAX_OWNERS && state.requests.size() <= MAX_REQUESTS, "Request storage exceeds its declared bounds");
            // Work on a fresh ledger; exceptions never replace its previous atomic snapshot.
            T result = operation.run(state);
            JsonObject json = Json.createObjectBuilder().add("schemaVersion", 1).add("owners", Json.createArrayBuilder(state.owners.values()))
                    .add("requests", Json.createArrayBuilder(state.requests.values())).add("creations", Json.createArrayBuilder(state.creations)).build();
            byte[] bytes = IntakeFiles.json(json); require(bytes.length <= 4 * 1024 * 1024, "Request storage capacity reached; wait for expiry or perform documented maintenance");
            if (!Files.exists(file) || !Arrays.equals(bytes, IntakeFiles.read(file, 4 * 1024 * 1024))) PackActivation.atomicSwitch(file, bytes);
            return result;
        });
    }
    String createOwner() throws IOException {
        String id = UUID.randomUUID().toString(), secret = TopicBuilderServer.secret();
        transaction(s -> {
            if (s.owners.size() >= MAX_OWNERS || s.creations.size() >= 8) throw new GameService.GameError(429, "Local request-owner capacity/rate reached; wait for expiry before trying again.");
            s.creations.add(clock.millis()); s.owners.put(id, Json.createObjectBuilder().add("id", id).add("secretHash", hash(secret)).add("createdAt", clock.millis()).add("expiresAt", clock.millis()+TTL)
                    .add("ops", Json.createArrayBuilder()).add("recent", Json.createArrayBuilder()).build()); return null;
        });
        return id + "." + secret;
    }
    private JsonObject owner(State s, String credential) {
        if (credential == null || !credential.matches("[a-f0-9-]{36}\\.[A-Za-z0-9_-]{43}")) throw unauthorized();
        String[] parts = credential.split("\\.", 2); JsonObject row = s.owners.get(parts[0]);
        if (row == null || !MessageDigest.isEqual(row.getString("secretHash").getBytes(StandardCharsets.US_ASCII), hash(parts[1]).getBytes(StandardCharsets.US_ASCII))) throw unauthorized(); return row;
    }
    private static GameService.GameError unauthorized() { return new GameService.GameError(401, "Request recovery identity is unavailable or expired. Use this browser's saved cookie, or explicitly start a new request identity."); }
    private JsonObject owned(State s, JsonObject owner, String id, String target) {
        JsonObject row = s.requests.get(id);
        if (row == null || !row.getString("ownerId").equals(owner.getString("id")) || !row.getString("targetKey").equals(target)) throw new GameService.GameError(404, "Topic request not found."); return row;
    }
    private static String hash(String text) { return sha256(text.getBytes(StandardCharsets.UTF_8)); }
    static String normalized(String text) { return Normalizer.normalize(text, Normalizer.Form.NFKC).trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }
    static String topic(String text) {
        if (text == null || text.isBlank() || text.length() > 120 || !text.equals(text.trim()) || !text.matches("[\\p{L}\\p{N} .,'():&+!?-]+")
                || text.matches("(?i)^(?:https?|ftp|file|mailto|javascript|data):.*")
                || text.codePoints().noneMatch(Character::isLetterOrDigit) || text.contains("..") || text.split("\\s+").length > 20)
            throw new GameService.GameError(400, "Use 1-120 characters of subject words (at most 20 words), not a URL, path or markup.");
        return text;
    }
    static void uuid(String id) { if (!TopicBuilder.uuid(id)) throw new GameService.GameError(400, "Use a canonical retry UUID."); }
    private JsonObject previous(JsonArray operations, String id, JsonObject payload) {
        uuid(id);
        for (JsonValue v : operations) { JsonObject row = (JsonObject)v; if (row.getString("id").equals(id)) {
            if (!row.getJsonObject("payload").equals(payload)) throw new GameService.GameError(409, "Retry identity belongs to different input. Recover the original request; do not change its payload."); return row;
        }} return null;
    }
    private JsonObject addOwnerOp(State s, JsonObject owner, String id, JsonObject payload, String result, JsonObject extra) {
        JsonArray ops = owner.getJsonArray("ops"); if (ops.size() >= MAX_OPS) throw new GameService.GameError(429, "This recovery identity has reached its 32 mutation limit; retained requests remain readable until expiry.");
        JsonObject entry = Json.createObjectBuilder(extra).add("id", id).add("payload", payload).add("resultId", result).build();
        s.owners.put(owner.getString("id"), Json.createObjectBuilder(owner).add("ops", Json.createArrayBuilder(ops).add(entry)).build()); return entry;
    }
    JsonObject submit(String credential, String target, String retry, String text, String difficulty) throws IOException {
        topic(text); if (!Set.of("easy", "standard", "hard").contains(difficulty)) throw new GameService.GameError(400, "Choose Easy, Standard or Hard; no fallback difficulty is used.");
        JsonObject payload = Json.createObjectBuilder().add("action", "submit").add("target", target).add("topic", text).add("difficulty", difficulty).build();
        return transaction(s -> {
            JsonObject owner = owner(s, credential), previous = previous(owner.getJsonArray("ops"), retry, payload);
            if (previous != null) return owned(s, owner, previous.getString("resultId"), target);
            List<JsonObject> mine = s.requests.values().stream().filter(r -> r.getString("ownerId").equals(owner.getString("id"))).toList();
            for (JsonObject row : mine) if (!TERMINAL.contains(row.getString("phase")) && row.getString("targetKey").equals(target) && normalized(row.getString("topic")).equals(normalized(text)) && row.getString("difficulty").equals(difficulty)) {
                addOwnerOp(s, owner, retry, payload, row.getString("id"), JsonValue.EMPTY_JSON_OBJECT); return row;
            }
            List<JsonValue> recent = owner.getJsonArray("recent").stream().filter(v -> ((JsonNumber)v).longValue() > clock.millis()-RATE_WINDOW).toList();
            if (mine.size() >= MAX_PER_OWNER || mine.stream().filter(r -> !TERMINAL.contains(r.getString("phase"))).count() >= MAX_PENDING || s.requests.size() >= MAX_REQUESTS || recent.size() >= 5)
                throw new GameService.GameError(429, "Local request limit reached: four pending/eight retained per identity and five new requests per ten minutes. Existing requests remain available.");
            String id = UUID.randomUUID().toString(); JsonObject row = Json.createObjectBuilder().add("id", id).add("ownerId", owner.getString("id")).add("targetKey", target).add("topic", text).add("difficulty", difficulty)
                    .add("phase", "requested").add("message", "Queued for manual operator preparation; no sources are fetched automatically.").add("createdAt", clock.millis()).add("expiresAt", owner.getJsonNumber("expiresAt"))
                    .addNull("offered").add("accepted", "").add("ops", Json.createArrayBuilder()).build();
            s.requests.put(id, row); addOwnerOp(s, owner, retry, payload, id, JsonValue.EMPTY_JSON_OBJECT);
            JsonObject updated = s.owners.get(owner.getString("id")); s.owners.put(owner.getString("id"), Json.createObjectBuilder(updated).add("recent", Json.createArrayBuilder(recent).add(clock.millis())).build()); return row;
        });
    }
    List<JsonObject> list(String credential, String target) throws IOException { return transaction(s -> { JsonObject owner = owner(s, credential); return s.requests.values().stream().filter(r -> r.getString("ownerId").equals(owner.getString("id")) && r.getString("targetKey").equals(target)).toList(); }); }
    JsonObject get(String credential, String target, String id) throws IOException { return transaction(s -> owned(s, owner(s, credential), id, target)); }
    JsonObject choose(String credential, String target, String id, String retry, JsonObject binding) throws IOException {
        JsonObject payload = Json.createObjectBuilder().add("action", "choose").add("request", id).add("binding", binding).build();
        return transaction(s -> {
            JsonObject owner = owner(s, credential), row = owned(s, owner, id, target);
            if (previous(owner.getJsonArray("ops"), retry, payload) != null) return row;
            if (TERMINAL.contains(row.getString("phase"))) throw new GameService.GameError(409, "This request is closed; it cannot be reopened by choosing a pack.");
            addOwnerOp(s, owner, retry, payload, id, JsonValue.EMPTY_JSON_OBJECT);
            row = Json.createObjectBuilder(row).add("offered", binding).add("accepted", binding.getString("selectionId")).add("phase", "awaiting-review").add("message", "Pack choice recorded. Readiness is checked against the running player, catalog and indexes.").build(); s.requests.put(id, row); return row;
        });
    }
    JsonObject cancel(String credential, String target, String id, String retry) throws IOException {
        JsonObject payload = Json.createObjectBuilder().add("action", "cancel").add("request", id).build();
        return transaction(s -> { JsonObject owner = owner(s, credential), row = owned(s, owner, id, target); if (previous(owner.getJsonArray("ops"), retry, payload) != null) return row;
            addOwnerOp(s, owner, retry, payload, id, JsonValue.EMPTY_JSON_OBJECT); row = Json.createObjectBuilder(row).add("phase", "cancelled").add("message", "Request cancelled. Shared content and any already-started game are unchanged.").build(); s.requests.put(id, row); return row; });
    }
    JsonObject operatorGet(String id, String target) throws IOException { return transaction(s -> { JsonObject r = s.requests.get(id); if (r == null || !r.getString("targetKey").equals(target)) throw new GameService.GameError(404, "Request is not owned by this catalog target."); return r; }); }
    List<JsonObject> inbox() throws IOException { return transaction(s -> List.copyOf(s.requests.values())); }
    JsonObject operator(String id, String target, String retry, String action, String message, JsonObject binding) throws IOException {
        if (!Set.of("begin", "awaiting-review", "offer", "failed", "unavailable", "clarification").contains(action)) throw new GameService.GameError(400, "Unsupported operator transition.");
        require(message != null && message.length() <= 300 && !message.matches(".*(?:https?://|/Users/|/private/|\\\\).*"), "Use a short public player message, not private notes, paths or URLs");
        JsonObject payload = Json.createObjectBuilder().add("action", action).add("message", message).add("binding", binding == null ? JsonValue.NULL : binding).build();
        return transaction(s -> {
            JsonObject row = s.requests.get(id); if (row == null || !row.getString("targetKey").equals(target)) throw new GameService.GameError(404, "Request target not found.");
            JsonArray ops = row.getJsonArray("ops"); if (previous(ops, retry, payload) != null) return row;
            require(ops.size() < MAX_OPS, "Request operator-transition limit reached");
            JsonArrayBuilder recorded = Json.createArrayBuilder(ops).add(Json.createObjectBuilder().add("id", retry).add("payload", payload));
            if (TERMINAL.contains(row.getString("phase"))) {
                JsonObject closed = Json.createObjectBuilder(row).add("ops", recorded).build(); s.requests.put(id, closed); return closed;
            }
            if (action.equals("awaiting-review") || action.equals("offer")) require(Set.of("preparing", "awaiting-review").contains(row.getString("phase")), "Actual operator preparation must begin first");
            String phase = action.equals("begin") ? "preparing" : action.equals("offer") ? "awaiting-review" : action;
            JsonObjectBuilder next = Json.createObjectBuilder(row).add("phase", phase).add("message", message).add("ops", recorded);
            if (binding != null) { require(binding.getString("difficulty").equals(row.getString("difficulty")), "Candidate difficulty differs from the request"); next.add("offered", binding); if (!row.getString("accepted").equals(binding.getString("selectionId"))) next.add("accepted", ""); }
            JsonObject result = next.build(); s.requests.put(id, result); return result;
        });
    }
    interface Play { JsonObject run(JsonObject request, JsonObject intention) throws IOException; }
    JsonObject play(String credential, String target, String id, String retry, String selection, boolean replay, String boot, String session, Play action) throws IOException {
        JsonObject payload = Json.createObjectBuilder().add("action", "play").add("request", id).add("selection", selection).add("replay", replay).build();
        // Phase one survives a crash before, during, or after the separate game-store commit.
        transaction(s -> { JsonObject owner = owner(s, credential), row = owned(s, owner, id, target), old = previous(owner.getJsonArray("ops"), retry, payload);
            if (old != null) return null;
            if (TERMINAL.contains(row.getString("phase")) || !row.getString("accepted").equals(selection)) throw new GameService.GameError(409, "Select an offered pack on an open request before playing.");
            addOwnerOp(s, owner, retry, payload, id, Json.createObjectBuilder().add("boot", boot).add("session", session).build()); return null;
        });
        // Cancellation is ordered against the actual launch under the request-ledger lock.
        return transaction(s -> {
            JsonObject owner = owner(s, credential), row = owned(s, owner, id, target);
            return action.run(row, previous(owner.getJsonArray("ops"), retry, payload));
        });
    }
}
