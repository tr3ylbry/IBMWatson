package main;

import jakarta.json.*;
import jakarta.json.stream.JsonParser;
import java.io.*;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/** Validated, immutable content snapshot. Private clue data is never a transport DTO. */
public final class PackCatalog {
    public static final int SCHEMA = 1;

    /** The only content selector passed into retrieval; contains no clue or answer metadata. */
    public static final class Scope {
        public final String id, version, digest;
        Scope(String id, String version, String digest) { this.id = id; this.version = version; this.digest = digest; }
        public String key() { return id + "@" + version; }
    }

    public static final class Source {
        public final String id, title, category, text, url, referenceTitle, publisher, attribution;
        public final String snapshotKind, snapshotSha256, capturedOn, revisionId, revisionNote;
        Source(JsonObject json, String text) throws IOException {
            keys(json, "source", "id title category url referenceTitle publisher attribution snapshot snapshotKind snapshotSha256 capturedOn revisionId revisionNote");
            id = id(json, "id"); title = string(json, "title", 200); category = string(json, "category", 200);
            require(!title.contains("|") && !title.contains("[") && !title.contains("]"), id + ": invalid article title");
            url = https(json, "url"); referenceTitle = string(json, "referenceTitle", 300);
            publisher = string(json, "publisher", 200); attribution = string(json, "attribution", 2000);
            snapshotKind = choice(json, "snapshotKind", "excerpt", "legacy-adaptation");
            snapshotSha256 = string(json, "snapshotSha256", 64);
            require(snapshotSha256.matches("[a-f0-9]{64}"), id + ": snapshotSha256 must be lowercase SHA-256");
            capturedOn = date(json, "capturedOn");
            require(json.containsKey("revisionId"), id + ": revisionId must be present (null if unknown)");
            revisionId = json.isNull("revisionId") ? null : string(json, "revisionId", 300);
            revisionNote = string(json, "revisionNote", 1000);
            this.text = text;
        }
        public JsonObject evidence(String passage, String kind) {
            JsonObjectBuilder out = Json.createObjectBuilder().add("sourceId", id).add("title", title)
                    .add("text", passage).add("kind", kind).add("source", referenceTitle)
                    .add("articleUrl", url).add("publisher", publisher).add("attribution", attribution)
                    .add("snapshotKind", snapshotKind).add("snapshotSha256", snapshotSha256)
                    .add("capturedOn", capturedOn).add("revisionNote", revisionNote);
            if (revisionId == null) out.addNull("revisionId"); else out.add("revisionId", revisionId);
            return out.build();
        }
    }

    public static final class Collection {
        public final Scope scope;
        public final String description, language;
        public final Map<String, Source> sources;
        private final Map<String, Source> titles;
        Collection(String id, String version, String digest, String description, String language, Map<String, Source> sources) {
            scope = new Scope(id, version, digest); this.description = description; this.language = language;
            this.sources = Collections.unmodifiableMap(new LinkedHashMap<>(sources));
            Map<String, Source> titles = new HashMap<>();
            sources.values().forEach(source -> titles.put(Evaluation.normalize(source.title), source));
            this.titles = Collections.unmodifiableMap(titles);
        }
        public Source title(String title) { return titles.get(Evaluation.normalize(title)); }
    }

