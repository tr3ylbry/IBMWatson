package main;

import jakarta.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static main.PackCatalog.*;

/** Bounded English Wikipedia Action API adapter. Only this admin component has network access. */
final class WikiApi {
    static final int MAX_PAGES = 12, MAX_RESPONSE = 1024 * 1024, MAX_TOTAL = 8 * MAX_RESPONSE;
    static final String LICENSE = "https://creativecommons.org/licenses/by-sa/4.0/";
    static final String TERMS = "https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use#7._Licensing_of_Content";
    record Response(int status, Map<String, String> headers, byte[] bytes) {}
    record Document(JsonObject json, String capturedAt, String responseHash) {}
    interface Transport { Response get(URI uri, String userAgent, Budget budget) throws IOException; }
    static class Budget {
        final long deadline; final Path cancel; int transferred;
        Budget(Path cancel) { this(cancel, Duration.ofSeconds(120)); }
        Budget(Path cancel, Duration duration) { this.cancel = cancel; deadline = System.nanoTime() + duration.toNanos(); }
        void check() throws IOException {
            if (Thread.currentThread().isInterrupted() || cancel != null && Files.exists(cancel)) throw new InterruptedIOException("Cancelled; committed artifacts remain unchanged");
            require(System.nanoTime() < deadline, "Operation deadline exceeded; committed artifacts remain unchanged");
            require(transferred <= MAX_TOTAL, "Operation byte budget exceeded; stop and inspect partial capture");
        }
        void bytes(int count) throws IOException { transferred += count; require(transferred <= MAX_TOTAL, "Operation byte budget exceeded"); }
        void pause(long millis) throws IOException {
            long end = System.nanoTime() + Duration.ofMillis(millis).toNanos();
            while (System.nanoTime() < end) {
                check(); try { Thread.sleep(Math.min(100, Math.max(1, Duration.ofNanos(end - System.nanoTime()).toMillis()))); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled during backoff"); }
            }
        }
    }
    private final Transport transport; private final Path cache; private final Clock clock;
    private final String userAgent; private final long spacingMillis; private long lastRequest;
    WikiApi(Transport transport, Path cache, Clock clock, String userAgent, long spacingMillis) throws IOException {
        require(userAgent != null && userAgent.matches("[ -~]{10,250}") && userAgent.contains("(") && userAgent.contains(")")
                && (userAgent.contains("https://") || userAgent.contains("@")), "Set CLUELAB_WIKI_USER_AGENT to a descriptive tool/version (contact URL or email)");
        this.transport = transport; this.cache = cache; this.clock = clock; this.userAgent = userAgent; this.spacingMillis = spacingMillis;
        IntakeFiles.safe(cache); Files.createDirectories(cache);
    }
    static URI uri(Map<String, String> parameters) {
        Map<String, String> query = new TreeMap<>(parameters);
        query.put("action", "query"); query.put("format", "json"); query.put("formatversion", "2"); query.put("maxlag", "5");
        StringJoiner join = new StringJoiner("&"); query.forEach((key, value) -> join.add(encode(key) + "=" + encode(value)));
        return URI.create("https://en.wikipedia.org/w/api.php?" + join);
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String article(String title) { return "https://en.wikipedia.org/wiki/" + encode(title.replace(' ', '_')); }
    Document request(Map<String, String> parameters, Budget budget) throws IOException {
        URI uri = uri(parameters); WikipediaTransport.destination(uri); budget.check();
        String key = sha256(uri.toASCIIString().getBytes(StandardCharsets.UTF_8));
        Document cached = cached(key, budget); if (cached != null) return cached;
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            budget.check();
            long elapsed = lastRequest == 0 ? spacingMillis : Duration.ofNanos(System.nanoTime() - lastRequest).toMillis();
            if (elapsed < spacingMillis) budget.pause(spacingMillis - elapsed);
            lastRequest = System.nanoTime(); Response response;
            try { response = transport.get(uri, userAgent, budget); }
            catch (InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException error) {
                last = error;
                if (attempt == 0 && (error instanceof SocketException || error instanceof EOFException || error instanceof java.net.UnknownHostException)) {
                    budget.pause(1000); continue;
                }
                throw error;
            }
            require(response.bytes.length <= MAX_RESPONSE, "Response exceeds decompressed byte limit");
            // Injected transports must enforce transferred bytes too; count decoded bytes as a second operation bound.
            budget.bytes(response.bytes.length);
            if (response.status == 429 || response.status == 503) {
                require(attempt == 0, "Wikipedia HTTP " + response.status + " after retry; try later");
                budget.pause(retryDelay(response.headers.get("retry-after"))); continue;
            }
            require(response.status == 200, "Wikipedia HTTP " + response.status + "; inspect access/policy before retrying");
            JsonObject json = IntakeFiles.parse(IntakeFiles.utf8(response.bytes), "Wikipedia API");
            if (json.containsKey("error")) {
                JsonObject error = object(json, "error");
                if ("maxlag".equals(error.getString("code", "")) && attempt == 0) { budget.pause(5000); continue; }
                throw new IOException("Wikipedia API error: " + error.getString("code", "unknown") + " " + error.getString("info", ""));
            }
            require(!json.containsKey("warnings"), "Wikipedia API warnings; adapter needs inspection: " + json.get("warnings"));
            String captured = clock.instant().toString(), hash = sha256(response.bytes);
            byte[] entry = IntakeFiles.json(Json.createObjectBuilder().add("uri", uri.toASCIIString()).add("capturedAt", captured)
                    .add("sha256", hash).add("body", Base64.getEncoder().encodeToString(response.bytes)).build());
            prune(entry.length, budget);
            Path output = cache.resolve(key + "-" + hash + ".json"); IntakeFiles.safe(output);
            if (!Files.exists(output)) {
                Path temporary = Files.createTempFile(cache.toAbsolutePath().getParent(), ".wiki-response-", ".tmp");
                try { Files.write(temporary, entry); budget.check(); Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE); }
                finally { Files.deleteIfExists(temporary); }
            }
            return new Document(json, captured, hash);
        }
        throw new IOException("Wikipedia retry exhausted", last);
    }
    static long retryDelay(String value) throws IOException {
        if (value == null) return 1000;
        try { long seconds = Long.parseLong(value); require(seconds >= 0 && seconds <= 10, "Retry-After exceeds bounded wait; retry later"); return Math.max(1000, seconds * 1000); }
        catch (NumberFormatException error) { throw new IOException("Unsupported Retry-After date; retry later rather than retrying early", error); }
    }
    private List<Path> cacheFiles() throws IOException {
        IntakeFiles.safe(cache);
        try (var files = Files.list(cache)) {
            List<Path> list = files.sorted().limit(257).toList(); require(list.size() <= 256, "Cache exceeds 256 files; inspect/clear cache");
            for (Path file : list) { IntakeFiles.safe(file); require(Files.isRegularFile(file), "Unexpected cache entry: " + file); }
            return list;
        }
    }
    private Document cached(String key, Budget budget) throws IOException {
        prune(0, budget); List<Path> candidates = cacheFiles().stream().filter(f -> f.getFileName().toString().startsWith(key + "-")).toList();
        Document latest = null;
        for (Path file : candidates) {
            budget.check(); JsonObject row = IntakeFiles.read(file); byte[] body;
            try { body = Base64.getDecoder().decode(row.getString("body")); }
            catch (RuntimeException error) { throw new IOException("Malformed cache entry: " + file, error); }
            require(body.length <= MAX_RESPONSE && sha256(body).equals(row.getString("sha256")), "Corrupt cache body: " + file);
            require(sha256(row.getString("uri").getBytes(StandardCharsets.UTF_8)).equals(key), "Corrupt cache request identity");
            Document document = new Document(IntakeFiles.parse(IntakeFiles.utf8(body), "cached API"), row.getString("capturedAt"), row.getString("sha256"));
            if (latest == null || latest.capturedAt.compareTo(document.capturedAt) < 0) latest = document;
        }
        if (latest != null) budget.bytes(IntakeFiles.json(latest.json).length);
        return latest;
    }
    private void prune(int incoming, Budget budget) throws IOException {
        long size = 0; List<Path> retained = new ArrayList<>();
        for (Path file : cacheFiles()) {
            budget.check(); JsonObject entry = IntakeFiles.read(file); Instant created;
            try { created = Instant.parse(entry.getString("capturedAt")); }
            catch (RuntimeException error) { throw new IOException("Malformed cache date: " + file, error); }
            require(!created.isAfter(clock.instant().plusSeconds(60)), "Cache date is in the future");
            if (created.isBefore(clock.instant().minus(Duration.ofDays(7)))) Files.delete(file);
            else { retained.add(file); size += Files.size(file); }
        }
        require(size + incoming <= 32L * MAX_RESPONSE && retained.size() + (incoming == 0 ? 0 : 1) <= 256,
                "Cache retention limit reached; move captures to immutable artifacts and clear the cache");
    }
    JsonObject preview(String topic, int limit, Budget budget) throws IOException {
        require(validText(topic, 150) && topic.codePoints().anyMatch(Character::isLetter), "Topic: use 1-150 trimmed English topic characters");
        require(limit >= 1 && limit <= MAX_PAGES, "Preview limit must be 1-12");
        Document response = request(Map.of("list", "search", "srsearch", topic, "srnamespace", "0", "srlimit", "" + limit, "srprop", ""), budget);
        JsonArrayBuilder candidates = Json.createArrayBuilder(); Set<Integer> ids = new HashSet<>();
        for (JsonValue value : array(object(response.json, "query"), "search")) {
            JsonObject row = object(value, "search hit"); int id = integer(row, "pageid"); String title = title(row);
            require(id > 0 && integer(row, "ns") == 0 && ids.add(id), "Invalid/duplicate search page ID");
            candidates.add(Json.createObjectBuilder().add("pageId", id).add("title", title).add("url", article(title)));
        }
        require(ids.size() <= limit, "API returned more candidates than requested");
        return Json.createObjectBuilder().add("schemaVersion", 1).add("topic", topic).add("language", "en")
                .add("capturedAt", response.capturedAt).add("responseSha256", response.responseHash).add("candidates", candidates).build();
    }
    static String title(JsonObject row) throws IOException {
        String title = string(row, "title", 200);
        require(!title.contains("|") && !title.contains("[") && !title.contains("]") && !title.contains(":")
                && title.codePoints().anyMatch(Character::isLetterOrDigit), "Unsupported namespace/symbol-only article title: " + title);
        return title;
    }
    JsonObject capture(JsonObject selected, Budget budget) throws IOException {
        String requested = title(selected); int requestedId = integer(selected, "pageId"); require(requestedId > 0, "Invalid selected page ID");
        Document response = request(Map.of("pageids", "" + requestedId, "redirects", "1", "prop", "extracts|info|revisions|pageprops",
                "inprop", "url", "rvprop", "ids|timestamp", "explaintext", "1", "exintro", "1", "exlimit", "1"), budget);
        JsonObject query = object(response.json, "query");
        JsonArray redirects = query.getJsonArray("redirects");
        require(redirects == null || redirects.size() <= 5, "Article redirect depth exceeds five");
        String resolved = requested; Set<String> seen = new HashSet<>(); seen.add(resolved);
        if (redirects != null) for (JsonValue value : redirects) {
            JsonObject link = object(value, "redirect");
            require(!link.containsKey("tointerwiki") && !link.containsKey("tofragment") && string(link, "from", 200).equals(resolved), "Unresolved/external/fragment article redirect");
            resolved = string(link, "to", 200); require(seen.add(resolved), "Article redirect cycle");
        }
        JsonArray pages = array(query, "pages"); require(pages.size() == 1, "Expected exactly one resolved article");
        JsonObject page = object(pages.get(0), "page"); String canonical = title(page);
        require(!page.containsKey("missing") && !page.containsKey("invalid") && !page.containsKey("redirect")
                && integer(page, "ns") == 0 && canonical.equals(resolved), "Unresolved/missing/renamed article; preview/select again");
        require(page.getJsonObject("pageprops") == null || !page.getJsonObject("pageprops").containsKey("disambiguation"), "Disambiguation pages need explicit disambiguation, not export");
        int pageId = integer(page, "pageid"); require(pageId > 0 && (redirects != null || pageId == requestedId), "Resolved page identity mismatch");
        String text = page.getString("extract", "").strip(); usable(canonical, text);
        String revision = null;
        JsonArray revisions = page.getJsonArray("revisions");
        if (revisions != null && !revisions.isEmpty()) {
            JsonObject rev = object(revisions.get(0), "revision");
            if (rev.containsKey("revid")) { int id = integer(rev, "revid"); require(id > 0, "Invalid revision ID"); revision = "" + id; }
        }
        JsonObjectBuilder result = Json.createObjectBuilder().add("pageId", pageId).add("requestedPageId", requestedId)
                .add("requestedTitle", requested).add("title", canonical).add("originalUrl", article(requested)).add("canonicalUrl", article(canonical))
                .add("publisher", "Wikipedia contributors / Wikimedia Foundation").add("licenseUrl", LICENSE).add("licenseEvidence", TERMS)
                .add("capturedAt", response.capturedAt).add("responseSha256", response.responseHash)
                .add("text", text + "\n").add("textSha256", sha256((text + "\n").getBytes(StandardCharsets.UTF_8)))
                .add("redirects", redirects == null ? Json.createArrayBuilder().build() : redirects);
        if (revision == null) result.addNull("revisionId"); else result.add("revisionId", revision);
        return result.build();
    }
    static void usable(String title, String text) throws IOException {
        require(title.codePoints().anyMatch(Character::isLetterOrDigit), "Symbol-only title has no searchable identity");
        require(!text.stripLeading().toLowerCase(Locale.ROOT).startsWith("#redirect"), "Redirect-only stub; resolve the target");
        require(text.length() >= 400 && text.split("\\s+").length >= 60 && text.chars().filter(c -> c == '.' || c == '!' || c == '?').count() >= 2,
                "Insufficient usable context: need 400 characters, 60 word tokens and two sentences");
        require((text + "\n").getBytes(StandardCharsets.UTF_8).length <= 128 * 1024 && text.codePoints().noneMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t'),
                "Oversized/control-containing context; select another source rather than truncating");
    }
}
