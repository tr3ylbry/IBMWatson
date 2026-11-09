package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TopicBuilderTest {
    @TempDir Path root;
    TopicBuilder builder;
    TopicBuilderServer server;
    Path normal, isolated, work;
    AtomicInteger calls = new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        root = root.toRealPath(); work = root.resolve("drafts/workbench");
        PackActivation.sandbox(Path.of("data/game.json"), root.resolve("normal"), root.resolve("normal-indexes"), 18765);
        normal = root.resolve("normal/game.json");
        PackActivation.sandbox(normal, root.resolve("isolated"), root.resolve("isolated-indexes"), 18766); isolated = root.resolve("isolated/game.json");
        builder = create(() -> fake((uri, agent, budget) -> { calls.incrementAndGet(); return searchResponse(); }));
    }
    TopicBuilder create(TopicBuilder.ApiFactory apis) throws Exception { return new TopicBuilder(work, root.resolve("drafts"), Map.of("normal", normal, "isolated", isolated), TopicBuilder.examples(), apis); }
    WikiApi fake(WikiApi.Transport transport) throws IOException { return new WikiApi(transport, root.resolve("cache"), Clock.systemUTC(), SourceIntakeTest.AGENT, 0); }
    WikiApi.Response searchResponse() { return new WikiApi.Response(200, Map.of(), IntakeFiles.json(Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("search", Json.createArrayBuilder().add(Json.createObjectBuilder().add("pageid", 1).add("ns", 0).add("title", "Object 1")))).build())); }
    JsonObject input(String operation, JsonObject data) { return Json.createObjectBuilder(data).add("requestId", UUID.randomUUID().toString()).add("operation", operation).build(); }
    JsonObject done(JsonObject request) throws Exception {
        String id = builder.submit(request).getString("id"); long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        for (;;) { JsonObject s = builder.status(id); if (!Set.of("queued", "running", "cancelling").contains(s.getString("state"))) {
                while (System.nanoTime() < end) { try { builder.submit(request); break; } catch (IOException ignored) {} }
                return s;
            } assertTrue(System.nanoTime() < end, "Job timed out"); Thread.sleep(10); }
    }
    JsonObject data(String key, String value) { return Json.createObjectBuilder().add(key, value).build(); }
    JsonObject reviewInput(JsonObject decision) { return input("review", Json.createObjectBuilder().add("beforeId", "jovian-original").add("draftId", "jovian-revised").add("decisionJson", decision.toString()).build()); }
    JsonObject review() throws Exception { return done(reviewInput(IntakeFiles.read(Path.of("drafts/jovian-v2/review.json")))); }
    JsonObject plan() throws Exception {
        JsonObject reviewed = review(); assertEquals("succeeded", reviewed.getString("state"));
        return done(input("plan", Json.createObjectBuilder().add("reviewId", reviewed.getString("id")).add("targetId", "isolated").add("catalogVersion", "1.0.1").build()));
    }
    JsonObject apply(JsonObject plan, String action) throws Exception { return done(input(action, Json.createObjectBuilder().add("planId", plan.getString("id")).add("confirmPlanId", plan.getJsonObject("result").getJsonObject("plan").getString("planId")).add("confirmed", true).build())); }
    @AfterEach void close() throws Exception {
        if (server != null) server.close(); else if (builder != null) builder.close();
        // Shutdown is intentionally asynchronous; wait before JUnit removes a job's receipt directory.
        if (builder != null) awaitLock();
    }

    @Test void duplicateRequestsShareOneJobAndConflictingRetriesCannotOverwrite() throws Exception {
        JsonObject r = input("preview", Json.createObjectBuilder().add("topic", "Objects").add("limit", 8).build());
        JsonObject result = done(r); assertEquals("succeeded", result.getString("state")); assertEquals(result, builder.submit(r)); assertEquals(1, calls.get());
        assertThrows(IOException.class, () -> builder.submit(Json.createObjectBuilder(r).add("topic", "Changed").build()));
        assertEquals(1, builder.catalog().getJsonArray("jobs").size());
    }
    @Test void concurrentSameIdStartsOnlyOneSourceOperation() throws Exception {
        JsonObject r = input("preview", Json.createObjectBuilder().add("topic", "Objects").add("limit", 8).build());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try { List<Future<JsonObject>> results = new ArrayList<>(); for (int i=0;i<12;i++) results.add(pool.submit(() -> builder.submit(r))); for (var result : results) assertEquals(r.getString("requestId"), result.get().getString("id")); }
        finally { pool.shutdownNow(); }
        assertEquals("succeeded", done(r).getString("state")); assertEquals(1, calls.get());
    }
    @Test void cancellationStopsSourceJobWithoutPublishingAndBlocksAnotherMutation() throws Exception {
        builder.close(); awaitLock(); CountDownLatch entered = new CountDownLatch(1);
        builder = create(() -> fake((uri, agent, b) -> { entered.countDown(); for (;;) b.pause(20); }));
        JsonObject r = input("preview", Json.createObjectBuilder().add("topic", "Cancel").add("limit", 8).build()); builder.submit(r); assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertThrows(IOException.class, () -> builder.submit(input("preview", Json.createObjectBuilder().add("topic", "Other").add("limit", 8).build())));
        builder.cancel(r.getString("requestId")); assertEquals("cancelled", done(r).getString("state")); assertFalse(Files.exists(work.resolve("jobs/"+r.getString("requestId")+"/artifact")));
        assertEquals("cancelled", builder.cancel(r.getString("requestId")).getString("state"));
    }
    void awaitLock() throws Exception { for(int i=0;i<100;i++){try(FileChannelProbe probe = new FileChannelProbe(work.resolve(".server.lock"))){if(probe.available)return;}Thread.sleep(10);}fail("lock not released"); }
    static class FileChannelProbe implements AutoCloseable { final java.nio.channels.FileChannel channel; final java.nio.channels.FileLock lock; final boolean available;
        FileChannelProbe(Path p) throws Exception { channel=java.nio.channels.FileChannel.open(p,StandardOpenOption.WRITE);java.nio.channels.FileLock value;try{value=channel.tryLock();}catch(java.nio.channels.OverlappingFileLockException e){value=null;}lock=value;available=value!=null; }
        public void close() throws Exception { if(lock!=null)lock.release();channel.close(); }
    }
    @Test void completedResultsAndArtifactsSurviveRestartButUnfinishedJobsAreNotReexecuted() throws Exception {
        JsonObject r=input("preview",Json.createObjectBuilder().add("topic","Objects").add("limit",8).build()), result=done(r);
        builder.close();awaitLock();String interrupted=UUID.randomUUID().toString();
        IntakeFiles.publish(work.resolve("jobs/"+interrupted),Map.of("request.json",IntakeFiles.json(Json.createObjectBuilder(r).add("requestId",interrupted).build())),new WikiApi.Budget(null));
        builder=create(()->fake((u,a,b)->{throw new IOException("Must not rerun");}));
        assertEquals(result,builder.status(r.getString("requestId")));assertEquals("preview",builder.open(r.getString("requestId")).getString("kind"));
        assertEquals("interrupted",builder.status(interrupted).getString("state"));assertEquals(1,calls.get());
    }
    @Test void wrongKindsUnknownIdsTraversalAndExtraCommandFieldsAreRejected() throws Exception {
        for(String id:List.of("../../data/game.json","%2e%2e","/tmp/key","unknown"))assertThrows(IOException.class,()->builder.open(id));
        assertThrows(IOException.class,()->builder.submit(Json.createObjectBuilder(input("capture",data("artifactId","jovian-capture"))).add("path","/tmp/key").build()));
        assertEquals("failed",done(input("capture",data("artifactId","jovian-capture"))).getString("state"));
        assertThrows(IOException.class,()->builder.submit(input("exec",data("command","echo nope"))));
    }
    @Test void validationAndExportKeepDraftStatusAndDoNotChangeCatalog() throws Exception {
        String before=TopicBuilder.hash(PackActivation.catalogPath(normal));
        JsonObject payload=Json.createObjectBuilder().add("captureId","jovian-capture").add("proposalJson",Files.readString(Path.of("drafts/jovian-v2/proposals.json"))).build();
        for(String op:List.of("validate","export")){JsonObject job=done(input(op,payload));assertEquals("succeeded",job.getString("state"),job.toString());JsonObject pack=job.getJsonObject("result").getJsonObject("validation").getJsonArray("packs").getJsonObject(0);assertEquals("draft",pack.getJsonObject("review").getString("status"));assertEquals(0,pack.getJsonObject("eligibleCounts").getInt("standard"));}
        assertEquals(before,TopicBuilder.hash(PackActivation.catalogPath(normal)));
    }
    @Test void invalidAliasesSupportDuplicateJsonAndHumanAssertionsFailWithoutPromotion() throws Exception {
        String raw=Files.readString(Path.of("drafts/jovian-v2/proposals.json"));
        for(String value:List.of(raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"),raw.replace("\"Io\"","\"Io|wrong\""),raw.replace("\"humanReviewer\": null","\"humanReviewer\": \"Invented\""),raw.replace("With over 400 active volcanoes","Invented support sentence"))){
            assertEquals("failed",done(input("validate",Json.createObjectBuilder().add("captureId","jovian-capture").add("proposalJson",value).build())).getString("state"));}
    }
    @Test void diagnosticValidationDoesNotReserveVersionButPublishedExportsDo() throws Exception {
        JsonObject original=IntakeFiles.read(Path.of("drafts/jovian-v2/proposals.json"));
        JsonObject payload=Json.createObjectBuilder().add("captureId","jovian-capture").add("proposalJson",original.toString()).build();
        JsonObject diagnostic=done(input("validate",payload));assertEquals("succeeded",diagnostic.getString("state"));
        assertEquals(original,builder.open(diagnostic.getString("id")).getJsonObject("data"));
        JsonObject edited=Json.createObjectBuilder(payload).add("proposalJson",Json.createObjectBuilder(original).add("description","Edited after structural validation, before publication.").build().toString()).build();
        assertEquals("succeeded",done(input("export",edited)).getString("state"));
        assertEquals("failed",done(input("export",payload)).getString("state"));
    }
    @Test void reviewBlockersRemainVisibleAndCannotProduceAPlan() throws Exception {
        JsonObject d=IntakeFiles.read(Path.of("drafts/jovian-v2/review.json")),c=d.getJsonArray("clues").getJsonObject(0);JsonArrayBuilder rows=Json.createArrayBuilder().add(Json.createObjectBuilder(c).add("blockers",Json.createArrayBuilder().add("Unresolved support")));for(int i=1;i<5;i++)rows.add(d.getJsonArray("clues").get(i));
        JsonObject reviewed=done(reviewInput(Json.createObjectBuilder(d).add("clues",rows).build()));assertEquals("partial",reviewed.getString("state"));assertFalse(reviewed.getJsonObject("result").getJsonObject("preview").getJsonArray("blockingFailures").isEmpty());
        assertEquals("failed",done(input("plan",Json.createObjectBuilder().add("reviewId",reviewed.getString("id")).add("targetId","isolated").add("catalogVersion","1.0.1").build())).getString("state"));
    }
    @Test void dryPlanActivationRollbackAndHistoricalReceiptNeverMisstateCurrentCatalog() throws Exception {
        String original=TopicBuilder.hash(PackActivation.catalogPath(isolated)),normalHash=TopicBuilder.hash(PackActivation.catalogPath(normal));JsonObject p=plan();assertEquals("succeeded",p.getString("state"));assertEquals(original,TopicBuilder.hash(PackActivation.catalogPath(isolated)));
        assertEquals("succeeded",apply(p,"activate").getString("state"));assertEquals("candidate-active",builder.open(p.getString("id")).getJsonObject("current").getString("state"));
        assertEquals("succeeded",apply(p,"rollback").getString("state"));assertEquals(original,TopicBuilder.hash(PackActivation.catalogPath(isolated)));
        JsonObject repeated=apply(p,"activate");assertEquals("succeeded",repeated.getString("state"));assertEquals("previous-active",repeated.getJsonObject("result").getJsonObject("current").getString("state"));assertEquals(normalHash,TopicBuilder.hash(PackActivation.catalogPath(normal)));
    }
    @Test void stalePlanAndMissingConfirmationCannotSwitch() throws Exception {
        JsonObject p=plan();String before=TopicBuilder.hash(PackActivation.catalogPath(isolated));
        assertEquals("failed",done(input("activate",Json.createObjectBuilder().add("planId",p.getString("id")).add("confirmed",false).add("confirmPlanId","bad").build())).getString("state"));assertEquals(before,TopicBuilder.hash(PackActivation.catalogPath(isolated)));
        Path catalog=PackActivation.catalogPath(isolated);Files.writeString(catalog,Files.readString(catalog)+" ");String changed=TopicBuilder.hash(catalog);assertEquals("failed",apply(p,"activate").getString("state"));assertEquals(changed,TopicBuilder.hash(catalog));
    }
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> http(HttpClient client,String method,String path,String body,String origin,String csrf) throws Exception {
        HttpRequest.Builder r=HttpRequest.newBuilder(URI.create(server.url()+path));if(origin!=null)r.header("Origin",origin);if(csrf!=null)r.header("X-ClueLab-CSRF",csrf);
        if(body!=null)r.header("Content-Type","application/json");r.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return client.send(r.build(),HttpResponse.BodyHandlers.ofString());
    }
    void serve() throws Exception { server=new TopicBuilderServer(0,builder,"test-private-code");server.start(); }
    String unlock(HttpClient client) throws Exception { var r=http(client,"POST","/api/login","{\"code\":\"test-private-code\"}",server.url(),null);assertEquals(200,r.statusCode());assertTrue(r.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly; SameSite=Strict"));return IntakeFiles.parse(r.body(),"login").getString("csrf"); }
    @Test void httpRequiresOperatorOriginAndCsrfForPrivateJobs() throws Exception {
        serve();HttpClient c=client();assertEquals(401,http(c,"GET","/api/session",null,null,null).statusCode());
        assertEquals(403,http(c,"POST","/api/login","{\"code\":\"test-private-code\"}","https://evil.invalid",null).statusCode());
        assertEquals(403,http(c,"POST","/api/login","{\"code\":\"test-private-code\"}",null,null).statusCode());
        String csrf=unlock(c),r=input("preview",Json.createObjectBuilder().add("topic","Objects").add("limit",8).build()).toString();
        assertEquals(403,http(c,"POST","/api/jobs",r,server.url(),null).statusCode());assertEquals(403,http(c,"POST","/api/jobs",r,"https://evil.invalid",csrf).statusCode());
        assertEquals(202,http(c,"POST","/api/jobs",r,server.url(),csrf).statusCode());assertEquals(401,http(client(),"GET","/api/artifacts/jovian-decision",null,null,null).statusCode());
    }
    @Test void httpHostTraversalAssetAllowlistAndUnsafeTextHaveNoExecutionRoute() throws Exception {
        serve();HttpClient c=client();String csrf=unlock(c);
        for(String path:List.of("/drafts/jovian-v2/review.json","/builder/../data/game.json","/%2e%2e/secrets","/api/artifacts/..%2fjovian-decision"))assertEquals(404,http(c,"GET",path,null,null,null).statusCode());
        try(Socket socket=new Socket("127.0.0.1",URI.create(server.url()).getPort())){socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.invalid\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));assertTrue(new String(socket.getInputStream().readAllBytes(),StandardCharsets.UTF_8).contains("403 Forbidden"));}
        String js=http(c,"GET","/builder.js",null,null,null).body();assertFalse(js.contains("innerHTML"));assertFalse(js.contains("eval("));assertTrue(js.contains("textContent"));
        var response=http(c,"GET","/",null,null,null);assertTrue(response.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors 'none'"));assertFalse(response.body().contains("Jupiter II"));
        assertEquals(400,http(c,"POST","/api/jobs","{\"operation\":\"exec\",\"path\":\"/etc/passwd\"}",server.url(),csrf).statusCode());
    }
    @Test void partialCaptureReportsFailuresAndProgressWithoutSilentReplacement() throws Exception {
        builder.close();awaitLock();builder=create(()->fake((uri,agent,budget)->{String query=uri.getRawQuery();if(query.contains("pageids=2"))return new WikiApi.Response(404,Map.of(),new byte[0]);JsonObject p=Json.createObjectBuilder().add("pageid",1).add("ns",0).add("title","Object 1").add("extract",SourceIntakeTest.TEXT).add("revisions",Json.createArrayBuilder().add(Json.createObjectBuilder().add("revid",2))).build();return new WikiApi.Response(200,Map.of(),IntakeFiles.json(Json.createObjectBuilder().add("query",Json.createObjectBuilder().add("pages",Json.createArrayBuilder().add(p))).build()));}));
        SourceIntakeTest fixture=new SourceIntakeTest();JsonObject selected=SourceIntake.select(fixture.preview(),Set.of(1,2));String id=UUID.randomUUID().toString();IntakeFiles.publish(work.resolve("jobs/"+id),Map.of("request.json",IntakeFiles.json(Json.createObjectBuilder().add("requestId",id).add("operation","select").build())),new WikiApi.Budget(null));IntakeFiles.publish(work.resolve("jobs/"+id+"/artifact"),Map.of("select.json",IntakeFiles.json(selected)),new WikiApi.Budget(null));
        JsonObject result=done(input("capture",data("artifactId",id)));assertEquals("partial",result.getString("state"));assertEquals(1,result.getJsonObject("result").getInt("capturedSources"));assertEquals(1,result.getJsonObject("result").getJsonArray("failures").size());
        assertEquals(2,builder.open(result.getString("id")).getJsonObject("data").getJsonObject("selection").getJsonArray("selected").size());
    }
    @Test void sourceProgressDoesNotConsumeCapturedSourcesAndCountsFailures() throws Exception {
        SourceIntakeTest fixture=new SourceIntakeTest();JsonObject selection=SourceIntake.select(fixture.preview(),Set.of(1,2));List<JsonObject> progress=new ArrayList<>();
        WikiApi api=fake((uri,agent,budget)->{if(uri.getRawQuery().contains("pageids=2"))throw new IOException("Synthetic unavailable source");JsonObject p=Json.createObjectBuilder().add("pageid",1).add("ns",0).add("title","Object 1").add("extract",SourceIntakeTest.TEXT).add("revisions",Json.createArrayBuilder().add(Json.createObjectBuilder().add("revid",2))).build();return new WikiApi.Response(200,Map.of(),IntakeFiles.json(Json.createObjectBuilder().add("query",Json.createObjectBuilder().add("pages",Json.createArrayBuilder().add(p))).build()));});
        JsonObject capture=SourceIntake.capture(selection,api,new WikiApi.Budget(null),progress::add);
        assertEquals(1,capture.getJsonArray("sources").size());assertEquals(1,capture.getJsonArray("failures").size());
        assertEquals(List.of(0,1,1,2),progress.stream().map(p->p.getInt("completed")).toList());assertTrue(progress.stream().allMatch(p->p.getInt("total")==2));
    }
    @Test void workspaceLockSymlinkAndJobCapacityAreExplicitBounds() throws Exception {
        assertThrows(IOException.class,()->create(()->fake((u,a,b)->searchResponse())));
        String id=UUID.randomUUID().toString();Files.createSymbolicLink(work.resolve("jobs/"+id),root.resolve("normal"));assertThrows(IOException.class,()->builder.open(id));Files.delete(work.resolve("jobs/"+id));
        for(int i=0;i<TopicBuilder.MAX_JOBS;i++)assertEquals("succeeded",done(input("preview",Json.createObjectBuilder().add("topic","Objects").add("limit",8).build())).getString("state"));
        assertThrows(IOException.class,()->builder.submit(input("preview",Json.createObjectBuilder().add("topic","Objects").add("limit",8).build())));assertEquals(32,builder.catalog().getJsonArray("jobs").size());
    }
    @Test void privateWorkspacePermissionsAndSmallCatalogSummariesArePreserved() throws Exception {
        assertEquals("rwx------",java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(work)));
        JsonObject reviewed=review();assertTrue(builder.status(reviewed.getString("id")).getJsonObject("result").getJsonObject("preview").containsKey("clues"));
        assertFalse(builder.catalog().getJsonArray("jobs").getJsonObject(0).containsKey("result"));assertFalse(builder.catalog().toString().contains("Jupiter II"));
    }
    @Test void httpRejectsOtherLocalOriginsMissingOriginWrongCsrfAndOversizedImports() throws Exception {
        serve();HttpClient c=client();String csrf=unlock(c),r=input("preview",Json.createObjectBuilder().add("topic","Objects").add("limit",8).build()).toString();
        assertEquals(403,http(c,"GET","/api/session",null,"http://127.0.0.1:8765",null).statusCode());
        assertEquals(403,http(c,"POST","/api/jobs",r,null,csrf).statusCode());assertEquals(403,http(c,"POST","/api/jobs",r,server.url(),"wrong").statusCode());
        assertEquals(413,http(c,"POST","/api/jobs"," ".repeat(TopicBuilderServer.MAX_BODY+1),server.url(),csrf).statusCode());assertTrue(builder.catalog().getJsonArray("jobs").isEmpty());
        for(int i=0;i<10;i++)assertEquals(401,http(c,"POST","/api/login","{\"code\":\"wrong\"}",server.url(),null).statusCode());
        assertEquals(429,http(c,"POST","/api/login","{\"code\":\"wrong\"}",server.url(),null).statusCode());
    }
}