    public static final class Clue {
        public final String id, topicId, subtopicId, category, text, hint, difficulty, answerType, answerSourceId;
        public final List<String> aliases;
        public final Source supportingSource;
        public final int passageStart, passageEnd;
        Clue(JsonObject json, String topic, Set<String> subtopics, Set<String> difficulties, Collection collection) throws IOException {
            keys(json, "clue", "id topicId subtopicId category text hint difficulty answerType answerSourceId aliases support");
            id = id(json, "id"); topicId = id(json, "topicId"); subtopicId = id(json, "subtopicId");
            require(topic.equals(topicId), id + ": topicId must match pack topic " + topic);
            require(subtopics.contains(subtopicId), id + ": unknown subtopicId " + subtopicId);
            category = string(json, "category", 200); text = string(json, "text", 3000);
            hint = !json.containsKey("hint") || json.isNull("hint") ? "" : string(json, "hint", 600);
            difficulty = id(json, "difficulty");
            require(difficulties.contains(difficulty), id + ": difficulty is not declared in the catalog: " + difficulty);
            answerType = choice(json, "answerType", "article-title");
            answerSourceId = id(json, "answerSourceId");
            Source answer = collection.sources.get(answerSourceId);
            require(answer != null, id + ": missing answer source " + answerSourceId + " in " + collection.scope.key());
            List<String> aliases = new ArrayList<>(); Set<String> normalized = new HashSet<>();
            for (JsonValue value : array(json, "aliases")) {
                require(value.getValueType() == JsonValue.ValueType.STRING, id + ": aliases must be strings");
                String alias = ((JsonString) value).getString();
                require(validText(alias, 200) && !alias.contains("|") && normalized.add(Evaluation.normalize(alias)),
                        id + ": aliases must be nonempty, unique after normalization, at most 200 characters, and contain no pipe/control characters");
                aliases.add(alias);
            }
            require(!aliases.isEmpty() && normalized.contains(Evaluation.normalize(answer.title)),
                    id + ": aliases must include the searchable article title: " + answer.title);
            this.aliases = Collections.unmodifiableList(aliases);
            JsonObject support = object(json, "support"); keys(support, id + ".support", "sourceId start end");
            supportingSource = collection.sources.get(id(support, "sourceId"));
            require(supportingSource != null, id + ": support references a missing source");
            passageStart = integer(support, "start"); passageEnd = integer(support, "end");
            require(passageStart >= 0 && passageEnd > passageStart && passageEnd <= supportingSource.text.length(),
                    id + ": supporting passage offsets are outside the snapshot");
            require(!supportingPassage().trim().isEmpty(), id + ": supporting passage is empty");
            require(retrievalText().length() + category.length() + 1 <= 4096, id + ": retrieval input exceeds 4096 characters");
        }
        public String retrievalText() { return text + (hint.isEmpty() ? "" : " Hint: " + hint); }
        public String answers() { return String.join("|", aliases); }
        public String supportingPassage() { return supportingSource.text.substring(passageStart, passageEnd); }
    }

    public static final class Pack {
        public final String id, version, topicId, label, description, language, digest;
        public final Collection collection;
        public final List<Clue> clues;
        public final JsonObject review;
        public final boolean reviewed;
        Pack(String id, String version, String topicId, String label, String description, String language,
             String digest, Collection collection, List<Clue> clues, JsonObject review) {
            this.id = id; this.version = version; this.topicId = topicId; this.label = label; this.description = description;
            this.language = language; this.digest = digest; this.collection = collection;
            this.clues = Collections.unmodifiableList(new ArrayList<>(clues)); this.review = review;
            reviewed = !"draft".equals(review.getString("status"));
        }
        public List<Clue> eligible(String difficulty) {
            List<Clue> result = new ArrayList<>();
            if (reviewed) for (Clue clue : clues) if (clue.difficulty.equals(difficulty)) result.add(clue);
            return result;
        }
    }

    public final String id, version, defaultTopic, defaultDifficulty;
    public final Map<String, String> difficulties;
    public final Map<String, Collection> collections;
    public final Map<String, Pack> topics;

    private PackCatalog(String id, String version, String defaultTopic, String defaultDifficulty,
                        Map<String, String> difficulties, Map<String, Collection> collections, Map<String, Pack> topics) {
        this.id = id; this.version = version; this.defaultTopic = defaultTopic; this.defaultDifficulty = defaultDifficulty;
        this.difficulties = Collections.unmodifiableMap(difficulties);
        this.collections = Collections.unmodifiableMap(collections); this.topics = Collections.unmodifiableMap(topics);
    }

    public static PackCatalog load(Path path) throws IOException {
        return load(path, false);
    }

    /** Offline validation only: a draft-only catalog must never be accepted as a game configuration. */
    public static PackCatalog loadDraft(Path path) throws IOException {
        return load(path, true);
    }

