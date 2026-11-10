package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.store.FSDirectory;
import static org.junit.jupiter.api.Assertions.*;

class PackActivationTest {
    @TempDir Path root;
    Path config, plan, review, draft = Path.of("drafts/jovian-v2/export");
    @BeforeEach void setup() throws Exception {
        root = root.toRealPath();
        PackActivation.sandbox(Path.of("data/game.json"), root.resolve("game"), root.resolve("indexes"), 18765);
        config = root.resolve("game/game.json"); plan = root.resolve("plan"); review = root.resolve("review.json");
        Files.write(review, IntakeFiles.read(Path.of("drafts/jovian-v2/review.json"), 4 * 1024 * 1024));
        new CollectionIndex(GameConfig.load(config)).build(false);
    }
    JsonObject createPlan() throws Exception { return PackActivation.plan(config, draft, review, "1.0.1", plan); }
    JsonObject activate() throws Exception { return PackActivation.apply(config, plan, false, p -> {}); }
    JsonObject rollback() throws Exception { return PackActivation.apply(config, plan, true, p -> {}); }
    byte[] catalog() throws Exception { return Files.readAllBytes(PackActivation.catalogPath(config)); }
    JsonObject decision() throws IOException { return IntakeFiles.read(review); }
    void decision(JsonObject value) throws IOException { Files.write(review, IntakeFiles.json(value)); }
    JsonObject clueDecision(JsonObject value, JsonObject first) {
        JsonArrayBuilder rows = Json.createArrayBuilder().add(first); for (int i = 1; i < value.getJsonArray("clues").size(); i++) rows.add(value.getJsonArray("clues").get(i));
        return Json.createObjectBuilder(value).add("clues", rows).build();
    }
    @Test void reviewPreviewDistinguishesVersionsChangesAvailabilityAndLimitations() throws Exception {
        Path out = root.resolve("preview"); ContentReview.preview(Path.of("drafts/jovian-v1/export"), draft, review, out);
        JsonObject preview = IntakeFiles.read(out.resolve("preview.json"));
        assertEquals("1.0.0", preview.getString("beforeVersion")); assertEquals("1.0.1", preview.getString("draftVersion")); assertEquals("1.0.2", preview.getString("candidateVersion"));
        assertEquals(0, preview.getInt("draftEligible")); assertEquals(5, preview.getJsonObject("reviewedCandidateCounts").getInt("standard")); assertTrue(preview.getBoolean("fiveClueSelectionPossible"));
        assertTrue(preview.getJsonArray("blockingFailures").isEmpty()); assertTrue(preview.getString("advisoryLimitations").contains("0/60"));
        assertFalse(Files.readString(out.resolve("preview.html")).contains("<script"));
        assertEquals("1.0.2", PackCatalog.load(out.resolve("candidate/catalog.json")).topics.get("jovian-system").version);
        assertFalse(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
    }
    @Test void changedWordingSupportOffsetsAndUnchangedAliasesAreSourceBacked() throws Exception {
        PackCatalog old = DraftExporter.validate(Path.of("drafts/jovian-v1/export")), revised = DraftExporter.validate(draft);
        var a = old.topics.get("jovian-system").clues; var b = revised.topics.get("jovian-system").clues;
        for (int i = 0; i < 5; i++) { assertEquals(a.get(i).aliases, b.get(i).aliases); assertEquals("standard", b.get(i).difficulty); assertEquals(b.get(i).supportingPassage(), b.get(i).supportingSource.text.substring(b.get(i).passageStart, b.get(i).passageEnd)); }
        assertFalse(b.get(2).text.contains("Jupiter II")); assertEquals("", b.get(4).hint);
        assertTrue(b.get(3).supportingPassage().startsWith("Callisto")); assertTrue(b.get(3).passageStart < a.get(3).passageStart);
        assertEquals(a.get(3).passageEnd, b.get(3).passageEnd);
        for (var source : old.collections.values().iterator().next().sources.values()) assertEquals(source.snapshotSha256, revised.collections.values().iterator().next().sources.get(source.id).snapshotSha256);
    }
    @Test void unsupportedHumanStatusAndInventedHumanEvidenceAreRejected() throws Exception {
        JsonObject d = decision();
        assertThrows(IOException.class, () -> ContentReview.candidate(draft, Json.createObjectBuilder(d).add("status", "human-reviewed").build()));
        assertThrows(IOException.class, () -> ContentReview.candidate(draft, Json.createObjectBuilder(d).add("humanReviewer", "invented").build()));
        assertThrows(IOException.class, () -> ContentReview.candidate(draft, Json.createObjectBuilder(d).add("candidateVersion", "1.0.1").build()));
    }
    JsonObject sourceAdvisory(String sourceId, String reference) {
        return Json.createObjectBuilder().add("sourceId", sourceId).add("note", "assistantadvisorycanary: preserved quotation requires a context check.")
                .add("referenceUrl", reference).build();
    }
    @Test void reviewedSourceAdvisoryChangesOnlyNewMetadataAndPreservesSnapshotBytes() throws Exception {
        JsonObject original = IntakeFiles.read(draft.resolve("collection.json")), source = original.getJsonArray("sources").getJsonObject(0);
        JsonObject d = Json.createObjectBuilder(decision()).add("sourceAdvisories", Json.createArrayBuilder()
                .add(sourceAdvisory(source.getString("id"), "https://science.nasa.gov/"))).build();
        var candidate = ContentReview.candidate(draft, d);
        JsonObject revised = IntakeFiles.parse(IntakeFiles.utf8(candidate.files().get("collection.json")), "candidate collection");
        JsonObject next = revised.getJsonArray("sources").getJsonObject(0);
        assertEquals("1.0.2", revised.getString("version"));
        assertTrue(next.getString("revisionNote").contains("assistantadvisorycanary"));
        assertTrue(next.getString("revisionNote").startsWith(source.getString("revisionNote")));
        assertEquals(source.getString("url"), next.getString("url"));
        assertEquals(source.get("revisionId"), next.get("revisionId"));
        assertArrayEquals(Files.readAllBytes(draft.resolve(source.getString("snapshot"))), candidate.files().get(next.getString("snapshot")));
        assertEquals(original, IntakeFiles.read(draft.resolve("collection.json")));
    }
    @Test void unknownDuplicateUnsafeOrOversizeSourceAdvisoriesFailClosed() throws Exception {
        String sourceId = IntakeFiles.read(draft.resolve("collection.json")).getJsonArray("sources").getJsonObject(0).getString("id");
        for (String url : List.of("javascript:bad", "http://example.org/", "https://user:secret@example.org/", "/relative")) {
            JsonObject d = Json.createObjectBuilder(decision()).add("sourceAdvisories", Json.createArrayBuilder().add(sourceAdvisory(sourceId, url))).build();
            assertThrows(IOException.class, () -> ContentReview.candidate(draft, d));
        }
        JsonObject row = sourceAdvisory(sourceId, "https://science.nasa.gov/");
        for (JsonArray rows : List.of(Json.createArrayBuilder().add(row).add(row).build(),
                Json.createArrayBuilder().add(sourceAdvisory("missing-source", "https://science.nasa.gov/")).build(),
                Json.createArrayBuilder().add(Json.createObjectBuilder(row).add("note", "x".repeat(401))).build())) {
            assertThrows(IOException.class, () -> ContentReview.candidate(draft, Json.createObjectBuilder(decision()).add("sourceAdvisories", rows).build()));
        }
    }
    @Test void sourceAdvisoryStaysOutOfSearchFieldsAndChangesInvalidatePinnedPlan() throws Exception {
        String sourceId = IntakeFiles.read(draft.resolve("collection.json")).getJsonArray("sources").getJsonObject(0).getString("id");
        JsonObject d = Json.createObjectBuilder(decision()).add("sourceAdvisories", Json.createArrayBuilder()
                .add(sourceAdvisory(sourceId, "https://science.nasa.gov/"))).build();
        decision(d); createPlan();
        decision(Json.createObjectBuilder(d).add("sourceAdvisories", Json.createArrayBuilder()).build());
        assertThrows(IOException.class, this::activate);
        decision(d); activate();
        GameConfig active = GameConfig.load(config); var collection = active.catalog.topics.get("jovian-system").collection;
        CollectionIndex indexes = new CollectionIndex(active);
        try (Indexer index = new Indexer(indexes.base(collection.scope).toString(), 1);
             QueryEngine query = new QueryEngine(1, 4, index)) {
            assertTrue(query.search("assistantadvisorycanary", 10).isEmpty());
        }
    }
    @Test void everyScreeningFlagAndEveryClueMustBeReviewed() throws Exception {
        JsonObject d = decision(), first = d.getJsonArray("clues").getJsonObject(0);
        JsonObject missing = clueDecision(d, Json.createObjectBuilder(first).add("flagDecisions", Json.createArrayBuilder()).build());
        assertThrows(IOException.class, () -> ContentReview.candidate(draft, missing));
        assertThrows(IOException.class, () -> ContentReview.candidate(draft, Json.createObjectBuilder(d).add("clues", Json.createArrayBuilder().add(first)).build()));
    }
    @Test void unresolvedBlockersAreRecordedInPreviewAndBlockPlanning() throws Exception {
        JsonObject d = decision(), first = d.getJsonArray("clues").getJsonObject(0);
        decision(clueDecision(d, Json.createObjectBuilder(first).add("blockers", Json.createArrayBuilder().add("Unresolved support claim")).build()));
        Path out = root.resolve("blocked-preview");
        assertThrows(IOException.class, () -> ContentReview.preview(Path.of("drafts/jovian-v1/export"), draft, review, out));
        assertFalse(IntakeFiles.read(out.resolve("preview.json")).getJsonArray("blockingFailures").isEmpty()); assertFalse(Files.exists(out.resolve("candidate")));
        assertThrows(IOException.class, this::createPlan); assertFalse(Files.exists(plan));
    }
    @Test void planIsDryRunAndFreezesBeforeAfterInputsAndRequiredIndexes() throws Exception {
        byte[] before = catalog(); JsonObject p = createPlan(); assertArrayEquals(before, catalog());
        assertEquals(PackCatalog.sha256(before), p.getString("beforeCatalogSha256")); assertEquals(6, p.getJsonArray("requiredIndexes").size());
        assertTrue(p.getJsonArray("requiredIndexes").getJsonObject(5).getBoolean("requiredBuild"));
        assertEquals(p, createPlan()); assertFalse(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
        assertFalse(Files.exists(PackActivation.catalogPath(config).getParent().resolve(p.getString("release"))));
    }
    @Test void activationPreparesAllIndexesAndArtifactsThenSwitchesOnce() throws Exception {
        JsonObject p = createPlan(), result = activate(); assertEquals("switched", result.getString("outcome"));
        assertEquals(6, result.getJsonArray("indexes").size());
        Path intent = PackActivation.catalogPath(config).getParent().resolve(".activation-history/" + p.getString("planId") + "/activation/intent.json");
        assertEquals(result.getJsonArray("indexes"), IntakeFiles.read(intent).getJsonArray("indexes"));
        GameConfig active = GameConfig.load(config); assertEquals(6, active.catalog.topics.size()); assertEquals(5, active.catalog.topics.get("jovian-system").eligible("standard").size());
        assertEquals("source-checked", active.catalog.topics.get("jovian-system").review.getString("status")); assertEquals("1.0.2", active.catalog.topics.get("jovian-system").version);
        for (var collection : active.catalog.collections.values()) new CollectionIndex(active).verify(collection);
        assertEquals(result, activate()); assertEquals(p.getString("afterCatalogSha256"), PackCatalog.sha256(catalog()));
    }
    @Test void rollbackRestoresExactCatalogButRetainsImmutableSourcesIndexesAndReceipts() throws Exception {
        byte[] before = catalog(); JsonObject p = createPlan(); JsonObject receipt = activate();
        Path release = PackActivation.catalogPath(config).getParent().resolve(p.getString("release")); assertTrue(Files.isDirectory(release));
        JsonObject undone = rollback(); assertArrayEquals(before, catalog()); assertFalse(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
        assertTrue(Files.isDirectory(release)); assertEquals(undone, rollback()); assertEquals(receipt, activate()); assertArrayEquals(before, catalog());
        PackCatalog prepared = PackCatalog.load(plan.resolve("prepared/catalog.json"));
        for (var c : prepared.collections.values()) new CollectionIndex(prepared.collections, GameConfig.load(config).indexBase, 1, 4).verify(c);
    }
    @Test void staleCatalogAndChangedConfigFailWithoutSwitching() throws Exception {
        createPlan(); Path target = PackActivation.catalogPath(config);
        Files.write(target, IntakeFiles.json(Json.createObjectBuilder(IntakeFiles.read(target)).add("version", "1.0.9").build())); byte[] modified = catalog();
        assertThrows(IOException.class, this::activate); assertArrayEquals(modified, catalog());
        Files.write(config, IntakeFiles.json(Json.createObjectBuilder(IntakeFiles.read(config)).add("port", 18766).build())); assertThrows(IOException.class, this::activate);
    }
    @Test void modifiedPreparedArtifactOrPlanIsRejected() throws Exception {
        createPlan(); byte[] before = catalog(); Path snapshot;
        try (var paths = Files.list(plan.resolve("prepared/snapshots"))) { snapshot = paths.filter(Files::isRegularFile).findFirst().orElseThrow(); }
        Files.writeString(snapshot, "Changed without review"); assertThrows(IOException.class, this::activate); assertArrayEquals(before, catalog());
    }
    @Test void modifiedOriginalReviewAndDraftAreStaleEvenWithPreparedCopies() throws Exception {
        createPlan(); decision(Json.createObjectBuilder(decision()).add("limitations", "Modified after plan").build());
        assertThrows(IOException.class, this::activate);
        assertFalse(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
    }
    @Test void changedActiveReferencedArtifactsAreRejected() throws Exception {
        createPlan(); Path source = PackActivation.catalogPath(config).getParent().resolve("snapshots/music/piano.txt"); Files.writeString(source, "Unreviewed change");
        byte[] before = catalog(); assertThrows(IOException.class, this::activate); assertArrayEquals(before, catalog());
    }
    @Test void failureBeforeSwitchLeavesOldGameUsableAndRecordsFailure() throws Exception {
        byte[] before = catalog(); JsonObject p = createPlan();
        assertThrows(IOException.class, () -> PackActivation.apply(config, plan, false, phase -> { if (phase.equals("before-switch")) throw new IOException("Synthetic pre-commit failure"); }));
        assertArrayEquals(before, catalog()); GameConfig old = GameConfig.load(config); assertEquals(5, old.catalog.topics.size());
        for (var c : old.catalog.collections.values()) new CollectionIndex(old).verify(c);
        Path journal = PackActivation.catalogPath(config).getParent().resolve(".activation-history/" + p.getString("planId") + "/activation");
        assertTrue(Files.exists(journal.resolve("intent.json"))); assertTrue(Files.isDirectory(journal.resolve("failures"))); assertFalse(Files.exists(journal.resolve("result.json")));
        assertEquals("switched", activate().getString("outcome"));
    }
    @Test void lostPostSwitchReceiptRecoversCommittedActivation() throws Exception {
        createPlan(); assertThrows(IOException.class, () -> PackActivation.apply(config, plan, false, phase -> { if (phase.equals("after-switch")) throw new IOException("Synthetic lost post-commit outcome"); }));
        assertTrue(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
        JsonObject recovered = activate(); assertEquals("recovered-after-switch", recovered.getString("outcome")); assertEquals(recovered, activate());
    }
    @Test void lastMomentCatalogChangeIsNotOverwritten() throws Exception {
        createPlan(); Path target = PackActivation.catalogPath(config);
        assertThrows(IOException.class, () -> PackActivation.apply(config, plan, false, phase -> {
            if (phase.equals("before-switch")) Files.write(target, IntakeFiles.json(Json.createObjectBuilder(IntakeFiles.read(target)).add("version", "1.0.9").build()));
        }));
        assertEquals("1.0.9", GameConfig.load(config).catalog.version); assertFalse(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
    }
    @Test void lostRollbackReceiptRecoversWithoutApplyingAnotherTransition() throws Exception {
        byte[] before = catalog(); createPlan(); activate();
        assertThrows(IOException.class, () -> PackActivation.apply(config, plan, true, phase -> { if (phase.equals("after-switch")) throw new IOException("Lost rollback result"); }));
        assertArrayEquals(before, catalog()); assertEquals("recovered-after-switch", rollback().getString("outcome"));
    }
    @Test void wrongOrIncompleteRollbackTargetsAreRejected() throws Exception {
        createPlan(); assertThrows(IOException.class, this::rollback); activate();
        Path snapshot;
        try (var paths = Files.walk(plan.resolve("before/snapshots"))) { snapshot = paths.filter(Files::isRegularFile).findFirst().orElseThrow(); }
        Files.delete(snapshot); byte[] active = catalog(); assertThrows(IOException.class, this::rollback); assertArrayEquals(active, catalog());
    }
    @Test void rollbackRequiresExistingCompatibleOldIndexes() throws Exception {
        createPlan(); activate(); GameConfig active = GameConfig.load(config); var old = active.catalog.topics.get("music").collection;
        CollectionIndex index = new CollectionIndex(active); Path base = index.base(old.scope);
        Files.move(base, base.resolveSibling("removed-index")); byte[] current = catalog();
        assertThrows(IOException.class, this::rollback); assertArrayEquals(current, catalog());
    }
    @Test void conflictingHistoricalVersionIsNotOverwrittenAfterRollback() throws Exception {
        createPlan(); activate(); rollback(); decision(Json.createObjectBuilder(decision()).add("limitations", "Different same-version content").build());
        assertThrows(IOException.class, () -> PackActivation.plan(config, draft, review, "1.0.2", root.resolve("conflict-plan")));
    }
    @Test void rolledBackCatalogVersionCannotBeReusedForDifferentContent() throws Exception {
        createPlan(); activate(); rollback();
        decision(Json.createObjectBuilder(decision()).add("candidateVersion", "1.0.3").build());
        assertThrows(IOException.class, () -> PackActivation.plan(config, draft, review, "1.0.1", root.resolve("bad-version-plan")));
        assertDoesNotThrow(() -> PackActivation.plan(config, draft, review, "1.0.2", root.resolve("new-version-plan")));
    }
    @Test void invalidIndexProvenanceBlocksActivationBeforeCatalogSwitch() throws Exception {
        JsonObject p = createPlan(); PackCatalog candidate = PackCatalog.load(plan.resolve("prepared/catalog.json")); var source = candidate.topics.get("jovian-system").collection;
        CollectionIndex index = new CollectionIndex(candidate.collections, GameConfig.load(config).indexBase, 1, 4);
        try (Indexer bad = new Indexer(index.base(source.scope).toString(), 1)) { bad.parseWikis(Path.of("data/demo/corpus"), false); }
        byte[] before = catalog(); assertThrows(IOException.class, this::activate); assertArrayEquals(before, catalog());
        assertEquals(PackCatalog.sha256(before), p.getString("beforeCatalogSha256"));
    }
    @Test void atomicSwitchReadersObserveOnlyCompleteOldOrNewCatalog() throws Exception {
        createPlan(); Set<String> expected = Set.of(PackCatalog.sha256(catalog()), IntakeFiles.read(plan.resolve("plan.json")).getString("afterCatalogSha256"));
        ExecutorService reader = Executors.newSingleThreadExecutor(); AtomicBoolean stop = new AtomicBoolean();
        Future<?> reads = reader.submit(() -> { try { while (!stop.get()) { byte[] bytes = Files.readAllBytes(PackActivation.catalogPath(config)); assertTrue(expected.contains(PackCatalog.sha256(bytes))); IntakeFiles.parse(IntakeFiles.utf8(bytes), "atomic reader"); } } catch (IOException e) { throw new RuntimeException(e); } });
        try { activate(); rollback(); } finally { stop.set(true); reads.get(5, TimeUnit.SECONDS); reader.shutdownNow(); }
    }
    @Test void concurrentCommandsDoNotCreateAdditionalTransitions() throws Exception {
        createPlan(); ExecutorService clients = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) results.add(clients.submit(() -> { start.await(); try { activate(); return true; } catch (IOException locked) { return false; } }));
            start.countDown(); int success = 0; for (var result : results) if (result.get(10, TimeUnit.SECONDS)) success++;
            assertTrue(success >= 1); assertEquals("1.0.1", GameConfig.load(config).catalog.version); assertEquals(activate(), activate());
        } finally { clients.shutdownNow(); }
    }
    @Test void startupLoadedCatalogsDoNotHotReloadAndRestartIsExplicit() throws Exception {
        GameConfig before = GameConfig.load(config); createPlan(); JsonObject result = activate(); GameConfig after = GameConfig.load(config);
        assertFalse(before.catalog.topics.containsKey("jovian-system")); assertTrue(after.catalog.topics.containsKey("jovian-system"));
        assertTrue(result.getBoolean("restartRequired")); assertFalse(result.getBoolean("restartClearsInMemorySessions"));
        assertFalse(IntakeFiles.read(plan.resolve("plan.json")).getBoolean("restartClearsInMemorySessions")); rollback();
        assertTrue(after.catalog.topics.containsKey("jovian-system")); assertFalse(GameConfig.load(config).catalog.topics.containsKey("jovian-system"));
    }
    @Test void sourceOnlyIndexNeverContainsReviewOrAnswerMetadata() throws Exception {
        decision(Json.createObjectBuilder(decision()).add("limitations", "privateactivationcanary; no human approval").build()); createPlan(); activate();
        GameConfig active = GameConfig.load(config); var collection = active.catalog.topics.get("jovian-system").collection; CollectionIndex indexes = new CollectionIndex(active);
        try (Indexer index = new Indexer(indexes.base(collection.scope).toString(), 1); FSDirectory directory = FSDirectory.open(Path.of(index.getIndexPath())); DirectoryReader reader = DirectoryReader.open(directory)) {
            assertEquals(8, reader.numDocs());
            for (int i = 0; i < reader.maxDoc(); i++) { var doc = reader.storedFields().document(i); assertFalse(doc.get("evidence").contains("privateactivationcanary")); for (String field : List.of("aliases", "support", "clueId", "review", "authoring")) assertNull(doc.get(field)); }
        }
    }
    @Test void insufficientReviewedContentCannotProduceAPlan() throws Exception {
        JsonObject original = IntakeFiles.read(draft.resolve("private/proposals.json")); JsonArrayBuilder clues = Json.createArrayBuilder();
        for (int i = 0; i < 4; i++) clues.add(original.getJsonArray("clues").get(i));
        Path shortDraft = root.resolve("short-draft"); DraftExporter.export(IntakeFiles.read(draft.resolve("provenance/capture.json")), Json.createObjectBuilder(original).add("clues", clues).build(), shortDraft, new WikiApi.Budget(null));
        JsonObject d = decision(); JsonArrayBuilder decisions = Json.createArrayBuilder(); for (int i = 0; i < 4; i++) decisions.add(d.getJsonArray("clues").get(i));
        decision(Json.createObjectBuilder(d).add("clues", decisions).add("draftManifestSha256", PackCatalog.sha256(Files.readAllBytes(shortDraft.resolve("manifest.json")))).build());
        assertThrows(IOException.class, () -> PackActivation.plan(config, shortDraft, review, "1.0.1", plan)); assertFalse(Files.exists(plan));
    }
    @Test void pathEscapesAndSameIndexSandboxAreRejected() throws Exception {
        assertThrows(IOException.class, () -> PackActivation.resolve(root, "../escape"));
        assertThrows(IOException.class, () -> PackActivation.sandbox(config, root.resolve("bad-sandbox"), GameConfig.load(config).indexBase, 18766));
    }
}
