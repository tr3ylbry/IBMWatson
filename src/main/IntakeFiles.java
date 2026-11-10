package main;

import jakarta.json.*;
import jakarta.json.stream.JsonParser;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static main.PackCatalog.require;

/** Small immutable admin artifacts; never writes into the active catalog. */
final class IntakeFiles {
    private IntakeFiles() {}
    static byte[] json(JsonObject object) { return (object.toString() + "\n").getBytes(StandardCharsets.UTF_8); }
    static String utf8(byte[] bytes) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException error) { throw new IOException("Malformed UTF-8", error); }
    }
    static byte[] read(Path path, int maximum) throws IOException {
        safe(path);
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) <= maximum,
                path + ": missing/nonregular/oversized input");
        try (InputStream in = Files.newInputStream(path)) {
            byte[] bytes = in.readNBytes(maximum + 1);
            require(bytes.length <= maximum, path + ": input grew beyond limit"); return bytes;
        }
    }
    static JsonObject read(Path path) throws IOException {
        return parse(utf8(read(path, 4 * 1024 * 1024)), path.toString());
    }
    static JsonObject parse(String text, String context) throws IOException {
        try (JsonParser parser = Json.createParser(new StringReader(text))) {
            int depth = 0, nodes = 0;
            while (parser.hasNext()) {
                JsonParser.Event event = parser.next(); require(++nodes <= 100000, context + ": too many JSON nodes");
                if (event == JsonParser.Event.START_ARRAY || event == JsonParser.Event.START_OBJECT) require(++depth <= 32, context + ": JSON nesting exceeds 32");
                if (event == JsonParser.Event.END_ARRAY || event == JsonParser.Event.END_OBJECT) depth--;
            }
        } catch (JsonException error) { throw new IOException(context + ": invalid JSON", error); }
        return PackCatalog.parse(text, context);
    }
    static void schema(JsonObject row, String allowed) throws IOException {
        PackCatalog.keys(row, "intake schema", "schemaVersion " + allowed);
        require(PackCatalog.integer(row, "schemaVersion") == 1, "Unsupported intake schemaVersion; supported: 1");
    }
    static void safe(Path path) throws IOException {
        Path current = path.toAbsolutePath().normalize();
        while (current != null) {
            require(!Files.isSymbolicLink(current), "Symlink not allowed in admin paths: " + current);
            current = current.getParent();
        }
    }
    static String slug(JsonObject row, String key) throws IOException {
        String value = PackCatalog.string(row, key, 100);
        require(value.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"), key + ": expected stable lowercase slug"); return value;
    }
    static String version(JsonObject row) throws IOException {
        String value = PackCatalog.string(row, "version", 40);
        require(value.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "version: expected major.minor.patch"); return value;
    }
    static void publish(Path destination, Map<String, byte[]> files, WikiApi.Budget budget) throws IOException {
        Path output = destination.toAbsolutePath().normalize(); safe(output);
        require(output.getParent() != null, "An output directory is required");
        Files.createDirectories(output.getParent());
        Path lock = output.resolveSibling("." + output.getFileName() + ".lock"); safe(lock);
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock held = channel.tryLock()) {
            require(held != null, "Another exporter owns " + output);
            budget.check();
            if (Files.exists(output)) {
                Map<String, byte[]> previous = tree(output);
                require(previous.keySet().equals(files.keySet()), "Version/output conflict at " + output + "; use a new version and directory");
                for (String name : files.keySet()) require(Arrays.equals(files.get(name), previous.get(name)),
                        "Version/output conflict at " + name + "; published bytes cannot be overwritten");
                return;
            }
            Path stage = Files.createTempDirectory(output.getParent(), "." + output.getFileName() + "-staging-");
            try {
                for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                    budget.check(); Path file = stage.resolve(entry.getKey()).normalize();
                    require(file.startsWith(stage) && !Path.of(entry.getKey()).isAbsolute(), "Export path escape: " + entry.getKey());
                    Files.createDirectories(file.getParent()); Files.write(file, entry.getValue(), StandardOpenOption.CREATE_NEW);
                }
                budget.check();
                Files.move(stage, output, StandardCopyOption.ATOMIC_MOVE);
            } finally { removeStage(stage); }
        } catch (OverlappingFileLockException error) { throw new IOException("Another exporter owns " + output, error); }
    }
    static Map<String, byte[]> tree(Path root) throws IOException {
        safe(root); Map<String, byte[]> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.toList()) {
                safe(file);
                if (Files.isDirectory(file)) continue;
                require(files.size() < 256, "Too many artifact files");
                files.put(root.relativize(file).toString().replace(File.separatorChar, '/'), read(file, 4 * 1024 * 1024));
            }
        }
        return files;
    }
    private static void removeStage(Path stage) throws IOException {
        if (!Files.exists(stage)) return;
        try (var paths = Files.walk(stage)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