    private static PackCatalog load(Path path, boolean draftOnly) throws IOException {
        Path root = path.toAbsolutePath().getParent().toRealPath();
        try {
            JsonObject catalog = read(path); header(catalog);
            keys(catalog, "catalog", "schemaVersion id version defaults difficulties collections packs topics");
            Map<String, String> difficulties = new LinkedHashMap<>();
            for (JsonValue value : array(catalog, "difficulties")) {
                JsonObject row = object(value, "difficulty"); keys(row, "difficulty", "id label");
                unique(difficulties, id(row, "id"), string(row, "label", 100), "difficulty");
            }
            require(!difficulties.isEmpty(), "catalog: declare at least one difficulty");
            Map<String, Collection> collections = new LinkedHashMap<>();
            Set<String> sourceIds = new HashSet<>();
            for (JsonValue value : array(catalog, "collections")) {
                JsonObject ref = object(value, "collection reference");
                Path file = reference(root, ref); JsonObject row = read(file); header(row); match(ref, row);
                keys(row, "collection", "schemaVersion id version language description sources");
                Map<String, Source> sources = new LinkedHashMap<>(); Set<String> titles = new HashSet<>();
                StringBuilder fingerprint = new StringBuilder(sha256(Files.readAllBytes(file)));
                for (JsonValue item : array(row, "sources")) {
                    JsonObject source = object(item, "source"); Path snapshot = resolve(root, string(source, "snapshot", 300));
                    byte[] bytes = bytes(snapshot, 256 * 1024); String text = utf8(bytes, snapshot.toString());
                    require(!text.trim().isEmpty() && text.indexOf('\0') < 0, snapshot + ": empty/invalid snapshot");
                    Source loaded = new Source(source, text);
                    require(loaded.snapshotSha256.equals(sha256(bytes)), snapshot + ": snapshot SHA-256 mismatch; review and version changed sources");
                    require(sourceIds.add(loaded.id), "Duplicate source ID: " + loaded.id);
                    require(titles.add(Evaluation.normalize(loaded.title)), "Duplicate searchable title: " + loaded.title);
                    sources.put(loaded.id, loaded); fingerprint.append(loaded.id).append(loaded.snapshotSha256);
                }
                require(!sources.isEmpty(), file + ": collection has no searchable sources");
                Collection collection = new Collection(id(row, "id"), version(row), sha256(fingerprint.toString().getBytes(StandardCharsets.UTF_8)),
                        string(row, "description", 2000), language(row), sources);
                unique(collections, collection.scope.id, collection, "collection");
            }
            Map<String, Pack> packs = new LinkedHashMap<>(); Set<String> clueIds = new HashSet<>();
            for (JsonValue value : array(catalog, "packs")) {
                JsonObject ref = object(value, "pack reference"); Path file = reference(root, ref);
                JsonObject row = read(file); header(row); match(ref, row);
                keys(row, "pack", "schemaVersion id version topicId label description language collectionId collectionVersion subtopics review clues");
                Collection collection = collections.get(id(row, "collectionId"));
                require(collection != null, file + ": missing collectionId");
                require(collection.scope.version.equals(string(row, "collectionVersion", 40)), file + ": collectionVersion mismatch");
                String topic = id(row, "topicId"); String language = language(row);
                require(language.equals(collection.language), file + ": pack/collection language mismatch");
                Set<String> subtopics = new LinkedHashSet<>();
                for (JsonValue sub : array(row, "subtopics")) {
                    JsonObject item = object(sub, "subtopic"); keys(item, "subtopic", "id label");
                    string(item, "label", 100); require(subtopics.add(id(item, "id")), file + ": duplicate subtopic ID");
                }
                JsonObject review = object(row, "review");
                keys(review, "review", "status reviewer reviewedOn factualNotes editorialNotes limitations");
                choice(review, "status", "draft", "source-checked", "human-reviewed", "legacy-preserved");
                require(!draftOnly || "draft".equals(review.getString("status")), "Offline draft catalog may contain only drafts");
                date(review, "reviewedOn");
                for (String field : Arrays.asList("reviewer", "factualNotes", "editorialNotes", "limitations")) string(review, field, 3000);
                List<Clue> clues = new ArrayList<>(); Set<String> texts = new HashSet<>();
                for (JsonValue clue : array(row, "clues")) {
                    Clue loaded = new Clue(object(clue, "clue"), topic, subtopics, difficulties.keySet(), collection);
                    require(clueIds.add(loaded.id), "Duplicate clue ID: " + loaded.id);
                    require(texts.add(Evaluation.normalize(loaded.text)), loaded.id + ": duplicate clue text in pack");
                    clues.add(loaded);
                }
                Pack pack = new Pack(id(row, "id"), version(row), topic, string(row, "label", 100),
                        string(row, "description", 2000), language, sha256(Files.readAllBytes(file)), collection, clues, review);
                unique(packs, pack.id, pack, "pack");
            }
            Map<String, Pack> topics = new LinkedHashMap<>(); Set<String> usedPacks = new HashSet<>();
            for (JsonValue value : array(catalog, "topics")) {
                JsonObject row = object(value, "topic"); keys(row, "topic", "id packId packVersion");
                Pack pack = packs.get(id(row, "packId"));
                require(pack != null, "topic: missing packId");
                require(pack.topicId.equals(id(row, "id")) && pack.version.equals(string(row, "packVersion", 40)),
                        "topic: pack topic/version mismatch");
                unique(topics, pack.topicId, pack, "topic"); usedPacks.add(pack.id);
            }
            require(usedPacks.size() == packs.size(), "catalog: every pack must have an explicit topic entry");
            JsonObject defaults = object(catalog, "defaults"); keys(defaults, "defaults", "topicId difficulty");
            String defaultTopic = id(defaults, "topicId"), defaultDifficulty = id(defaults, "difficulty");
            require(topics.containsKey(defaultTopic) && difficulties.containsKey(defaultDifficulty), "catalog: invalid default topic/difficulty");
            require(draftOnly || !topics.get(defaultTopic).eligible(defaultDifficulty).isEmpty(), "catalog: default combination has no reviewed clues");
            return new PackCatalog(id(catalog, "id"), version(catalog), defaultTopic, defaultDifficulty, difficulties, collections, topics);
        } catch (IOException error) { throw new IOException(path + ": " + error.getMessage(), error); }
        catch (RuntimeException error) { throw new IOException(path + ": malformed content: " + error.getMessage(), error); }
    }

