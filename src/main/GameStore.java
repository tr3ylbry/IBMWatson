package main;

import jakarta.json.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import static main.PackCatalog.require;

/** One local writer and an atomic, checksummed private snapshot. Not a portable save format. */
final class GameStore implements AutoCloseable {
    static final Path DEFAULT_ROOT = Path.of("drafts/local/player-games");
    static final int MAX_BYTES = 32 * 1024 * 1024;
    final Path root;
    final String target;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;
    interface Fault { void at(String phase) throws IOException; }
    private final Fault fault;

    static Path configuredRoot() throws IOException {
        String configured = System.getenv("CLUELAB_GAME_STORE_ROOT");
        return productionRoot(configured == null || configured.isBlank() ? DEFAULT_ROOT : Path.of(configured));
    }
    static Path productionRoot(Path selected) throws IOException {
        Path allowed = Path.of("drafts/local").toAbsolutePath().normalize();
        Path root = selected.toAbsolutePath().normalize();
        require(root.startsWith(allowed) && !root.equals(allowed), "Player saves must be below this clone's ignored drafts/local workspace, never archives, public resources, indexes or benchmarks");
        return selected;
    }

    GameStore(Path base, String target) throws IOException { this(base, target, phase -> {}); }
    GameStore(Path base, String target, Fault fault) throws IOException {
        require(target.matches("[a-f0-9]{64}"), "Game store requires a SHA-256 target identity");
        require(!base.toString().contains(".."), "Game storage must not contain parent traversal");
        Path normalized = base.toAbsolutePath().normalize();
        for (Path part : normalized) require(!part.toString().equals("target"), "Game storage must be outside target/");
        this.root = normalized.resolve(target); this.target = target; this.fault = fault;
        TopicBuilder.privateWorkspace(normalized); TopicBuilder.privateWorkspace(root);
        Path lockPath = root.resolve("writer.lock"); IntakeFiles.safe(lockPath);
        channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            Files.setPosixFilePermissions(lockPath, PosixFilePermissions.fromString("rw-------"));
            lock = channel.tryLock(); require(lock != null, "Another player process owns this game store");
            // A leftover staging file is never a committed save. Do not follow it, even to delete it.
            IntakeFiles.safe(root.resolve("pending.json"));
            Files.deleteIfExists(root.resolve("pending.json"));
        } catch (IOException | RuntimeException error) {
            channel.close(); throw new IOException("Cannot lock private game storage: " + root, error);
        }
    }

    JsonObject read() throws IOException {
        Path file = root.resolve("state.json"); IntakeFiles.safe(file);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        require(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")),
                "Private game state must be owner-only readable/writable (0600); recovery stopped without changing it");
        JsonObject envelope = parse(IntakeFiles.read(file, MAX_BYTES));
        PackCatalog.keys(envelope, "game save", "schemaVersion target sha256 state");
        require(PackCatalog.integer(envelope, "schemaVersion") == 1, "Unsupported game-save schemaVersion; keep the store and use a compatible runtime");
        require(target.equals(envelope.getString("target")), "Game save belongs to another catalog target");
        JsonObject state = envelope.getJsonObject("state");
        require(PackCatalog.sha256(IntakeFiles.json(state)).equals(envelope.getString("sha256")), "Game-save checksum mismatch; recovery stopped, not reset");
        return state;
    }

    void write(JsonObject state) throws IOException {
        require(!closed, "Game store is closed");
        byte[] bytes = IntakeFiles.json(Json.createObjectBuilder().add("schemaVersion", 1).add("target", target)
                .add("sha256", PackCatalog.sha256(IntakeFiles.json(state))).add("state", state).build());
        require(bytes.length <= MAX_BYTES, "Private game-save capacity reached (32 MiB); no state was acknowledged");
        Path file = root.resolve("state.json"), stage = root.resolve("pending.json");
        IntakeFiles.safe(file); IntakeFiles.safe(stage); fault.at("before-write");
        try {
            try (FileChannel out = FileChannel.open(stage, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                Files.setPosixFilePermissions(stage, PosixFilePermissions.fromString("rw-------"));
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) out.write(buffer);
                fault.at("before-force"); out.force(true);
            }
            fault.at("before-replace");
            Files.move(stage, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            fault.at("after-replace");
        } finally { Files.deleteIfExists(stage); }
    }

    private static JsonObject parse(byte[] bytes) throws IOException {
        String text = IntakeFiles.utf8(bytes);
        // The bounded snapshot may exceed the smaller admin-artifact node limit.
        try (var parser = Json.createParser(new java.io.StringReader(text))) {
            int depth = 0, nodes = 0;
            while (parser.hasNext()) {
                var event = parser.next(); require(++nodes <= 2_000_000, "Game save has too many JSON nodes");
                if (event == jakarta.json.stream.JsonParser.Event.START_OBJECT || event == jakarta.json.stream.JsonParser.Event.START_ARRAY)
                    require(++depth <= 32, "Game-save nesting exceeds 32");
                if (event == jakarta.json.stream.JsonParser.Event.END_OBJECT || event == jakarta.json.stream.JsonParser.Event.END_ARRAY) depth--;
            }
            return PackCatalog.parse(text, "private game save");
        } catch (RuntimeException error) { throw new IOException("Malformed private game save; recovery stopped, not reset", error); }
    }

    static String identity(String secret) {
        return PackCatalog.sha256(secret.getBytes(StandardCharsets.UTF_8));
    }
    @Override public void close() throws IOException { if (!closed) { closed = true; channel.close(); } }
}
