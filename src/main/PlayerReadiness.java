package main;

import jakarta.json.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static main.PackCatalog.*;

/** Local liveness lease and loaded identities; historical activation receipts are never readiness evidence. */
final class PlayerReadiness implements AutoCloseable {
    final GameConfig loaded;
    final String key, boot = UUID.randomUUID().toString();
    private final Path root;
    private final FileChannel channel;
    private final FileLock lease;
    PlayerReadiness(Path root, GameConfig config) throws IOException {
        this.root = root; this.loaded = config; this.key = targetKey(config.configPath);
        TopicBuilder.privateWorkspace(root); Files.createDirectories(root.resolve("runtime"));
        channel = FileChannel.open(root.resolve("runtime/"+key+".lease"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lease = TopicRequests.locked(root.resolve("runtime.lock"), () -> {
                FileLock held; try { held = channel.tryLock(); } catch (OverlappingFileLockException e) { throw new IOException("A player server already owns this catalog target", e); }
                require(held != null, "A player server already owns this catalog target");
                try { write(false); return held; } catch (IOException e) { held.release(); throw e; }
            });
        } catch (IOException e) { channel.close(); throw e; }
    }
    static String targetKey(Path config) throws IOException { return sha256(config.toRealPath().toString().getBytes(StandardCharsets.UTF_8)); }
    private void write(boolean active) throws IOException {
        JsonArrayBuilder bindings = Json.createArrayBuilder(); for (PackCatalog.Pack p : loaded.catalog.topics.values()) for (String d : loaded.catalog.difficulties.keySet()) bindings.add(binding(p, d));
        JsonObject identity = Json.createObjectBuilder().add("schemaVersion", 1).add("boot", boot).add("active", active).add("configSha256", loaded.configSha256)
                .add("catalogSha256", loaded.catalogSha256).add("runtime", RuntimeInfo.indexNamespace()).add("bindings", bindings).build();
        PackActivation.atomicSwitch(root.resolve("runtime/"+key+".json"), IntakeFiles.json(identity));
    }
    void started() throws IOException { TopicRequests.locked(root.resolve("runtime.lock"), () -> { write(true); return null; }); }
    private static JsonObject live(Path root, String key) throws IOException {
        return TopicRequests.locked(root.resolve("runtime.lock"), () -> {
            Path file = root.resolve("runtime/"+key+".json"), lock = root.resolve("runtime/"+key+".lease");
            if (!Files.exists(file) || !Files.exists(lock)) return null;
            boolean held = false;
            try (FileChannel c = FileChannel.open(lock, StandardOpenOption.WRITE)) {
                try { FileLock l = c.tryLock(); if (l != null) l.release(); else held = true; }
                catch (OverlappingFileLockException e) { held = true; }
            }
            if (!held) return null; JsonObject json = IntakeFiles.read(file); return json.getBoolean("active", false) ? json : null;
        });
    }
    static JsonObject binding(PackCatalog.Pack pack, String difficulty) {
        JsonObject body = Json.createObjectBuilder().add("topicId", pack.topicId).add("label", pack.label).add("packId", pack.id).add("packVersion", pack.version).add("packSha256", pack.digest)
                .add("collectionId", pack.collection.scope.id).add("collectionVersion", pack.collection.scope.version).add("collectionDigest", pack.collection.scope.digest)
                .add("difficulty", difficulty).add("eligible", pack.eligible(difficulty).size()).add("reviewStatus", pack.review.getString("status")).build();
        return Json.createObjectBuilder(body).add("selectionId", sha256(IntakeFiles.json(body))).build();
    }
    static JsonObject check(Path root, Path configFile, JsonObject selected) {
        try {
            GameConfig current = GameConfig.load(configFile); String key = targetKey(configFile); JsonObject runtime = live(root, key);
            if (runtime == null) return result(false, "player-not-running", "Start the player server for this catalog target; no automatic restart is performed.");
            if (!current.configSha256.equals(runtime.getString("configSha256")) || !current.catalogSha256.equals(runtime.getString("catalogSha256")) || !RuntimeInfo.indexNamespace().equals(runtime.getString("runtime")))
                return result(false, "restart-required", "Catalog/configuration differs from the running player. An operator must restart it; unexpired durable games keep their pinned content.");
            PackCatalog.Pack pack = current.catalog.topics.get(selected.getString("topicId"));
            if (pack == null || !binding(pack, selected.getString("difficulty")).equals(selected)) return result(false, "pack-not-active", "The exact chosen pack/source versions are not active on this target. A draft, rehearsal or old receipt is not enough.");
            if (!pack.reviewed || pack.eligible(selected.getString("difficulty")).size() < 5) return result(false, "insufficient-content", "This exact difficulty does not have five eligible clues; no substitution is made.");
            if (!runtime.getJsonArray("bindings").contains(selected)) return result(false, "restart-required", "The running player did not load this exact pack/source identity.");
            new CollectionIndex(current).verify(pack.collection);
            return result(true, "verified", "Running player, active catalog, requested difficulty and source index agree. Review status is not human approval.");
        } catch (IOException | RuntimeException e) { return result(false, "index-or-content-unavailable", "Catalog content or source indexes could not be verified. Operator attention is required; no fallback is used."); }
    }
    static JsonObject result(boolean ready, String code, String message) { return Json.createObjectBuilder().add("ready", ready).add("code", code).add("message", message).build(); }
    static boolean compatible(PackCatalog.Pack pack, String topic) {
        String label = TopicRequests.normalized(pack.label + " " + pack.topicId.replace('-', ' '));
        return Arrays.stream(TopicRequests.normalized(topic).split("[^\\p{L}\\p{N}]+", -1)).filter(s -> !s.isEmpty()).allMatch(word -> Arrays.asList(label.split("[^\\p{L}\\p{N}]+")).contains(word));
    }
    static JsonObject publicView(JsonObject row, JsonObject readiness, List<JsonObject> choices) {
        String phase = row.getString("phase"); boolean accepted = !row.isNull("offered") && row.getString("accepted").equals(row.getJsonObject("offered").getString("selectionId"));
        boolean ready = !TopicRequests.TERMINAL.contains(phase) && accepted && readiness.getBoolean("ready");
        JsonObjectBuilder out = Json.createObjectBuilder().add("id", row.getString("id")).add("topic", row.getString("topic")).add("difficulty", row.getString("difficulty"))
                .add("length", 5).add("state", ready ? "ready" : phase).add("message", row.getString("message")).add("expiresAt", row.getJsonNumber("expiresAt"))
                .add("readiness", readiness).add("choices", Json.createArrayBuilder(choices)).add("choiceAccepted", accepted);
        if (row.isNull("offered")) out.addNull("offered"); else out.add("offered", row.getJsonObject("offered")); return out.build();
    }
    @Override public void close() throws IOException {
        try { TopicRequests.locked(root.resolve("runtime.lock"), () -> { if (lease.isValid()) write(false); return null; }); }
        // Closing the channel releases its lease even if storage or the coordination lock fails.
        finally { channel.close(); }
    }
}