    static JsonObject read(Path path) throws IOException {
        return parse(utf8(bytes(path, 4 * 1024 * 1024), path.toString()), path.toString());
    }
    static JsonObject parse(String text, String context) throws IOException {
        try {
            // JSON-P's tree reader otherwise accepts duplicate property names (last value wins).
            Deque<Set<String>> objects = new ArrayDeque<>();
            try (JsonParser parser = Json.createParser(new StringReader(text))) {
                while (parser.hasNext()) {
                    JsonParser.Event event = parser.next();
                    if (event == JsonParser.Event.START_OBJECT) objects.push(new HashSet<>());
                    else if (event == JsonParser.Event.END_OBJECT) objects.pop();
                    else if (event == JsonParser.Event.KEY_NAME) require(objects.peek().add(parser.getString()), context + ": duplicate JSON property " + parser.getString());
                }
            }
            try (JsonReader reader = Json.createReader(new StringReader(text))) { return reader.readObject(); }
        } catch (JsonException | IllegalStateException error) { throw new IOException(context + ": invalid JSON: " + error.getMessage(), error); }
    }
    private static byte[] bytes(Path path, int maximum) throws IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= maximum, path + ": missing/nonregular/oversized file");
        return Files.readAllBytes(path);
    }
    private static String utf8(byte[] bytes, String context) throws IOException {
        try { return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException error) { throw new IOException(context + ": malformed UTF-8", error); }
    }
    private static Path resolve(Path root, String relative) throws IOException {
        Path path = root.resolve(relative).normalize();
        require(!Paths.get(relative).isAbsolute() && path.startsWith(root), "Content paths must stay within catalog directory: " + relative);
        require(path.toRealPath().startsWith(root), "Content symlink escapes catalog directory: " + relative);
        return path;
    }
    private static Path reference(Path root, JsonObject ref) throws IOException {
        keys(ref, "reference", "id version path"); id(ref, "id"); version(ref);
        return resolve(root, string(ref, "path", 300));
    }
    private static void match(JsonObject ref, JsonObject file) throws IOException {
        require(id(ref, "id").equals(id(file, "id")) && version(ref).equals(version(file)), "Reference ID/version does not match content file");
    }
    private static void header(JsonObject row) throws IOException {
        require(integer(row, "schemaVersion") == SCHEMA, "Unsupported schemaVersion; supported: " + SCHEMA);
        id(row, "id"); version(row);
    }
    static void keys(JsonObject row, String context, String allowed) throws IOException {
        Set<String> names = new HashSet<>(Arrays.asList(allowed.split(" ")));
        for (String key : row.keySet()) require(names.contains(key), context + ": unknown field " + key);
    }
    static JsonObject object(JsonValue value, String context) throws IOException {
        require(value != null && value.getValueType() == JsonValue.ValueType.OBJECT, context + ": expected an object"); return (JsonObject) value;
    }
    static JsonObject object(JsonObject row, String key) throws IOException { return object(row.get(key), key); }
    static JsonArray array(JsonObject row, String key) throws IOException {
        JsonValue value = row.get(key); require(value != null && value.getValueType() == JsonValue.ValueType.ARRAY, key + ": expected an array"); return (JsonArray) value;
    }
    static String string(JsonObject row, String key, int maximum) throws IOException {
        JsonValue value = row.get(key); require(value != null && value.getValueType() == JsonValue.ValueType.STRING, key + ": expected a string");
        String text = ((JsonString) value).getString(); require(validText(text, maximum), key + ": expected 1-" + maximum + " trimmed characters without control characters"); return text;
    }
    static boolean validText(String text, int maximum) {
        return !text.trim().isEmpty() && text.equals(text.trim()) && text.length() <= maximum && text.chars().noneMatch(Character::isISOControl);
    }
    static int integer(JsonObject row, String key) throws IOException {
        JsonValue value = row.get(key); require(value instanceof JsonNumber && ((JsonNumber) value).isIntegral(), key + ": expected an integer");
        try { return ((JsonNumber) value).intValueExact(); } catch (ArithmeticException error) { throw new IOException(key + ": integer out of range"); }
    }
    private static String id(JsonObject row, String key) throws IOException {
        String id = string(row, key, 100); require(id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"), key + ": use a lowercase stable slug"); return id;
    }
    private static String version(JsonObject row) throws IOException {
        String version = string(row, "version", 40); require(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), "version: expected major.minor.patch"); return version;
    }
    private static String language(JsonObject row) throws IOException {
        return choice(row, "language", "en"); // The current engine's analysis is English-only.
    }
    private static String choice(JsonObject row, String field, String... choices) throws IOException {
        String text = string(row, field, 100); require(Arrays.asList(choices).contains(text), field + ": unsupported value " + text + "; supported: " + Arrays.toString(choices)); return text;
    }
    private static String date(JsonObject row, String field) throws IOException {
        String text = string(row, field, 10);
        try { LocalDate.parse(text); } catch (RuntimeException error) { throw new IOException(field + ": expected YYYY-MM-DD"); } return text;
    }
    private static String https(JsonObject row, String field) throws IOException {
        String text = string(row, field, 2000);
        try { URI url = new URI(text); require("https".equals(url.getScheme()) && url.getHost() != null && url.getUserInfo() == null, field + ": expected a public HTTPS reference URL"); }
        catch (java.net.URISyntaxException error) { throw new IOException(field + ": malformed URL", error); } return text;
    }
    private static <T> void unique(Map<String, T> map, String id, T value, String kind) throws IOException {
        require(!map.containsKey(id), "Duplicate " + kind + " ID: " + id); map.put(id, value);
    }
    static void require(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
    static String sha256(byte[] bytes) {
        try {
            StringBuilder hex = new StringBuilder(); for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", value & 255)); return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
