package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class GamePersistenceTest {
    @TempDir Path temp;
    PackCatalog catalog;
    GameService service;
    String owner;
    final String target = GameStore.identity("persistence-tests");
    static String uuid() { return UUID.randomUUID().toString(); }
    @BeforeEach void setup() throws Exception { temp = temp.toRealPath(); catalog = PackCatalog.load(Path.of("data/packs/catalog.json")); }
    @AfterEach void cleanup() { if (service != null) service.close(); }
    GameStore store() throws Exception { return new GameStore(temp.resolve("private"), target); }
    void open(GameService.ScopedComputer computer) throws Exception {
        service = new GameService(catalog, computer).durable(store());
        if (owner == null) owner = service.openSession(null); else assertEquals(owner, service.openSession(owner));
    }
    void restart(GameService.ScopedComputer computer) throws Exception { service.close(); open(computer); }
    JsonObject start(String intention, boolean replay) { return service.startGame(owner, intention, "music", "easy", "1.0.0", replay); }
    JsonObject start() { return start(uuid(), false); }
    JsonObject ready(JsonObject match) throws Exception {
        GameServiceTest.ready(service, owner, match.getJsonObject("round").getString("id")); return service.getGame(owner, match.getString("id"));
    }
    String correct(JsonObject match) {
        String text = match.getJsonObject("round").getString("clue");
        return catalog.topics.get("music").clues.stream().filter(c -> c.text.equals(text)).findFirst().orElseThrow().aliases.get(0);
    }
    JsonObject answer(JsonObject match) throws Exception {
        match = ready(match); return service.answerGame(owner, match.getString("id"), match.getJsonObject("round").getString("id"), correct(match));
    }
    JsonObject complete(JsonObject match) throws Exception {
        for (int i = match.getInt("clueNumber"); i <= 5; i++) {
            String round = match.getJsonObject("round").getString("id"); match = answer(match);
            if (i < 5) match = service.advanceGame(owner, match.getString("id"), round);
        }
        return match;
    }
    Path stateFile() { return temp.resolve("private").resolve(target).resolve("state.json"); }

    @Test void committedGuessAndExactReceiptSurviveRestartsWithoutAnotherSearch() throws Exception {
        AtomicInteger calls = new AtomicInteger(); open((s,c,q) -> { calls.incrementAndGet(); return GameServiceTest.response("Piano"); });
        String retry = uuid(); JsonObject match = ready(start(retry, false)); String id = match.getString("id");
        restart((s,c,q) -> { fail("Committed guess was recomputed"); return null; });
        assertEquals(match, service.getGame(owner, id));
        JsonObject scored = answer(match); JsonObject receipt = scored.getJsonObject("round");
        restart((s,c,q) -> { fail("Finalized clue was recomputed"); return null; });
        assertEquals(scored, service.getGame(owner,id)); assertEquals(scored,start(retry,false));
        assertEquals(receipt, service.submit(owner,receipt.getString("id"),"Changed answer")); assertEquals(1,calls.get());
        assertFalse(Files.readString(stateFile()).contains(owner));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(stateFile()));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(stateFile().getParent()));
    }

    @Test void thinkingRecoveryIsInterruptedOnceAndStillScoresThePlayer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        open((s,c,q) -> { entered.countDown(); new CountDownLatch(1).await(); return null; });
        JsonObject match = start(); assertTrue(entered.await(2,TimeUnit.SECONDS));
        restart((s,c,q) -> { fail("Interrupted round was rerun"); return null; });
        JsonObject scored = answer(match), receipt = scored.getJsonObject("round");
        assertEquals("interrupted",receipt.getString("computerStatus")); assertEquals(0,receipt.getInt("computerPoints"));
        assertEquals(100,receipt.getInt("playerPoints")); assertTrue(receipt.isNull("evidence"));
        restart((s,c,q) -> null); assertEquals(scored,service.getGame(owner,match.getString("id")));
    }

    @Test void allFiveRoundsCompletionReplayAndOldRetryRemainStableAcrossEveryRestart() throws Exception {
        open((s,c,q) -> GameServiceTest.response("Piano")); String retry=uuid(); JsonObject match=start(retry,false);
        List<String> opened = new ArrayList<>();
        for(int i=0;i<5;i++){
            String round=match.getJsonObject("round").getString("id");opened.add(round);match=answer(match);
            restart((s,c,q)->GameServiceTest.response("Piano"));assertEquals(match,service.getGame(owner,match.getString("id")));
            if(i<4){ match=service.advanceGame(owner,match.getString("id"),round); ready(match);
                restart((s,c,q)->GameServiceTest.response("Piano"));
                assertEquals(i+2,service.advanceGame(owner,match.getString("id"),round).getInt("clueNumber")); }
        }
        assertEquals("COMPLETED",match.getString("state"));assertEquals(500,match.getInt("playerScore"));
        JsonObject receipt=match;String replayId=uuid();JsonObject replay=ready(start(replayId,true));
        restart((s,c,q)->GameServiceTest.response("Piano"));assertEquals(replay,start(replayId,true));
        assertEquals(receipt,start(retry,false));assertEquals(0,replay.getInt("playerScore"));
        assertEquals(500,service.current(owner).getJsonObject("sessionTotals").getInt("player"));
        service.answerGame(owner,receipt.getString("id"),opened.get(0),"Late changed guess");
        assertEquals(replay.getString("id"),service.current(owner).getJsonObject("game").getString("id"));
    }

    @Test void cyclesAndReservedFutureCluesDoNotReshuffleOnRestart() throws Exception {
        open((s,c,q)->GameServiceTest.response(null));JsonObject match=start();ready(match);service.close();
        JsonObject before;try(GameStore store=store()){before=store.read();}
        open((s,c,q)->GameServiceTest.response(null));service.close();
        try(GameStore store=store()) { assertEquals(before,store.read()); }
    }

    @Test void restoredPreRevealResponsesStillExcludeAnswersCandidatesAndFutureClues() throws Exception {
        open((s,c,q)->GameServiceTest.response("Piano"));JsonObject match=ready(start());restart((s,c,q)->null);
        String publicJson=service.current(owner).toString();
        for(String forbidden:List.of("acceptedAnswers","candidate","support","aliases","answerSourceId","requestId","clues"))
            assertFalse(publicJson.contains("\""+forbidden+"\""),forbidden);
        assertEquals(match,service.getGame(owner,match.getString("id")));
    }

    @Test void ownedCookiesNotGameIdsAuthorizeRestoredState() throws Exception {
        open((s,c,q)->GameServiceTest.response(null));JsonObject match=ready(start());restart((s,c,q)->null);
        String other=service.openSession(null);
        assertEquals(404,assertThrows(GameService.GameError.class,()->service.getGame(other,match.getString("id"))).status);
        assertEquals(410,assertThrows(GameService.GameError.class,()->service.openSession(uuid())).status);
        assertEquals(410,assertThrows(GameService.GameError.class,()->service.getGame(match.getString("id"),match.getString("id"))).status);
        assertNotEquals(owner,other);assertEquals(match,service.getGame(owner,match.getString("id")));
    }

    static final class MutableClock extends Clock {
        long now=1_800_000_000_000L;
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now);}
    }
    @Test void absoluteExpiryIsNotExtendedByReadsOrRestart() throws Exception {
        MutableClock clock=new MutableClock();service=new GameService(catalog,(s,c,q)->GameServiceTest.response(null),clock,15000).durable(store());owner=service.openSession(null);
        JsonObject match=ready(start());clock.now+=GameService.SESSION_MS-1000;assertEquals(1,service.remainingSeconds(owner));service.current(owner);service.close();
        service=new GameService(catalog,(s,c,q)->null,clock,15000).durable(store());assertEquals(match,service.getGame(owner,match.getString("id")));
        clock.now+=1000;assertEquals(410,assertThrows(GameService.GameError.class,()->service.current(owner)).status);
        service.close();service=new GameService(catalog,(s,c,q)->null,clock,15000).durable(store());assertEquals(410,assertThrows(GameService.GameError.class,()->service.openSession(owner)).status);
        assertNotEquals(owner,service.openSession(null));
    }

    @Test void failedScoringWriteBlocksAllViewsAndRestartRestoresOldScores() throws Exception {
        AtomicBoolean fail=new AtomicBoolean();
        service=new GameService(catalog,(s,c,q)->GameServiceTest.response("Piano")).durable(new GameStore(temp.resolve("private"),target,phase->{if(fail.get()&&phase.equals("before-replace"))throw new IOException("fixture disk full");}));
        owner=service.openSession(null);JsonObject match=ready(start());fail.set(true);
        assertEquals(503,assertThrows(GameService.GameError.class,()->answer(match)).status);
        assertEquals(503,assertThrows(GameService.GameError.class,()->service.current(owner)).status);
        restart((s,c,q)->null);assertEquals(match,service.getGame(owner,match.getString("id")));assertEquals(100,answer(match).getInt("playerScore"));
    }

    @Test void lostAcknowledgementAfterReplacementRecoversAwardedOnce() throws Exception {
        AtomicBoolean fail=new AtomicBoolean();
        service=new GameService(catalog,(s,c,q)->GameServiceTest.response("Piano")).durable(new GameStore(temp.resolve("private"),target,phase->{if(fail.get()&&phase.equals("after-replace"))throw new IOException("fixture lost ack");}));
        owner=service.openSession(null);JsonObject match=ready(start());fail.set(true);
        assertEquals(503,assertThrows(GameService.GameError.class,()->answer(match)).status);restart((s,c,q)->null);
        JsonObject recovered=service.getGame(owner,match.getString("id"));assertEquals(100,recovered.getInt("playerScore"));
        assertEquals(recovered,service.answerGame(owner,match.getString("id"),match.getJsonObject("round").getString("id"),"Different"));
    }

    @Test void interruptedStageAndPreSwitchFailuresRetainTheLastCommittedBytes() throws Exception {
        AtomicReference<String> fail=new AtomicReference<>();
        JsonObject old=Json.createObjectBuilder().add("value",1).build(), next=Json.createObjectBuilder().add("value",2).build();
        try(GameStore store=new GameStore(temp.resolve("private"),target,p->{if(p.equals(fail.get()))throw new IOException("interrupted fixture");})){
            store.write(old);
            for(String phase:List.of("before-write","before-force","before-replace")){fail.set(phase);assertThrows(IOException.class,()->store.write(next));assertEquals(old,store.read());}
        }
        Files.writeString(stateFile().resolveSibling("pending.json"),"partial");
        try(GameStore store=store()){assertEquals(old,store.read());assertFalse(Files.exists(stateFile().resolveSibling("pending.json")));}
    }

    @Test void lockConflictsAndTargetNamespacesAreIsolated() throws Exception {
        try(GameStore first=store()){
            assertThrows(IOException.class,this::store);
            try(GameStore second=new GameStore(temp.resolve("private"),GameStore.identity("other"))){second.write(Json.createObjectBuilder().add("other",true).build());assertNull(first.read());}
        }
        try(GameStore reopened=store()){assertNull(reopened.read());}
    }

    @Test void corruptUnknownSchemaWrongTargetAndOversizedSavesNeverReset() throws Exception {
        open((s,c,q)->null);service.close();byte[] original=Files.readAllBytes(stateFile());
        List<byte[]> bad=new ArrayList<>();bad.add("{broken".getBytes());
        JsonObject envelope=PackCatalog.read(stateFile());bad.add(IntakeFiles.json(Json.createObjectBuilder(envelope).add("schemaVersion",99).build()));
        bad.add(IntakeFiles.json(Json.createObjectBuilder(envelope).add("schemaVersion",1.5).build()));
        bad.add(IntakeFiles.json(Json.createObjectBuilder(envelope).add("target",GameStore.identity("foreign")).build()));
        bad.add(IntakeFiles.json(Json.createObjectBuilder(envelope).add("sha256","0".repeat(64)).build()));
        for(byte[] bytes:bad){Files.write(stateFile(),bytes);assertThrows(IOException.class,()->new GameService(catalog,(s,c,q)->null).durable(store()));assertArrayEquals(bytes,Files.readAllBytes(stateFile()));}
        try(var file=java.nio.channels.FileChannel.open(stateFile(),StandardOpenOption.WRITE)){file.position(GameStore.MAX_BYTES);file.write(java.nio.ByteBuffer.wrap(new byte[]{1}));}
        assertThrows(IOException.class,()->new GameService(catalog,(s,c,q)->null).durable(store()));
        Files.write(stateFile(),original);open((s,c,q)->null);
    }

    @Test void validChecksumCannotHideInconsistentScoresOrMissingPins() throws Exception {
        open((s,c,q)->GameServiceTest.response(null));ready(start());service.close();JsonObject saved;
        try(GameStore store=store()){saved=store.read();}
        JsonObject owners=saved.getJsonObject("sessions"),ownerRow=owners.getJsonObject(GameStore.identity(owner));
        JsonObject bad=Json.createObjectBuilder(saved).add("sessions",Json.createObjectBuilder(owners).add(GameStore.identity(owner),Json.createObjectBuilder(ownerRow).add("playerTotal",100))).build();
        try(GameStore store=store()){store.write(bad);}assertThrows(IOException.class,()->new GameService(catalog,(s,c,q)->null).durable(store()));
        try(GameStore store=store()){store.write(Json.createObjectBuilder(saved).add("packs",Json.createObjectBuilder()).build());}
        assertThrows(IOException.class,()->new GameService(catalog,(s,c,q)->null).durable(store()));
    }

    @Test void symlinksTraversalAndTargetDirectoryAreRejected() throws Exception {
        Path real=temp.resolve("real");Files.createDirectory(real);Path link=temp.resolve("link");Files.createSymbolicLink(link,real);
        assertThrows(IOException.class,()->new GameStore(link,target));
        assertThrows(IOException.class,()->new GameStore(temp.resolve("../escape"),target));
        assertThrows(IOException.class,()->new GameStore(temp.resolve("target/saves"),target));
        try(GameStore store=store()){Files.createSymbolicLink(stateFile(),temp.resolve("elsewhere"));assertThrows(IOException.class,store::read);}
    }

    @Test void productionStorageCannotPackagePrivateSavesOrWriteIntoReferences() throws Exception {
        assertEquals(GameStore.DEFAULT_ROOT,GameStore.productionRoot(GameStore.DEFAULT_ROOT));
        for(Path unsafe:List.of(Path.of("src/main/resources/web"),Path.of("data"),Path.of("indexes"),Path.of("target"),Path.of("drafts/local"),temp))
            assertThrows(IOException.class,()->GameStore.productionRoot(unsafe));
    }

    @Test void unreadableNonPrivateOrNonRegularSavesFailWithoutRepairOrReset() throws Exception {
        open((s,c,q)->null);service.close();
        for(String permissions:List.of("---------","rw-r--r--")){
            Files.setPosixFilePermissions(stateFile(),PosixFilePermissions.fromString(permissions));
            assertThrows(IOException.class,()->new GameService(catalog,(s,c,q)->null).durable(store()));
            assertEquals(PosixFilePermissions.fromString(permissions),Files.getPosixFilePermissions(stateFile()));
        }
        Files.setPosixFilePermissions(stateFile(),PosixFilePermissions.fromString("rw-------"));
        Files.delete(stateFile());Files.createDirectory(stateFile());
        assertThrows(IOException.class,()->new GameService(catalog,(s,c,q)->null).durable(store()));
        assertTrue(Files.isDirectory(stateFile()));
    }

    @Test void pinnedContentAndReceiptsSurviveRetiredTopicsAndMissingSourceFiles() throws Exception {
        open((s,c,q)->GameServiceTest.response("Piano"));JsonObject match=answer(start());String id=match.getString("id"), first=match.getJsonObject("round").getString("id");service.close();
        PackActivation.sandbox(Path.of("data/game.json"),temp.resolve("catalog"),temp.resolve("indexes"),18780);
        Path file=PackActivation.catalogPath(temp.resolve("catalog/game.json"));JsonObject catalogRow=IntakeFiles.read(file);JsonObjectBuilder changed=Json.createObjectBuilder(catalogRow);
        for(String field:List.of("packs","topics","collections")){
            JsonArrayBuilder keep=Json.createArrayBuilder();for(JsonValue v:catalogRow.getJsonArray(field))if(!((JsonObject)v).getString("id").startsWith("music"))keep.add(v);changed.add(field,keep);
        }
        Files.write(file,IntakeFiles.json(changed.build()));PackCatalog retired=PackCatalog.load(file);
        try(var paths=Files.walk(temp.resolve("catalog"))){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        service=new GameService(retired,new CollectionIndex(Map.of(),temp.resolve("missing"),1,4)::ask).durable(store());
        assertEquals(match,service.getGame(owner,id));match=service.advanceGame(owner,id,first);
        assertEquals("music",match.getString("topicId"));assertEquals("1.0.0",match.getString("packVersion"));
        JsonObject completed=complete(match);assertEquals(500,completed.getInt("playerScore"));assertEquals("COMPLETED",completed.getString("state"));
        assertEquals("unavailable",completed.getJsonArray("rounds").getJsonObject(1).getString("computerStatus"));
        restart((s,c,q)->{fail("Completed result was rerun");return null;});assertEquals(completed,service.getGame(owner,id));
    }

    @Test void legacyStandaloneQuestionGuessAndCursorRecoverWithoutNewSourceReads() throws Exception {
        Path questions=temp.resolve("questions.txt");Files.writeString(questions,"MUSIC\nA keyboard instrument\nPiano\n\nMUSIC\nA bowed instrument\nViolin\n");
        service=new GameService(questions,(c,q)->GameServiceTest.response("Piano")).durable(store());owner=service.openSession(null);JsonObject round=service.start(owner);GameServiceTest.ready(service,owner,round.getString("id"));
        JsonObject result=service.submit(owner,round.getString("id"),"Piano");service.close();
        service=new GameService(questions,(c,q)->GameServiceTest.response("Violin")).durable(store());assertEquals(result,service.get(owner,round.getString("id")));
        assertEquals("A bowed instrument",service.start(owner).getString("clue"));
    }

    @Test void actualCatalogRollbackKeepsTheReservedGameAndReceiptWithoutReregisteringRetiredIndexes() throws Exception {
        Path config=temp.resolve("rollback-player/game.json"),plan=temp.resolve("rollback-plan");
        PackActivation.sandbox(Path.of("data/game.json"),config.getParent(),temp.resolve("rollback-indexes"),18795);
        PackActivation.plan(config,Path.of("drafts/jovian-v2/export"),Path.of("drafts/jovian-v2/review.json"),"1.0.1",plan);
        PackActivation.apply(config,plan,false,p->{});GameConfig active=GameConfig.load(config);
        PackCatalog.Pack pinned=active.catalog.topics.get("jovian-system");
        service=new GameService(active.catalog,new CollectionIndex(active)::ask).durable(store());owner=service.openSession(null);
        String retry=uuid();JsonObject game=service.startGame(owner,retry,pinned.topicId,"standard",pinned.version,false);
        game=ready(game);String round=game.getJsonObject("round").getString("id"),text=game.getJsonObject("round").getString("clue");
        String guess=pinned.clues.stream().filter(c->c.text.equals(text)).findFirst().orElseThrow().aliases.get(0);
        game=service.answerGame(owner,game.getString("id"),round,guess);JsonObject first=game;service.close();
        PackActivation.apply(config,plan,true,p->{});GameConfig rolledBack=GameConfig.load(config);assertFalse(rolledBack.catalog.topics.containsKey(pinned.topicId));
        service=new GameService(rolledBack.catalog,new CollectionIndex(rolledBack)::ask).durable(store());
        assertEquals(first,service.getGame(owner,game.getString("id")));
        assertEquals(first,service.startGame(owner,retry,pinned.topicId,"standard",pinned.version,false));
        for(int i=1;i<5;i++){
            game=service.advanceGame(owner,game.getString("id"),round);game=ready(game);round=game.getJsonObject("round").getString("id");
            String clueText=game.getJsonObject("round").getString("clue");guess=pinned.clues.stream().filter(c->c.text.equals(clueText)).findFirst().orElseThrow().aliases.get(0);
            game=service.answerGame(owner,game.getString("id"),round,guess);
            JsonObject receipt=i==4?game.getJsonArray("rounds").getJsonObject(4):game.getJsonObject("round");
            assertEquals("unavailable",receipt.getString("computerStatus"));assertTrue(receipt.isNull("evidence"));
            assertEquals(pinned.version,receipt.getString("packVersion"));assertNotNull(receipt.getJsonObject("curatedEvidence"));
        }
        assertEquals(500,game.getInt("playerScore"));assertEquals("COMPLETED",game.getString("state"));
        service.close();service=new GameService(rolledBack.catalog,new CollectionIndex(rolledBack)::ask).durable(store());
        assertEquals(game,service.getGame(owner,game.getString("id")));assertEquals(first.getJsonObject("round"),game.getJsonArray("rounds").getJsonObject(0));
    }

    @Test void concurrentRestoredRetriesNeverDuplicateStartAdvanceOrScoring() throws Exception {
        open((s,c,q)->GameServiceTest.response("Piano"));String intention=uuid();JsonObject match=ready(start(intention,false));restart((s,c,q)->GameServiceTest.response("Piano"));
        ExecutorService pool=Executors.newFixedThreadPool(8);
        try{
            List<Future<JsonObject>> futures=new ArrayList<>();String id=match.getString("id"),round=match.getJsonObject("round").getString("id");
            String guess=correct(match);
            for(int i=0;i<20;i++)futures.add(pool.submit(()->{start(intention,false);return service.answerGame(owner,id,round,guess);}));
            for(var f:futures)assertEquals(100,f.get(5,TimeUnit.SECONDS).getInt("playerScore"));futures.clear();
            for(int i=0;i<20;i++)futures.add(pool.submit(()->service.advanceGame(owner,id,round)));
            Set<String> next=new HashSet<>();for(var f:futures){JsonObject nextGame=f.get(5,TimeUnit.SECONDS);assertEquals(2,nextGame.getInt("clueNumber"));next.add(nextGame.getJsonObject("round").getString("id"));}assertEquals(1,next.size());
        }finally{pool.shutdownNow();}
    }
}
