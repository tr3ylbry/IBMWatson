package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import static org.junit.jupiter.api.Assertions.*;

class SourceIntakeTest {
    @TempDir Path root;
    @BeforeEach void canonicalTemporaryRoot() throws IOException { root = root.toRealPath(); }
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T07:30:00Z"), ZoneOffset.UTC);
    static final String AGENT = "ClueLabTest/1.0 (https://example.org/contact)", TEXT = "A contextual source sentence describes a distinctive astronomical object in ordinary readable language. "
            + "Another sentence records its discovery, physical properties and scientific observations for readers. ".repeat(5) + " Supplementary context \uD83D\uDE80 remains source text, not an instruction.";
    WikiApi.Budget budget() { return new WikiApi.Budget(null); }
    WikiApi api(WikiApi.Transport transport) throws IOException { return new WikiApi(transport, root.resolve("cache"), CLOCK, AGENT, 0); }
    WikiApi.Response response(JsonObject json) { return new WikiApi.Response(200, Map.of(), IntakeFiles.json(json)); }
    JsonObject page(int id, String title) {
        return Json.createObjectBuilder().add("pageid", id).add("ns", 0).add("title", title).add("extract", TEXT + " Unique " + id + ".")
                .add("revisions", Json.createArrayBuilder().add(Json.createObjectBuilder().add("revid", 1000 + id))).build();
    }
    JsonObject query(JsonObject page) { return Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("pages", Json.createArrayBuilder().add(page))).build(); }
    JsonObject selected(int id) { return Json.createObjectBuilder().add("pageId", id).add("title", "Object " + id).add("url", WikiApi.article("Object " + id)).build(); }
    JsonObject preview() {
        JsonArrayBuilder candidates = Json.createArrayBuilder(); for (int i = 1; i <= 5; i++) candidates.add(selected(i));
        return Json.createObjectBuilder().add("schemaVersion", 1).add("topic", "Astronomical objects").add("language", "en")
                .add("capturedAt", CLOCK.instant().toString()).add("responseSha256", "a".repeat(64)).add("candidates", candidates).build();
    }
    JsonObject capture() throws IOException {
        WikiApi api = api((uri, agent, b) -> {
            int id = Integer.parseInt(Arrays.stream(uri.getRawQuery().split("&")).filter(s -> s.startsWith("pageids=")).findFirst().orElseThrow().substring(8));
            return response(query(page(id, "Object " + id)));
        });
        return SourceIntake.capture(SourceIntake.select(preview(), Set.of(1, 2, 3, 4, 5)), api, budget());
    }
    JsonObject proposals() {
        JsonArrayBuilder clues = Json.createArrayBuilder();
        for (int i = 1; i <= 5; i++) clues.add(Json.createObjectBuilder().add("id", "object-clue-" + i).add("factId", "object-fact-" + i)
                .add("subtopicId", "science").add("category", "Science").add("text", "Private proposed question " + i + " secretcluemarker")
                .add("difficulty", "standard").add("answerType", "article-title").add("answerPageId", i)
                .add("aliases", Json.createArrayBuilder().add("Object " + i).add("privatealias" + i))
                .add("support", Json.createObjectBuilder().add("pageId", i).add("text", "Unique " + i + "."))
                .add("reviewNotes", "Private unsupportedclaimreviewmarker; inspect source in context."));
        return Json.createObjectBuilder().add("schemaVersion", 1).add("id", "fixture-intake").add("version", "1.0.0").add("topicId", "fixture")
                .add("topic", "Astronomical objects").add("label", "Fixture").add("description", "Synthetic workflow test, not editorial approval.")
                .add("subtopics", Json.createArrayBuilder().add(Json.createObjectBuilder().add("id", "science").add("label", "Science")))
                .add("authoring", Json.createObjectBuilder().add("method", "operator-supplied").add("author", "Synthetic test fixture")
                        .add("createdOn", "2026-11-06").add("notes", "Private authorprovenancemarker").addNull("humanReviewer").addNull("humanReviewedOn"))
                .add("clues", clues).build();
    }
    JsonObject changeClue(JsonObject proposal, int index, JsonObject replacement) {
        JsonArrayBuilder clues = Json.createArrayBuilder(); for (int i = 0; i < proposal.getJsonArray("clues").size(); i++) clues.add(i == index ? replacement : proposal.getJsonArray("clues").getJsonObject(i));
        return Json.createObjectBuilder(proposal).add("clues", clues).build();
    }
    JsonObject first(JsonObject proposal) { return proposal.getJsonArray("clues").getJsonObject(0); }
    void export(JsonObject capture, JsonObject proposals) throws IOException { DraftExporter.export(capture, proposals, root.resolve("export"), budget()); }

    @Test void destinationRejectsEveryNonApiUrlAndPathEscape() {
        for (String url : List.of("http://en.wikipedia.org/w/api.php?a=1", "https://localhost/w/api.php?a=1", "https://en.wikipedia.org.evil.org/w/api.php?a=1",
                "https://en.wikipedia.org@127.0.0.1/w/api.php?a=1", "https://en.wikipedia.org:443/w/api.php?a=1", "file:///etc/passwd",
                "https://en.wikipedia.org/w/../api.php?a=1", "https://en.wikipedia.org/w/%61pi.php?a=1", "https://en.wikipedia.org/wiki/Test?a=1", "https://en.wikipedia.org/w/api.php?a=1#fragment"))
            assertThrows(IOException.class, () -> WikipediaTransport.destination(URI.create(url)), url);
    }
    @Test void destinationAllowsOnlyStructuredQueryEndpoint() throws Exception {
        URI uri = WikiApi.uri(Map.of("titles", "https://127.0.0.1/?x=1|../escape"));
        WikipediaTransport.destination(uri); assertEquals("en.wikipedia.org", uri.getHost()); assertTrue(uri.toASCIIString().contains("maxlag=5"));
    }
    @Test void privateReservedAndTransitionAddressesAreRejected() throws Exception {
        for (String ip : List.of("0.0.0.0", "10.0.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.2", "100.64.0.1", "198.18.0.1",
                "192.0.2.1", "198.51.100.2", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "fc00::1", "fe80::1", "2001:db8::1", "2002:7f00:1::1"))
            assertFalse(WikipediaTransport.global(InetAddress.getByName(ip)), ip);
        assertTrue(WikipediaTransport.global(InetAddress.getByName("208.80.154.224")));
        assertTrue(WikipediaTransport.global(InetAddress.getByName("2620:0:861:ed1a::1")));
    }
    @Test void resolverIsValidatedBeforeAnySocketConnection() {
        AtomicInteger calls = new AtomicInteger();
        WikipediaTransport transport = new WikipediaTransport(host -> { calls.incrementAndGet(); return new InetAddress[]{InetAddress.getByName("127.0.0.1")}; });
        assertThrows(IOException.class, () -> transport.get(WikiApi.uri(Map.of()), AGENT, budget())); assertEquals(1, calls.get());
    }
    @Test void slowDnsIsBoundedAndCancellable() {
        WikipediaTransport transport = new WikipediaTransport(host -> { try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return new InetAddress[0]; });
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(IOException.class, () -> transport.get(WikiApi.uri(Map.of()), AGENT, new WikiApi.Budget(null, Duration.ofMillis(30)))));
        Path marker = root.resolve("dns-cancel");
        WikipediaTransport cancelled = new WikipediaTransport(host -> {
            Files.createFile(marker);
            try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return new InetAddress[0];
        });
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(InterruptedIOException.class,
                () -> cancelled.get(WikiApi.uri(Map.of()), AGENT, new WikiApi.Budget(marker))));
    }
    WikiApi.Response decode(String wire) throws IOException { return WikipediaTransport.decode(new ByteArrayInputStream(wire.getBytes(StandardCharsets.UTF_8)), budget()); }
    @Test void httpRedirectsIncludingSameHostAndPrivateTargetsAreNeverFollowed() {
        for (String location : List.of("http://127.0.0.1/", "https://169.254.169.254/", "https://en.wikipedia.org/w/api.php?a=1", "/w/api.php?a=1", "https://other.example/w/api.php"))
            assertThrows(IOException.class, () -> decode("HTTP/1.1 302 Found\r\nLocation: " + location + "\r\nContent-Length: 0\r\n\r\n"));
    }
    @Test void malformedFramingAndTruncationFailClosed() {
        for (String wire : List.of("invalid\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 9\r\nContent-Type: application/json\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Length: 2\r\n\r\n{}", "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n", "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n{}"))
            assertThrows(IOException.class, () -> decode(wire));
    }
    @Test void legalCookiesAreIgnoredAndChunkedJsonIsRead() throws Exception {
        WikiApi.Response r = decode("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\nTransfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n");
        assertEquals("{}", IntakeFiles.utf8(r.bytes()));
    }
    @Test void compressedBombAndOversizedTransferAreRejected() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream(); try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(new byte[WikiApi.MAX_RESPONSE + 1]); }
        ByteArrayOutputStream wire = new ByteArrayOutputStream(); wire.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Encoding: gzip\r\nContent-Length: " + compressed.size() + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); wire.write(compressed.toByteArray());
        assertThrows(IOException.class, () -> WikipediaTransport.decode(new ByteArrayInputStream(wire.toByteArray()), budget()));
        assertThrows(IOException.class, () -> decode("HTTP/1.1 200 OK\r\nContent-Length: 1048577\r\n\r\n"));
    }
    @Test void totalBudgetAndDeadlineAreEnforced() throws Exception {
        WikiApi.Budget b = budget(); assertThrows(IOException.class, () -> b.bytes(WikiApi.MAX_TOTAL + 1)); assertThrows(IOException.class, b::check);
        assertThrows(IOException.class, new WikiApi.Budget(null, Duration.ZERO)::check);
    }
    @Test void cancellationMarkerAndInterruptStopWork() throws Exception {
        Path marker = root.resolve("cancel"); Files.createFile(marker);
        assertThrows(InterruptedIOException.class, new WikiApi.Budget(marker)::check);
        assertThrows(InterruptedIOException.class, () -> new WikiApi.Budget(marker).pause(5000));
        Thread.currentThread().interrupt(); try { assertThrows(InterruptedIOException.class, budget()::check); } finally { Thread.interrupted(); }
    }
    @Test void transientRetryIsBoundedAndCachePreventsNewDownloads() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WikiApi api = api((uri, agent, b) -> calls.incrementAndGet() == 1 ? new WikiApi.Response(503, Map.of("retry-after", "0"), new byte[0]) : response(query(page(1, "Object 1"))));
        WikiApi.Document first = api.request(Map.of("pageids", "1"), budget()), second = api.request(Map.of("pageids", "1"), budget());
        assertEquals(2, calls.get()); assertEquals(first, second);
        assertThrows(IOException.class, () -> WikiApi.retryDelay("11")); assertThrows(IOException.class, () -> WikiApi.retryDelay("Wed, 12 Nov"));
    }
    @Test void repeatedServerFailureStopsAfterOneRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(); WikiApi api = api((uri, agent, b) -> { calls.incrementAndGet(); return new WikiApi.Response(429, Map.of(), new byte[0]); });
        assertThrows(IOException.class, () -> api.request(Map.of(), budget())); assertEquals(2, calls.get());
    }
    @Test void malformedUtf8JsonDuplicateKeysAndWarningsAreRejected() throws Exception {
        for (byte[] bytes : List.of(new byte[]{(byte) 0xff}, "{\"query\":{},\"query\":{}}".getBytes(StandardCharsets.UTF_8), "{\"warnings\":{}}".getBytes(StandardCharsets.UTF_8))) {
            Path cache = Files.createTempDirectory(root, "bad-api-"); WikiApi api = new WikiApi((u, a, b) -> new WikiApi.Response(200, Map.of(), bytes), cache, CLOCK, AGENT, 0);
            assertThrows(IOException.class, () -> api.request(Map.of(), budget()));
        }
    }
    @Test void excessiveJsonNestingAndUncontactableUserAgentFail() throws Exception {
        String nested = "{\"x\":".repeat(33) + "0" + "}".repeat(33);
        assertThrows(IOException.class, () -> IntakeFiles.parse(nested, "synthetic nesting"));
        assertThrows(IOException.class, () -> new WikiApi((u, a, b) -> response(query(page(1, "Object 1"))), root.resolve("agent"), CLOCK, "Mozilla browser", 0));
    }
    @Test void oldCacheExpiresButCaptureBytesRemainIndependent() throws Exception {
        AtomicInteger calls = new AtomicInteger(); WikiApi.Transport t = (u, a, b) -> { calls.incrementAndGet(); return response(query(page(1, "Object 1"))); };
        api(t).request(Map.of(), budget());
        WikiApi later = new WikiApi(t, root.resolve("cache"), Clock.offset(CLOCK, Duration.ofDays(8)), AGENT, 0);
        later.request(Map.of(), budget()); assertEquals(2, calls.get());
        try (var files = Files.list(root.resolve("cache"))) { assertEquals(1, files.count()); }
    }
    @Test void corruptCacheAndCacheFileCountFailActionably() throws Exception {
        WikiApi api = api((u, a, b) -> response(query(page(1, "Object 1")))); api.request(Map.of(), budget());
        Path cached; try (var paths = Files.list(root.resolve("cache"))) { cached = paths.findFirst().orElseThrow(); }
        JsonObject row = IntakeFiles.read(cached); Files.write(cached, IntakeFiles.json(Json.createObjectBuilder(row).add("sha256", "0".repeat(64)).build()));
        assertThrows(IOException.class, () -> api.request(Map.of(), budget()));
        Files.delete(cached); for (int i = 0; i < 257; i++) Files.createFile(root.resolve("cache/entry" + i));
        assertThrows(IOException.class, () -> api.request(Map.of(), budget()));
    }
    @Test void cacheByteRetentionIsBounded() throws Exception {
        Path cache = root.resolve("cache"); Files.createDirectories(cache);
        byte[] entry = IntakeFiles.json(Json.createObjectBuilder().add("capturedAt", CLOCK.instant().toString()).add("padding", "x".repeat(2 * 1024 * 1024)).build());
        for (int i = 0; i < 16; i++) Files.write(cache.resolve("entry-" + i + ".json"), entry);
        WikiApi api = api((u, a, b) -> response(query(page(1, "Object 1"))));
        IOException error = assertThrows(IOException.class, () -> api.request(Map.of(), budget())); assertTrue(error.getMessage().contains("Cache retention limit"));
    }
    @Test void selectionRejectsUnknownDuplicateUnsafeAndUnsupportedInputs() throws Exception {
        assertThrows(IOException.class, () -> SourceIntake.select(preview(), Set.of(999)));
        assertThrows(IOException.class, () -> SourceIntake.select(Json.createObjectBuilder(preview()).add("schemaVersion", 2).build(), Set.of(1)));
        assertThrows(IOException.class, () -> SourceIntake.select(Json.createObjectBuilder(preview()).add("language", "fr").build(), Set.of(1)));
        JsonObject duplicate = Json.createObjectBuilder(preview()).add("candidates", Json.createArrayBuilder().add(selected(1)).add(selected(1))).build();
        assertThrows(IOException.class, () -> SourceIntake.select(duplicate, Set.of(1)));
        JsonObject url = Json.createObjectBuilder(selected(1)).add("url", "https://127.0.0.1/").build();
        assertThrows(IOException.class, () -> SourceIntake.select(Json.createObjectBuilder(preview()).add("candidates", Json.createArrayBuilder().add(url)).build(), Set.of(1)));
    }
    @Test void articleRedirectResolvesCanonicalIdentityAndRecordsBothUrls() throws Exception {
        JsonObject json = Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("pages", Json.createArrayBuilder().add(page(2, "Object 2")))
                .add("redirects", Json.createArrayBuilder().add(Json.createObjectBuilder().add("from", "Object 1").add("to", "Object 2")))).build();
        JsonObject source = api((u, a, b) -> response(json)).capture(selected(1), budget());
        assertEquals(2, source.getInt("pageId")); assertEquals(WikiApi.article("Object 1"), source.getString("originalUrl")); assertEquals(WikiApi.article("Object 2"), source.getString("canonicalUrl"));
    }
    @Test void unresolvedRedirectsCyclesAndExcessiveDepthFail() throws Exception {
        for (JsonObject bad : List.of(Json.createObjectBuilder(page(1, "Object 1")).add("redirect", true).build(), Json.createObjectBuilder(page(1, "Object 1")).add("extract", "#REDIRECT [[Other]]").build())) {
            WikiApi api = new WikiApi((u, a, b) -> response(query(bad)), Files.createTempDirectory(root, "redirect-"), CLOCK, AGENT, 0);
            assertThrows(IOException.class, () -> api.capture(selected(1), budget()));
        }
        JsonArrayBuilder cycle = Json.createArrayBuilder().add(Json.createObjectBuilder().add("from", "Object 1").add("to", "Object 2")).add(Json.createObjectBuilder().add("from", "Object 2").add("to", "Object 1"));
        JsonObject json = Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("pages", Json.createArrayBuilder().add(page(1, "Object 1"))).add("redirects", cycle)).build();
        assertThrows(IOException.class, () -> api((u, a, b) -> response(json)).capture(selected(1), budget()));
        JsonArrayBuilder deep = Json.createArrayBuilder();
        for (int i = 1; i <= 6; i++) deep.add(Json.createObjectBuilder().add("from", "Object " + i).add("to", "Object " + (i + 1)));
        JsonObject tooDeep = Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("pages", Json.createArrayBuilder().add(page(7, "Object 7"))).add("redirects", deep)).build();
        WikiApi deepApi = new WikiApi((u, a, b) -> response(tooDeep), root.resolve("deep-cache"), CLOCK, AGENT, 0);
        assertThrows(IOException.class, () -> deepApi.capture(selected(1), budget()));
    }
    @Test void emptyThinSymbolOnlyDisambiguationAndOversizedContextsFail() throws Exception {
        for (String text : List.of("", "Short source.", "#redirect [[Other]]", "a ".repeat(70000))) assertThrows(IOException.class, () -> WikiApi.usable("Object", text));
        assertThrows(IOException.class, () -> WikiApi.usable("\u30a0", TEXT));
        JsonObject page = Json.createObjectBuilder(page(1, "Object 1")).add("pageprops", Json.createObjectBuilder().add("disambiguation", "")).build();
        assertThrows(IOException.class, () -> api((u, a, b) -> response(query(page))).capture(selected(1), budget()));
    }
    @Test void unknownRevisionsRemainNullAndPartialFailureIsExplicit() throws Exception {
        JsonObject noRevision = Json.createObjectBuilder(page(1, "Object 1")).remove("revisions").build();
        WikiApi api = api((uri, a, b) -> response(uri.getRawQuery().contains("pageids=1") ? query(noRevision) : query(Json.createObjectBuilder(page(2, "Object 2")).add("extract", "Too short").build())));
        JsonObject result = SourceIntake.capture(SourceIntake.select(preview(), Set.of(1, 2)), api, budget());
        assertEquals(1, result.getJsonArray("sources").size()); assertEquals(1, result.getJsonArray("failures").size()); assertTrue(result.getJsonArray("sources").getJsonObject(0).isNull("revisionId"));
        JsonObject remoteError = Json.createObjectBuilder().add("error", Json.createObjectBuilder().add("code", "badrequest").add("info", "x".repeat(50000))).build();
        WikiApi errors = new WikiApi((u, a, b) -> response(remoteError), root.resolve("error-cache"), CLOCK, AGENT, 0);
        JsonObject failed = SourceIntake.capture(SourceIntake.select(preview(), Set.of(1)), errors, budget());
        assertEquals(1000, failed.getJsonArray("failures").getJsonObject(0).getString("error").length());
    }
    @Test void duplicateCanonicalPagesAfterRedirectAreReported() throws Exception {
        WikiApi api = api((uri, a, b) -> {
            JsonObjectBuilder q = Json.createObjectBuilder().add("pages", Json.createArrayBuilder().add(page(1, "Object 1")));
            if (uri.getRawQuery().contains("pageids=2")) q.add("redirects", Json.createArrayBuilder().add(Json.createObjectBuilder().add("from", "Object 2").add("to", "Object 1")));
            return response(Json.createObjectBuilder().add("query", q).build());
        });
        JsonObject result = SourceIntake.capture(SourceIntake.select(preview(), Set.of(1, 2)), api, budget()); assertEquals(1, result.getJsonArray("sources").size()); assertEquals(1, result.getJsonArray("failures").size());
    }
    @Test void cancelledCaptureReportsProgressAndStopsInsteadOfInventingSources() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        WikiApi api = api((uri, a, b) -> {
            if (calls.incrementAndGet() == 2) throw new InterruptedIOException("Synthetic cancellation");
            return response(query(page(1, "Object 1")));
        });
        IOException error = assertThrows(InterruptedIOException.class, () -> SourceIntake.capture(SourceIntake.select(preview(), Set.of(1, 2, 3)), api, budget()));
        assertTrue(error.getMessage().contains("completed 1/3")); assertEquals(2, calls.get()); assertFalse(Files.exists(root.resolve("capture")));
    }
    @Test void fiveProposalsExportAsZeroEligibleAndCannotBeGameDefaults() throws Exception {
        export(capture(), proposals()); PackCatalog catalog = DraftExporter.validate(root.resolve("export"));
        PackCatalog.Pack pack = catalog.topics.get("fixture"); assertEquals(5, pack.clues.size()); assertTrue(pack.eligible("standard").isEmpty());
        assertThrows(IOException.class, () -> PackCatalog.load(root.resolve("export/catalog.json")));
        assertEquals(0, IntakeFiles.read(root.resolve("export/private/review.json")).getInt("humanReviewed"));
    }
    @Test void draftIndexUsesAllSourcesNeverPrivateProposalsAndEvaluationIsEmpty() throws Exception {
        export(capture(), proposals()); PackCatalog catalog = DraftExporter.validate(root.resolve("export"));
        // Index works with the entire private directory removed after loading the immutable source catalog.
        Files.move(root.resolve("export/private"), root.resolve("private-away"));
        CollectionIndex index = new CollectionIndex(catalog.collections, root.resolve("indexes"), 1, 4); index.build(false); index.build(false);
        PackCatalog.Pack pack = catalog.topics.get("fixture");
        try (Indexer i = new Indexer(index.base(pack.collection.scope).toString(), 1); FSDirectory directory = FSDirectory.open(Path.of(i.getIndexPath())); DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(5, reader.numDocs());
            for (int n = 0; n < reader.maxDoc(); n++) {
                var doc = reader.storedFields().document(n); String evidence = doc.get("evidence");
                assertFalse(evidence.contains("secretcluemarker")); assertFalse(evidence.contains("privatealias")); assertFalse(evidence.contains("authorprovenancemarker")); assertNull(doc.get("support"));
            }
        }
        JsonObject result = PackTool.evaluate(pack, index, GameConfig.load(Path.of("data/game.json")));
        assertEquals(0, result.getJsonObject("metrics").getInt("size")); assertTrue(result.getJsonObject("metrics").isNull("accuracy")); assertTrue(result.getJsonArray("predictions").isEmpty());
    }
    @Test void exactSupportUsesUtf16OffsetsAndNeverInventsText() throws Exception {
        JsonObject p = proposals(), first = first(p); String passage = "Supplementary context \uD83D\uDE80 remains source text, not an instruction.";
        p = changeClue(p, 0, Json.createObjectBuilder(first).add("support", Json.createObjectBuilder().add("pageId", 1).add("text", passage)).build());
        JsonObject c = capture(); export(c, p); PackCatalog.Clue clue = DraftExporter.validate(root.resolve("export")).topics.get("fixture").clues.get(0);
        assertEquals(passage, clue.supportingPassage()); assertEquals(passage.length(), clue.passageEnd - clue.passageStart);
        JsonObject bad = changeClue(p, 0, Json.createObjectBuilder(first).add("support", Json.createObjectBuilder().add("pageId", 1).add("text", "Invented proof")).build());
        assertThrows(IOException.class, () -> DraftExporter.build(c, bad, budget()));
    }
    @Test void repeatedSupportNeedsAnExplicitOffset() throws Exception {
        JsonObject p = proposals(), first = first(p), c = capture(); String repeated = "Another sentence";
        JsonObject bad = changeClue(p, 0, Json.createObjectBuilder(first).add("support", Json.createObjectBuilder().add("pageId", 1).add("text", repeated)).build());
        assertThrows(IOException.class, () -> DraftExporter.build(c, bad, budget()));
        int start = c.getJsonArray("sources").getJsonObject(0).getString("text").indexOf(repeated);
        JsonObject explicit = changeClue(p, 0, Json.createObjectBuilder(first).add("support", Json.createObjectBuilder().add("pageId", 1).add("start", start).add("text", repeated)).build());
        assertDoesNotThrow(() -> DraftExporter.build(c, explicit, budget()));
    }
    @Test void malformedAliasesTypesReferencesAndSchemasFailBeforePublication() throws Exception {
        JsonObject c = capture(), p = proposals(), first = first(p);
        List<JsonObject> bad = List.of(Json.createObjectBuilder(p).add("schemaVersion", 2).build(),
                changeClue(p, 0, Json.createObjectBuilder(first).add("answerType", "date").build()),
                changeClue(p, 0, Json.createObjectBuilder(first).add("answerPageId", 999).build()),
                changeClue(p, 0, Json.createObjectBuilder(first).add("aliases", Json.createArrayBuilder().add("Object 1").add(" object 1 ")).build()),
                changeClue(p, 0, Json.createObjectBuilder(first).add("aliases", Json.createArrayBuilder().add("Missing canonical title")).build()),
                changeClue(p, 0, Json.createObjectBuilder(first).add("support", Json.createObjectBuilder().add("pageId", 999).add("text", "no source")).build()),
                changeClue(p, 0, Json.createObjectBuilder(first).add("difficulty", "expert").build()),
                changeClue(p, 0, Json.createObjectBuilder(first).add("support", Json.createObjectBuilder().add("pageId", 1).add("text", 123)).build()),
                Json.createObjectBuilder(p).add("authoring", Json.createObjectBuilder(p.getJsonObject("authoring")).add("humanReviewer", "invented")).build());
        for (JsonObject proposal : bad) { assertThrows(IOException.class, () -> export(c, proposal)); assertFalse(Files.exists(root.resolve("export"))); }
    }
    @Test void sharedAliasesAndRepeatedFactsAreFlagsNotFalseApprovals() throws Exception {
        JsonObject c = capture(), p = proposals(), first = first(p), second = p.getJsonArray("clues").getJsonObject(1);
        p = changeClue(p, 1, Json.createObjectBuilder(second).add("factId", first.getString("factId")).add("aliases", Json.createArrayBuilder().add("Object 2").add("privatealias1")).build());
        export(c, p); String review = IntakeFiles.read(root.resolve("export/private/review.json")).toString();
        assertTrue(review.contains("Repeated factId")); assertTrue(review.contains("Ambiguous/shared alias")); assertTrue(review.contains("Questionable/unverified alias")); assertTrue(review.contains("unsupported claims"));
    }
    @Test void exportIsByteIdenticalOnRetryAndChangedVersionCannotOverwrite() throws Exception {
        JsonObject c = capture(), p = proposals(); export(c, p); Map<String, byte[]> before = IntakeFiles.tree(root.resolve("export")); export(c, p);
        for (var e : before.entrySet()) assertArrayEquals(e.getValue(), IntakeFiles.tree(root.resolve("export")).get(e.getKey()));
        assertThrows(IOException.class, () -> export(c, Json.createObjectBuilder(p).add("version", "1.0.1").build()));
        assertEquals("1.0.0", IntakeFiles.read(root.resolve("export/private/pack.json")).getString("version"));
    }
    @Test void identityConflictsAcrossDestinationsAndActiveVersionsAreRejected() throws Exception {
        JsonObject c = capture(), p = proposals(); export(c, p);
        PackCatalog active = PackCatalog.load(Path.of("data/packs/catalog.json"));
        assertDoesNotThrow(() -> DraftExporter.guardIdentity(DraftExporter.build(c, p, budget()), root, active, budget()));
        JsonObject changed = Json.createObjectBuilder(p).add("description", "Changed without a version bump").build();
        assertThrows(IOException.class, () -> DraftExporter.guardIdentity(DraftExporter.build(c, changed, budget()), root, active, budget()));
        JsonObject collision = Json.createObjectBuilder(p).add("id", "music").build();
        assertThrows(IOException.class, () -> DraftExporter.guardIdentity(DraftExporter.build(c, collision, budget()), root, active, budget()));
        JsonObject newVersion = Json.createObjectBuilder(collision).add("version", "1.0.1").build();
        assertDoesNotThrow(() -> DraftExporter.guardIdentity(DraftExporter.build(c, newVersion, budget()), root, active, budget()));
    }
    @Test void interruptedPublicationRemovesStageAndPreservesEarlierOutput() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>(); files.put("one.txt", new byte[]{1}); files.put("two.txt", new byte[]{2});
        WikiApi.Budget interrupted = new WikiApi.Budget(null) {
            int checks;
            @Override void check() throws IOException { super.check(); if (++checks == 4) throw new InterruptedIOException("Synthetic cancellation immediately before commit"); }
        };
        assertThrows(InterruptedIOException.class, () -> IntakeFiles.publish(root.resolve("interrupted"), files, interrupted)); assertFalse(Files.exists(root.resolve("interrupted")));
        try (var paths = Files.list(root)) { assertTrue(paths.noneMatch(p -> p.getFileName().toString().contains("staging"))); }
        IntakeFiles.publish(root.resolve("prior"), files, budget());
        Path marker = root.resolve("cancel"); Files.createFile(marker);
        assertThrows(InterruptedIOException.class, () -> IntakeFiles.publish(root.resolve("prior"), Map.of("one.txt", new byte[]{9}), new WikiApi.Budget(marker)));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(root.resolve("prior/one.txt")));
    }
    @Test void pathTraversalSymlinksAndAlteredSnapshotsFail() throws Exception {
        assertThrows(IOException.class, () -> IntakeFiles.publish(root.resolve("escape"), Map.of("../outside", new byte[1]), budget())); assertFalse(Files.exists(root.resolve("outside")));
        Files.createSymbolicLink(root.resolve("link"), root.resolve("outside")); assertThrows(IOException.class, () -> IntakeFiles.safe(root.resolve("link/file")));
        export(capture(), proposals()); Path snapshot = root.resolve("export/snapshots/fixture-wp-1.txt"); Files.writeString(snapshot, "Altered");
        assertThrows(IOException.class, () -> DraftExporter.validate(root.resolve("export")));
    }
    @Test void realAuthoringDemoIsReconstructibleDraftAndNotActiveContent() throws Exception {
        Path demo = Path.of("drafts/jovian-v1/export"); PackCatalog catalog = DraftExporter.validate(demo);
        assertEquals(8, catalog.collections.values().iterator().next().sources.size()); PackCatalog.Pack pack = catalog.topics.get("jovian-system");
        assertEquals(5, pack.clues.size()); assertTrue(pack.eligible("standard").isEmpty());
        assertFalse(PackCatalog.load(Path.of("data/packs/catalog.json")).topics.containsKey("jovian-system"));
    }
}
