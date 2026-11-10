package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TopicRequestsTest {
    @TempDir Path root;
    TopicRequests store;
    MutableClock clock=new MutableClock();
    Path config;
    GameConfig loaded;
    String owner,key;
    PlayerReadiness presence;
    GameServer server;
    TopicBuilder workshop;
    static class MutableClock extends Clock {
        long millis=Instant.parse("2026-11-06T12:00:00Z").toEpochMilli();
        public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return Instant.ofEpochMilli(millis);} public long millis(){return millis;}
    }
    @BeforeEach void setup() throws Exception {
        root=root.toRealPath();store=new TopicRequests(root.resolve("requests"),clock);owner=store.createOwner();
        PackActivation.sandbox(Path.of("data/game.json"),root.resolve("player"),root.resolve("indexes"),18765);config=root.resolve("player/game.json");loaded=GameConfig.load(config);key=PlayerReadiness.targetKey(config);
    }
    @AfterEach void cleanup() throws Exception {if(server!=null)server.close();if(presence!=null)presence.close();if(workshop!=null)workshop.close();}
    String uuid(){return UUID.randomUUID().toString();}
    JsonObject submit(String text,String difficulty)throws Exception{return store.submit(owner,key,uuid(),text,difficulty);}
    void running()throws Exception{new CollectionIndex(loaded).build(false);presence=new PlayerReadiness(store.root,loaded);presence.started();}
    JsonObject music(){return PlayerReadiness.binding(loaded.catalog.topics.get("music"),"standard");}
    JsonObject check(JsonObject binding){return PlayerReadiness.check(store.root,config,binding);}
    void assertNotReady(JsonObject check){assertFalse(check.getBoolean("ready"),check.toString());}
    TopicBuilder builder()throws Exception{return new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",config,"isolated",config),TopicBuilder.examples(),()->{throw new IOException("No fetching during request submission");},store);}
    JsonObject job(String op,JsonObject body){return Json.createObjectBuilder(body).add("requestId",uuid()).add("operation",op).build();}
    JsonObject done(JsonObject input)throws Exception{
        String id=workshop.submit(input).getString("id");for(int i=0;i<1500;i++){JsonObject result=workshop.status(id);if(!Set.of("queued","running","cancelling").contains(result.getString("state"))){Thread.sleep(20);return result;}Thread.sleep(10);}throw new AssertionError("Job timeout");
    }
    @Test void ownershipRequiresSecretNotOpaqueIdAndDoesNotLeakOtherOwners()throws Exception{
        JsonObject r=submit("Mercury","hard");String other=store.createOwner();
        assertEquals(0,store.list(other,key).size());assertThrows(GameService.GameError.class,()->store.get(other,key,r.getString("id")));
        assertThrows(GameService.GameError.class,()->store.get(owner.split("\\.")[0]+"."+TopicBuilderServer.secret(),key,r.getString("id")));
        assertThrows(GameService.GameError.class,()->store.cancel(other,key,r.getString("id"),uuid()));assertEquals("requested",store.get(owner,key,r.getString("id")).getString("phase"));
    }
    @Test void sameRetryAndEquivalentPendingRequestsDeduplicateOnlyWithinOwnerAndTarget()throws Exception{
        String retry=uuid();JsonObject a=store.submit(owner,key,retry,"Music","standard");assertEquals(a,store.submit(owner,key,retry,"Music","standard"));
        assertEquals(a.getString("id"),submit("music","standard").getString("id"));assertThrows(GameService.GameError.class,()->store.submit(owner,key,retry,"History","standard"));
        assertNotEquals(a.getString("id"),store.submit(store.createOwner(),key,uuid(),"Music","standard").getString("id"));
        assertNotEquals(a.getString("id"),store.submit(owner,"another-target",uuid(),"Music","standard").getString("id"));
    }
    @Test void concurrentSubmissionRetriesCreateOneRecord()throws Exception{
        String retry=uuid();ExecutorService pool=Executors.newFixedThreadPool(8);Set<String> ids=new HashSet<>();try{List<Future<JsonObject>> calls=new ArrayList<>();for(int i=0;i<12;i++)calls.add(pool.submit(()->store.submit(owner,key,retry,"Music","standard")));for(var call:calls)ids.add(call.get().getString("id"));}finally{pool.shutdownNow();}assertEquals(1,ids.size());assertEquals(1,store.list(owner,key).size());
    }
    @Test void textDifficultyPendingRetainedAndRateBoundsAreExplicit()throws Exception{
        for(String text:List.of("","!!!","https://example.org","../private","<script>","x".repeat(121)))assertThrows(GameService.GameError.class,()->submit(text,"standard"));
        assertThrows(GameService.GameError.class,()->submit("Music","expert"));
        List<JsonObject> requests=new ArrayList<>();for(int i=0;i<4;i++)requests.add(submit("Topic "+i,"standard"));assertThrows(GameService.GameError.class,()->submit("Fifth","standard"));
        for(JsonObject r:requests)store.cancel(owner,key,r.getString("id"),uuid());submit("Fifth","standard");assertThrows(GameService.GameError.class,()->submit("Sixth","standard"));
        clock.millis+=TopicRequests.RATE_WINDOW+1;for(int i=0;i<3;i++){JsonObject r=submit("Later "+i,"standard");store.cancel(owner,key,r.getString("id"),uuid());}assertThrows(GameService.GameError.class,()->submit("Ninth","standard"));
    }

    @Test void plainTextTopicSubtitlesAllowColonsButStillRejectSchemesAndMarkup()throws Exception{
        String topic="Kanye West: Albums and Collaborators";
        assertEquals(topic,submit(topic,"standard").getString("topic"));
        for(String invalid:List.of("javascript: alert(1)","data: text/plain", "mailto: person@example.com", "https: example.org", "file: private", "<b>Music:</b>"))
            assertThrows(GameService.GameError.class,()->submit(invalid,"standard"));
    }
    @Test void ownerAndRequestSurviveRestartIndependentlyOfTwoHourGamesThenExpire()throws Exception{
        JsonObject r=submit("Music","standard");TopicRequests reopened=new TopicRequests(store.root,clock);clock.millis+=Duration.ofHours(3).toMillis();assertEquals(r,reopened.get(owner,key,r.getString("id")));
        clock.millis+=TopicRequests.TTL;assertThrows(GameService.GameError.class,()->reopened.get(owner,key,r.getString("id")));String fresh=reopened.createOwner();assertTrue(reopened.list(fresh,key).isEmpty());
    }
    @Test void ownerCreationAndMutationStorageHaveBoundsAndPrivatePermissions()throws Exception{
        for(int i=0;i<7;i++)store.createOwner();assertThrows(GameService.GameError.class,store::createOwner);
        JsonObject r=submit("Music","standard");for(int i=0;i<31;i++)store.submit(owner,key,uuid(),"Music","standard");assertThrows(GameService.GameError.class,()->store.submit(owner,key,uuid(),"Music","standard"));
        assertEquals("rwx------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(store.root)));assertEquals(r.getString("id"),store.list(owner,key).get(0).getString("id"));
    }
    @Test void cancellationIsTerminalUnderLateOperatorResultsAndOtherRequestsUnaffected()throws Exception{
        JsonObject a=submit("Music","standard"),b=submit("Geography","standard");String id=a.getString("id");store.operator(id,key,uuid(),"begin","Actual fixture work began.",null);String cancel=uuid();
        store.cancel(owner,key,id,cancel);String late=uuid();store.operator(id,key,late,"offer","Late fixture offer",music());store.operator(id,key,late,"offer","Late fixture offer",music());assertThrows(GameService.GameError.class,()->store.operator(id,key,late,"offer","Changed late offer",music()));store.operator(id,key,uuid(),"begin","Late fixture job",null);
        assertEquals("cancelled",store.cancel(owner,key,id,cancel).getString("phase"));assertEquals("requested",store.get(owner,key,b.getString("id")).getString("phase"));
        assertThrows(GameService.GameError.class,()->store.choose(owner,key,id,uuid(),music()));
    }
    @Test void clarificationUnavailableAndFailedAreHonestTerminalOutcomes()throws Exception{
        for(String phase:List.of("clarification","unavailable","failed")){JsonObject r=submit("Mercury "+phase,"hard");String retry=uuid();store.operator(r.getString("id"),key,retry,phase,"Please supply a more specific subject.",null);assertEquals(phase,store.operator(r.getString("id"),key,retry,phase,"Please supply a more specific subject.",null).getString("phase"));assertThrows(GameService.GameError.class,()->store.operator(r.getString("id"),key,retry,phase,"Changed message",null));}
    }
    @Test void submissionNeverPreparesFetchesOrChoosesAndOperatorCannotFakeBeginViaTransition()throws Exception{
        workshop=builder();JsonObject r=submit("Music","standard");assertEquals("requested",r.getString("phase"));assertTrue(workshop.catalog().getJsonArray("jobs").isEmpty());
        assertThrows(IOException.class,()->workshop.requestTransition(Json.createObjectBuilder().add("requestId",r.getString("id")).add("targetId","normal").add("retryId",uuid()).add("action","begin").add("message","Pretend preparation").build()));
        assertThrows(IOException.class,()->store.operator(r.getString("id"),key,uuid(),"awaiting-review","No work yet",null));
    }
    @Test void matchingUsesOnlyExplicitTopicMetadataAndRequiresPlayerChoice()throws Exception{
        running();assertThrows(IOException.class,()->new PlayerReadiness(store.root,loaded));
        assertTrue(PlayerReadiness.compatible(loaded.catalog.topics.get("world-history"),"history"));assertFalse(PlayerReadiness.compatible(loaded.catalog.topics.get("music"),"Mercury"));
        JsonObject r=submit("Music","standard");assertNotReady(PlayerReadiness.publicView(r,PlayerReadiness.result(false,"choice-required","Choose"),List.of(music())).getJsonObject("readiness"));
        r=store.choose(owner,key,r.getString("id"),uuid(),music());assertEquals("ready",PlayerReadiness.publicView(r,check(music()),List.of()).getString("state"));
    }
    @Test void noRunningPlayerOrOnlyConstructedLeaseCannotAdvertiseReady()throws Exception{
        new CollectionIndex(loaded).build(false);assertEquals("player-not-running",check(music()).getString("code"));presence=new PlayerReadiness(store.root,loaded);assertNotReady(check(music()));presence.started();assertTrue(check(music()).getBoolean("ready"));presence.close();presence=null;assertNotReady(check(music()));
    }
    @Test void failedShutdownMarkerStillReleasesLiveLease()throws Exception{
        running();Path identity=store.root.resolve("runtime/"+key+".json");Files.delete(identity);Files.createDirectory(identity);Files.writeString(identity.resolve("blocked"),"Force a metadata replacement failure");
        assertThrows(IOException.class,presence::close);presence=null;assertNotReady(check(music()));Files.delete(identity.resolve("blocked"));Files.delete(identity);
        presence=new PlayerReadiness(store.root,loaded);assertNotReady(check(music()));presence.started();assertTrue(check(music()).getBoolean("ready"));
    }
    @Test void onDiskChangeRequiresRestartEvenWhenPackVersionsAppearUnchanged()throws Exception{
        running();assertTrue(check(music()).getBoolean("ready"));Path catalog=PackActivation.catalogPath(config);JsonObject current=IntakeFiles.read(catalog);Files.write(catalog,IntakeFiles.json(Json.createObjectBuilder(current).add("version","1.0.1").build()));assertEquals("restart-required",check(music()).getString("code"));
    }
    @Test void staleOrMissingIndexesCannotAdvertiseReady()throws Exception{
        running();PackCatalog.Pack p=loaded.catalog.topics.get("music");CollectionIndex indexes=new CollectionIndex(loaded);try(Indexer indexer=new Indexer(indexes.base(p.collection.scope).toString(),1)){
            List<Indexer.Article> docs=p.collection.sources.values().stream().map(s->new Indexer.Article(s.id,s.title,s.category,s.text)).toList();indexer.indexArticles(docs,Map.of("collectionId",p.collection.scope.id,"collectionVersion",p.collection.scope.version,"collectionDigest","wrong"),true);
        }assertEquals("index-or-content-unavailable",check(music()).getString("code"));
    }
    Path prepareCandidate()throws Exception{Path plan=root.resolve("plan");PackActivation.plan(config,Path.of("drafts/jovian-v2/export"),Path.of("drafts/jovian-v2/review.json"),"1.0.1",plan);return plan;}
    @Test void draftAndIsolatedOnlyCandidatesCannotBecomeReadyForAnotherTarget()throws Exception{
        running();Path plan=prepareCandidate();PackCatalog candidate=PackCatalog.load(plan.resolve("prepared/catalog.json"));JsonObject selection=PlayerReadiness.binding(candidate.topics.get("jovian-system"),"standard");assertNotReady(check(selection));
        Path other=root.resolve("other/game.json");PackActivation.sandbox(config,other.getParent(),root.resolve("other-indexes"),18766);Path otherPlan=root.resolve("other-plan");PackActivation.plan(other,Path.of("drafts/jovian-v2/export"),Path.of("drafts/jovian-v2/review.json"),"1.0.1",otherPlan);PackActivation.apply(other,otherPlan,false,p->{});assertNotReady(check(selection));
        PackCatalog draft=DraftExporter.validate(Path.of("drafts/jovian-v2/export"));assertNotReady(check(PlayerReadiness.binding(draft.topics.get("jovian-system"),"standard")));
    }
    @Test void activationNotLoadedAndRollbackBothInvalidateReadiness()throws Exception{
        running();Path plan=prepareCandidate();PackCatalog candidate=PackCatalog.load(plan.resolve("prepared/catalog.json"));JsonObject selection=PlayerReadiness.binding(candidate.topics.get("jovian-system"),"standard");PackActivation.apply(config,plan,false,p->{});assertEquals("restart-required",check(selection).getString("code"));
        presence.close();presence=new PlayerReadiness(store.root,GameConfig.load(config));presence.started();assertTrue(check(selection).getBoolean("ready"));PackActivation.apply(config,plan,true,p->{});assertNotReady(check(selection));presence.close();presence=new PlayerReadiness(store.root,GameConfig.load(config));presence.started();assertNotReady(check(selection));
    }
    @Test void requestedDifficultyNeverFallsBackToCandidateOtherLevel()throws Exception{
        running();Path plan=prepareCandidate();PackActivation.apply(config,plan,false,p->{});presence.close();GameConfig candidate=GameConfig.load(config);presence=new PlayerReadiness(store.root,candidate);presence.started();assertNotReady(check(PlayerReadiness.binding(candidate.catalog.topics.get("jovian-system"),"hard")));
    }
    HttpClient client(){return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    HttpResponse<String> call(HttpClient client,String method,String path,JsonObject body,String origin)throws Exception{
        HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(server.url()+path));if(origin!=null)r.header("Origin",origin);if(body!=null)r.header("Content-Type","application/json").header("X-ClueLab-Request","1");r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString()));return client.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonObject json(HttpResponse<String> response)throws Exception{return IntakeFiles.parse(response.body(),"response");}
    void serve()throws Exception{new CollectionIndex(loaded).build(false);server=new GameServer(0,new GameService(loaded.catalog,(s,c,q)->GameServiceTest.response(null)),loaded,store);server.start();}
    @Test void httpOwnershipOriginCookiesAndSafeResponseAllowlist()throws Exception{
        serve();HttpClient a=client(),b=client();var bootstrap=call(a,"GET","/api/topic-requests",null,null);assertEquals(200,bootstrap.statusCode());assertTrue(bootstrap.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly; SameSite=Strict"));
        JsonObject body=Json.createObjectBuilder().add("retryId",uuid()).add("topic","Music").add("difficulty","standard").build();assertEquals(403,call(a,"POST","/api/topic-requests",body,null).statusCode());assertEquals(403,call(a,"POST","/api/topic-requests",body,"http://127.0.0.1:8766").statusCode());
        var submitted=call(a,"POST","/api/topic-requests",body,server.url());assertEquals(200,submitted.statusCode());String id=json(submitted).getString("id");call(b,"GET","/api/topic-requests",null,null);assertEquals(404,call(b,"GET","/api/topic-requests/"+id,null,null).statusCode());
        String text=submitted.body();for(String secret:List.of("ownerId","secretHash","offeredAliases","humanReviewer","configPath","reviewNotes","\"clues\"","/Users/","drafts/"))assertFalse(text.contains(secret),secret);assertEquals(404,call(a,"GET","/api/player-requests",null,null).statusCode());
    }
    @Test void httpPlayRetriesAndRestartRecoveryNeverCreateAnotherGameSilently()throws Exception{
        serve();HttpClient client=client();call(client,"GET","/api/session",null,null);call(client,"GET","/api/topic-requests",null,null);
        JsonObject r=json(call(client,"POST","/api/topic-requests",Json.createObjectBuilder().add("retryId",uuid()).add("topic","Music").add("difficulty","standard").build(),server.url()));String id=r.getString("id"),selection=r.getJsonArray("choices").getJsonObject(0).getString("selectionId");
        JsonObject chosen=json(call(client,"POST","/api/topic-requests/"+id+"/choose",Json.createObjectBuilder().add("retryId",uuid()).add("selectionId",selection).build(),server.url()));assertEquals("ready",chosen.getString("state"));
        JsonObject play=Json.createObjectBuilder().add("retryId",uuid()).add("selectionId",selection).add("replay",false).build();var started=call(client,"POST","/api/topic-requests/"+id+"/play",play,server.url());assertEquals(200,started.statusCode(),started.body());JsonObject first=json(started);assertEquals(first.getString("id"),json(call(client,"POST","/api/topic-requests/"+id+"/play",play,server.url())).getString("id"));
        assertEquals(409,call(client,"POST","/api/topic-requests/"+id+"/play",Json.createObjectBuilder(play).add("replay",true).build(),server.url()).statusCode());
        server.close();server=null;serve();call(client,"GET","/api/session",null,null);assertEquals(id,json(call(client,"GET","/api/topic-requests",null,null)).getJsonArray("requests").getJsonObject(0).getString("id"));assertEquals(410,call(client,"POST","/api/topic-requests/"+id+"/play",play,server.url()).statusCode());
    }
    @Test void playRechecksIndexAfterReadyAndCannotStartOnStaleSourceIndex()throws Exception{
        serve();HttpClient client=client();call(client,"GET","/api/session",null,null);call(client,"GET","/api/topic-requests",null,null);
        JsonObject r=json(call(client,"POST","/api/topic-requests",Json.createObjectBuilder().add("retryId",uuid()).add("topic","Music").add("difficulty","standard").build(),server.url()));String id=r.getString("id"),selection=r.getJsonArray("choices").getJsonObject(0).getString("selectionId");
        assertEquals("ready",json(call(client,"POST","/api/topic-requests/"+id+"/choose",Json.createObjectBuilder().add("retryId",uuid()).add("selectionId",selection).build(),server.url())).getString("state"));
        PackCatalog.Collection collection=loaded.catalog.topics.get("music").collection;
        try(Indexer indexer=new Indexer(new CollectionIndex(loaded).base(collection.scope).toString(),1)){indexer.indexArticles(List.of(new Indexer.Article("wrong","Wrong","Wrong","Wrong source content")),Map.of("collectionDigest","stale"),true);}
        JsonObject play=Json.createObjectBuilder().add("retryId",uuid()).add("selectionId",selection).add("replay",false).build();assertEquals(409,call(client,"POST","/api/topic-requests/"+id+"/play",play,server.url()).statusCode());
        assertTrue(json(call(client,"GET","/api/session",null,null)).isNull("game"));
    }
    @Test void cancellationSerializesWithPlayAndPreventsAnyLaterNewLaunch()throws Exception{
        JsonObject r=submit("Music","standard");String id=r.getString("id");store.choose(owner,key,id,uuid(),music());
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger launches=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<JsonObject> play=pool.submit(()->store.play(owner,key,id,uuid(),music().getString("selectionId"),false,"boot","session",(request,intention)->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}launches.incrementAndGet();return request;}));
            assertTrue(entered.await(3,TimeUnit.SECONDS));Future<JsonObject> cancel=pool.submit(()->store.cancel(owner,key,id,uuid()));release.countDown();play.get();assertEquals("cancelled",cancel.get().getString("phase"));
            assertThrows(GameService.GameError.class,()->store.play(owner,key,id,uuid(),music().getString("selectionId"),false,"boot","session",(request,intention)->{launches.incrementAndGet();return request;}));assertEquals(1,launches.get());
        }finally{release.countDown();pool.shutdownNow();}
    }
    @Test void linkedWorkStartsPreparingOnlyAtExecutionAndLateCompletionCannotUncancel()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        workshop=new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",config),TopicBuilder.examples(),()->new WikiApi((u,a,b)->{calls.incrementAndGet();entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}JsonObject doc=Json.createObjectBuilder().add("query",Json.createObjectBuilder().add("search",Json.createArrayBuilder().add(Json.createObjectBuilder().add("pageid",1).add("ns",0).add("title","Music")))).build();return new WikiApi.Response(200,Map.of(),IntakeFiles.json(doc));},root.resolve("cache"),Clock.systemUTC(),SourceIntakeTest.AGENT,0),store);
        JsonObject r=submit("Music","standard");assertEquals(0,calls.get());JsonObject input=job("preview",Json.createObjectBuilder().add("topic","Music").add("limit",8).add("playerRequestId",r.getString("id")).add("playerTargetId","normal").build());String job=workshop.submit(input).getString("id");assertTrue(entered.await(3,TimeUnit.SECONDS));assertEquals("preparing",store.get(owner,key,r.getString("id")).getString("phase"));store.cancel(owner,key,r.getString("id"),uuid());release.countDown();for(int i=0;i<500&&workshop.status(job).getString("state").equals("running");i++)Thread.sleep(10);assertEquals("cancelled",store.get(owner,key,r.getString("id")).getString("phase"));
    }
    @Test void wrongTargetHandoffAndInsufficientLinkedPlanCannotPublishReadiness()throws Exception{
        workshop=builder();JsonObject r=submit("Jupiter moons","hard");
        JsonObject reviewed=done(job("review",Json.createObjectBuilder().add("beforeId","jovian-original").add("draftId","jovian-revised").add("decisionJson",Files.readString(Path.of("drafts/jovian-v2/review.json"))).add("playerRequestId",r.getString("id")).add("playerTargetId","normal").build()));assertEquals("succeeded",reviewed.getString("state"));
        JsonObject p=done(job("plan",Json.createObjectBuilder().add("reviewId",reviewed.getString("id")).add("targetId","normal").add("catalogVersion","1.0.1").add("playerRequestId",r.getString("id")).add("playerTargetId","normal").build()));assertEquals("failed",p.getString("state"));assertTrue(store.get(owner,key,r.getString("id")).isNull("offered"));
        assertThrows(GameService.GameError.class,()->store.operatorGet(r.getString("id"),"wrong-target"));
    }
}
