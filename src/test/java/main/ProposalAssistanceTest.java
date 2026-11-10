package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.sun.net.httpserver.HttpServer;
import static org.junit.jupiter.api.Assertions.*;

class ProposalAssistanceTest {
    @TempDir Path root;
    JsonObject capture,output,input;
    TopicBuilder builder;
    @BeforeEach void setup()throws Exception{root=root.toRealPath();capture=ProposalFixtures.capture();output=ProposalFixtures.output(capture);input=ProposalFixtures.input(uuid());}
    @AfterEach void close()throws Exception{if(builder!=null)builder.close();}
    String uuid(){return UUID.randomUUID().toString();}
    JsonObject first(){return output.getJsonArray("clues").getJsonObject(0);}
    JsonObject only(JsonObject clue){return Json.createObjectBuilder().add("clues",Json.createArrayBuilder().add(clue)).build();}
    JsonObject screen(JsonObject value)throws Exception{return ProposalAssistance.screen(capture,input,value);}
    JsonObject done(JsonObject request)throws Exception{String id=builder.submit(request).getString("id");for(int n=0;n<1500;n++){JsonObject s=builder.status(id);if(!Set.of("queued","running","cancelling").contains(s.getString("state"))){Thread.sleep(20);return s;}Thread.sleep(10);}throw new AssertionError("Timeout");}
    @Test void normalConfigurationHasNoFixtureAndIncompleteConfigKeepsManualMode(){
        ProposalProvider p=ProposalProvider.environment(Map.of());assertFalse(p.description().getBoolean("configured"));assertFalse(p.description().getBoolean("synthetic"));
        assertFalse(ProposalProvider.environment(Map.of("CLUELAB_ASSISTANCE_ENABLED","true","OPENAI_API_KEY","not-for-use")).description().getBoolean("configured"));
        assertFalse(p.description().toString().contains("not-for-use"));
    }
    @Test void validOutputDerivesOffsetsAndRetainsZeroApproval()throws Exception{
        JsonObject result=screen(output);assertEquals(5,result.getJsonObject("validation").getInt("accepted"));assertEquals(0,result.getJsonObject("validation").getInt("eligible"));
        for(JsonValue v:result.getJsonObject("proposals").getJsonArray("clues")){JsonObject c=(JsonObject)v;JsonObject s=c.getJsonObject("support");String context=capture.getJsonArray("sources").stream().map(x->(JsonObject)x).filter(x->x.getInt("pageId")==s.getInt("pageId")).findFirst().orElseThrow().getString("text");assertTrue(context.startsWith(s.getString("text"),s.getInt("start")));}
        assertTrue(result.getJsonObject("proposals").getJsonObject("authoring").isNull("humanReviewer"));
    }
    @Test void fabricatedReferencesTitlesOffsetsAndAnswerTypesAreRejected()throws Exception{
        for(JsonObject c:List.of(Json.createObjectBuilder(first()).add("answerPageId",999).build(),Json.createObjectBuilder(first()).add("answerTitle","Invented").build(),Json.createObjectBuilder(first()).add("answerType","date").build(),Json.createObjectBuilder(first()).add("support",Json.createObjectBuilder(first().getJsonObject("support")).add("start",0)).build()))assertEquals(0,screen(only(c)).getJsonObject("validation").getInt("accepted"));
    }
    @Test void missingMalformedAndUnapprovedFieldsCannotBecomeProposals()throws Exception{
        assertThrows(IOException.class,()->screen(Json.createObjectBuilder(output).add("review",Json.createObjectBuilder().add("status","human-reviewed")).build()));
        JsonObject bad=Json.createObjectBuilder(first()).remove("aliases").build();assertEquals(0,screen(only(bad)).getJsonObject("validation").getInt("accepted"));
        assertEquals(0,screen(only(Json.createObjectBuilder(first()).add("difficulty","hard").build())).getJsonObject("validation").getInt("accepted"));
    }
    @Test void duplicateFactsPassagesAndEmptyEvidenceDoNotPadBatch()throws Exception{
        JsonArrayBuilder repeated=Json.createArrayBuilder().add(first()).add(first());JsonObject result=screen(Json.createObjectBuilder().add("clues",repeated).build());assertEquals(1,result.getJsonObject("validation").getInt("accepted"));
        assertEquals(0,screen(Json.createObjectBuilder().add("clues",Json.createArrayBuilder()).build()).getJsonObject("validation").getInt("accepted"));
        assertEquals(0,screen(only(Json.createObjectBuilder(first()).add("support",Json.createObjectBuilder(first().getJsonObject("support")).add("text","invented evidence")).build())).getJsonObject("validation").getInt("accepted"));
    }
    @Test void aliasesAndAnswerRevealingHintsAreFlaggedNotApproved()throws Exception{
        JsonObject c=Json.createObjectBuilder(first()).add("hint","The answer is Io.").build(),result=screen(only(c));String flags=result.getJsonObject("validation").getJsonArray("clues").getJsonObject(0).getJsonArray("flags").toString();
        assertTrue(flags.contains("Answer-revealing"));assertTrue(flags.contains("Unverified alias"));assertTrue(flags.contains("factual premises"));
        assertEquals(0,screen(only(Json.createObjectBuilder(first()).add("aliases",Json.createArrayBuilder().add(" ")).build())).getJsonObject("validation").getInt("accepted"));
    }
    @Test void unicodeOffsetsAreUtf16AndRepeatedQuotesRejected()throws Exception{
        JsonObject source=capture.getJsonArray("sources").getJsonObject(0);String text="\ud83d\ude80 "+source.getString("text");JsonArrayBuilder rows=Json.createArrayBuilder();for(JsonValue v:capture.getJsonArray("sources"))rows.add(v==source?Json.createObjectBuilder(source).add("text",text).add("textSha256",PackCatalog.sha256(text.getBytes(StandardCharsets.UTF_8))).build():v);
        capture=Json.createObjectBuilder(capture).add("sources",rows).build();JsonObject target=output.getJsonArray("clues").stream().map(v->(JsonObject)v).filter(v->v.getInt("answerPageId")==source.getInt("pageId")).findFirst().orElse(null);
        if(target==null){target=Json.createObjectBuilder(first()).add("answerPageId",source.getInt("pageId")).add("answerTitle",source.getString("title")).add("aliases",Json.createArrayBuilder().add(source.getString("title"))).add("support",Json.createObjectBuilder().add("pageId",source.getInt("pageId")).add("text",source.getString("text").substring(0,80))).build();}
        JsonObject support=screen(only(target)).getJsonObject("proposals").getJsonArray("clues").getJsonObject(0).getJsonObject("support");assertEquals(text.indexOf(support.getString("text")),support.getInt("start"));assertTrue(support.getInt("start")>=3);
        String quote=support.getString("text");String repeated=text+quote;rows=Json.createArrayBuilder();for(JsonValue v:capture.getJsonArray("sources")){JsonObject s=(JsonObject)v;rows.add(s.getInt("pageId")==source.getInt("pageId")?Json.createObjectBuilder(s).add("text",repeated).add("textSha256",PackCatalog.sha256(repeated.getBytes(StandardCharsets.UTF_8))).build():s);}capture=Json.createObjectBuilder(capture).add("sources",rows).build();assertEquals(0,screen(only(target)).getJsonObject("validation").getInt("accepted"));
    }
    @Test void unsafeModelTextRemainsInertAndFlagged()throws Exception{JsonObject result=screen(only(Json.createObjectBuilder(first()).add("text","<img src=x onerror=alert(1)> Which moon has hundreds of volcanoes?").build()));assertTrue(result.toString().contains("Markup-like"));assertTrue(result.getJsonObject("proposals").getJsonArray("clues").getJsonObject(0).getString("text").startsWith("<img"));}
    @Test void exactMultilineUnicodeSupportIsAcceptedButSplitSurrogateIsRejected()throws Exception{
        String quote="\ud83d\ude80 First line.\nSecond line.";int page=first().getInt("answerPageId");JsonArrayBuilder rows=Json.createArrayBuilder();for(JsonValue v:capture.getJsonArray("sources")){JsonObject s=(JsonObject)v;String text=quote+"\n"+s.getString("text");rows.add(s.getInt("pageId")==page?Json.createObjectBuilder(s).add("text",text).add("textSha256",PackCatalog.sha256(text.getBytes(StandardCharsets.UTF_8))).build():s);}capture=Json.createObjectBuilder(capture).add("sources",rows).build();
        JsonObject valid=Json.createObjectBuilder(first()).add("support",Json.createObjectBuilder().add("pageId",page).add("text",quote)).build();assertEquals(1,screen(only(valid)).getJsonObject("validation").getInt("accepted"));
        JsonObject split=Json.createObjectBuilder(valid).add("support",Json.createObjectBuilder().add("pageId",page).add("text",quote.substring(1))).build();assertEquals(0,screen(only(split)).getJsonObject("validation").getInt("accepted"));
    }
    @Test void promptContainsOnlySourceWhitelistAndRequestedSettings()throws Exception{
        JsonObject request=ProposalAssistance.request(ProposalAssistance.sources(capture),input,input.getString("requestId"),ProposalFixtures.settings());JsonObject payload=IntakeFiles.parse(request.getString("input"),"input");assertEquals(Set.of("difficulty","count","sources"),payload.keySet());
        for(JsonValue v:payload.getJsonArray("sources"))assertEquals(Set.of("pageId","title","text","textSha256"),((JsonObject)v).keySet());
        assertFalse(request.getBoolean("store"));assertEquals(0,request.getJsonArray("tools").size());assertFalse(request.toString().contains("fixture-not-a-credential"));assertFalse(request.toString().contains("acceptedAnswers"));assertFalse(request.toString().contains("reviewNotes"));
    }
    @Test void batchConfigurationAndSourceBoundsRejectBeforeDispatch()throws Exception{
        for(JsonObject bad:List.of(Json.createObjectBuilder(input).add("count",6).build(),Json.createObjectBuilder(input).add("difficulty","expert").build(),Json.createObjectBuilder(input).add("confirmed",false).build()))assertThrows(IOException.class,()->ProposalAssistance.validateInput(bad));
        JsonObject source=capture.getJsonArray("sources").getJsonObject(0);String huge=source.getString("text")+" x".repeat(20000);JsonArrayBuilder rows=Json.createArrayBuilder().add(Json.createObjectBuilder(source).add("text",huge).add("textSha256",PackCatalog.sha256(huge.getBytes(StandardCharsets.UTF_8))));assertThrows(IOException.class,()->ProposalAssistance.sources(Json.createObjectBuilder(capture).add("sources",rows).build()));
        assertThrows(IllegalArgumentException.class,()->new ProposalProvider.Settings("test-fixture","fixture",999999,8192,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.TEN,"2026-11-06"));
    }
    @Test void validJobPersistsProvenanceUsageAndSameRetryDoesNotCallAgain()throws Exception{
        AtomicInteger calls=new AtomicInteger();builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();return new ProposalProvider.Reply(200,"req_fixture",IntakeFiles.json(ProposalFixtures.envelope(output)));}));
        JsonObject result=done(input);assertEquals("succeeded",result.getString("state"),result.toString());assertEquals(1,calls.get());assertEquals(result,builder.submit(input));assertEquals(1,calls.get());assertThrows(IOException.class,()->builder.submit(Json.createObjectBuilder(input).add("count",4).build()));
        JsonObject artifact=builder.open(input.getString("requestId"));assertEquals(5,artifact.getJsonObject("data").getJsonArray("clues").size());assertEquals(1200,artifact.getJsonObject("report").getJsonObject("usage").getInt("inputTokens"));assertTrue(artifact.getJsonObject("report").containsKey("estimatedUsd"));assertFalse(artifact.toString().contains("fixture-not-a-credential"));
    }
    @Test void malformedProviderReplyIsRecordedNotRetried()throws Exception{
        AtomicInteger calls=new AtomicInteger();builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();return new ProposalProvider.Reply(200,"req_fixture","bad json".getBytes(StandardCharsets.UTF_8));}));JsonObject result=done(input);assertEquals("failed",result.getString("state"));assertEquals(1,calls.get());assertTrue(Files.exists(root.resolve("workshop/jobs/"+input.getString("requestId")+"/received/response.json")));assertEquals(result,builder.submit(input));assertEquals(1,calls.get());
    }
    @Test void timeoutUnknownOutcomeNeedsExplicitRetryAndNeverSilentlyRepeats()throws Exception{
        AtomicInteger calls=new AtomicInteger();builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();throw new IOException("transport uncertainty");}));assertEquals("uncertain",done(input).getString("state"));assertEquals(1,calls.get());
        JsonObject second=Json.createObjectBuilder(input).add("requestId",uuid()).build();assertEquals("failed",done(second).getString("state"));assertEquals(1,calls.get());
        JsonObject retry=Json.createObjectBuilder(second).add("requestId",uuid()).add("retryOf",input.getString("requestId")).add("acknowledgeRetry",true).build();JsonObject retried=done(retry);assertEquals("uncertain",retried.getString("state"));assertFalse(retried.getJsonObject("result").getBoolean("retryEligible"));assertEquals(2,calls.get());
        JsonObject third=Json.createObjectBuilder(retry).add("requestId",uuid()).add("retryOf",retry.getString("requestId")).build();assertEquals("failed",done(third).getString("state"));assertEquals(2,calls.get());
    }
    @Test void terminalRetryMetadataPinsOriginalScopeAndHardBatchWithoutSecretOrConsent()throws Exception{
        builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{throw new IOException("Unknown outcome");}));JsonObject hard=Json.createObjectBuilder(input).add("difficulty","hard").add("count",3).build();JsonObject result=done(hard).getJsonObject("result"),retry=result.getJsonObject("retryInput");assertTrue(result.getBoolean("retryEligible"));assertEquals("hard",retry.getString("difficulty"));assertEquals(3,retry.getInt("count"));assertEquals("jovian-capture",retry.getString("captureId"));assertFalse(retry.containsKey("confirmed"));assertFalse(retry.containsKey("acknowledgeRetry"));assertFalse(retry.toString().contains("fixture-not-a-credential"));
    }
    @Test void cancellationAfterDispatchIsUncertainAndLateReplyCannotCreateProposals()throws Exception{
        CountDownLatch entered=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();entered.countDown();for(;;)budget.pause(50);}));String id=builder.submit(input).getString("id");assertTrue(entered.await(3,TimeUnit.SECONDS));builder.cancel(id);for(int n=0;n<500&&Set.of("queued","running","cancelling").contains(builder.status(id).getString("state"));n++)Thread.sleep(10);assertEquals("uncertain",builder.status(id).getString("state"));assertEquals(1,calls.get());assertFalse(Files.exists(root.resolve("workshop/jobs/"+id+"/artifact")));
    }
    @Test void insufficientOutputKeepsAccurateAvailability()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(only(first())));JsonObject result=done(input);assertEquals("partial",result.getString("state"));JsonObject artifact=builder.open(input.getString("requestId"));assertEquals(1,artifact.getJsonObject("report").getJsonObject("validation").getInt("accepted"));assertEquals(0,artifact.getJsonObject("report").getJsonObject("validation").getInt("eligible"));
    }
    @Test void draftExportRequiresOriginalProvenanceAndNeverApproves()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(output));done(input);String id=input.getString("requestId");JsonObject proposals=builder.open(id).getJsonObject("data");
        JsonObject export=Json.createObjectBuilder().add("requestId",uuid()).add("operation","export").add("captureId","jovian-capture").add("generationId",id).add("proposalJson",proposals.toString()).build();JsonObject result=done(export);assertEquals("succeeded",result.getString("state"),result.toString());assertTrue(builder.open(export.getString("requestId")).getJsonObject("validation").toString().contains("draft"));
        JsonObject changed=Json.createObjectBuilder(proposals).add("authoring",Json.createObjectBuilder(proposals.getJsonObject("authoring")).add("assistance",Json.createObjectBuilder(proposals.getJsonObject("authoring").getJsonObject("assistance")).add("promptTemplate","fake"))).build();
        assertEquals("failed",done(Json.createObjectBuilder(export).add("requestId",uuid()).add("proposalJson",changed.toString()).build()).getString("state"));
    }
    @Test void durableDispatchOnRestartRemainsUncertainWithoutProviderCall()throws Exception{
        Path job=root.resolve("workshop/jobs/"+input.getString("requestId"));Files.createDirectories(job.resolve("dispatch"));Files.write(job.resolve("request.json"),IntakeFiles.json(input));Files.writeString(job.resolve("dispatch/marker.json"),"{}");AtomicInteger calls=new AtomicInteger();
        builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();throw new IOException();}));assertEquals("uncertain",builder.status(input.getString("requestId")).getString("state"));assertEquals(0,calls.get());
    }
    @Test void browserDecimalFormattingDoesNotChangeCanonicalExportProvenance()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(output));done(input);String id=input.getString("requestId");JsonObject original=builder.open(id).getJsonObject("data"),author=original.getJsonObject("authoring"),p=author.getJsonObject("assistance");
        JsonObject browser=Json.createObjectBuilder(original).add("authoring",Json.createObjectBuilder(author).add("assistance",Json.createObjectBuilder(p).add("estimatedUsd",p.getJsonNumber("estimatedUsd").bigDecimalValue().stripTrailingZeros()))).build();
        JsonObject export=Json.createObjectBuilder().add("requestId",uuid()).add("operation","export").add("captureId","jovian-capture").add("generationId",id).add("proposalJson",browser.toString()).build();assertEquals("succeeded",done(export).getString("state"));
        assertEquals(p,builder.open(export.getString("requestId")).getJsonObject("data").getJsonObject("authoring").getJsonObject("assistance"));
    }
    @Test void generatedAuthoringFieldsNeverEnterSourceIndex()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(output));done(input);String id=input.getString("requestId");JsonObject p=builder.open(id).getJsonObject("data");JsonObject export=Json.createObjectBuilder().add("requestId",uuid()).add("operation","export").add("captureId","jovian-capture").add("generationId",id).add("proposalJson",p.toString()).build();assertEquals("succeeded",done(export).getString("state"));
        PackCatalog catalog=DraftExporter.validate(root.resolve("workshop/jobs/"+export.getString("requestId")+"/artifact"));CollectionIndex index=new CollectionIndex(catalog.collections,root.resolve("generated-index"),1,4);index.build(false);assertEquals(8,catalog.collections.values().iterator().next().sources.size());
        PackCatalog.Collection collection=catalog.collections.values().iterator().next();try(Indexer directory=new Indexer(index.base(collection.scope).toString(),1);org.apache.lucene.store.Directory d=org.apache.lucene.store.FSDirectory.open(Path.of(directory.getIndexPath()));org.apache.lucene.index.DirectoryReader reader=org.apache.lucene.index.DirectoryReader.open(d)){
            assertEquals(8,reader.numDocs());for(int n=0;n<reader.numDocs();n++){var doc=reader.storedFields().document(n);assertFalse(doc.get("body").contains("promptTemplate"));assertFalse(doc.get("body").contains("fixture-fact"));assertFalse(doc.get("body").contains("model-assisted"));assertNull(doc.get("assistance"));}}
    }
    @Test void refusalUnexpectedToolsOverUsageAndHttpRejectionStopWithoutRetries()throws Exception{
        JsonObject envelope=ProposalFixtures.envelope(output);List<ProposalProvider.Reply> replies=List.of(
                new ProposalProvider.Reply(429,"req_rejected","untrusted error text".getBytes(StandardCharsets.UTF_8)),
                new ProposalProvider.Reply(200,"req_refusal",IntakeFiles.json(Json.createObjectBuilder(envelope).add("output",Json.createArrayBuilder().add(Json.createObjectBuilder().add("type","message").add("role","assistant").add("content",Json.createArrayBuilder().add(Json.createObjectBuilder().add("type","refusal").add("refusal","No"))))).build())),
                new ProposalProvider.Reply(200,"req_tool",IntakeFiles.json(Json.createObjectBuilder(envelope).add("output",Json.createArrayBuilder().add(Json.createObjectBuilder().add("type","function_call"))).build())),
                new ProposalProvider.Reply(200,"req_usage",IntakeFiles.json(Json.createObjectBuilder(envelope).add("usage",Json.createObjectBuilder().add("input_tokens",999999).add("output_tokens",1)).build())));
        Path jobs=root.resolve("jobs");Files.createDirectories(jobs);for(ProposalProvider.Reply reply:replies){AtomicInteger calls=new AtomicInteger();String id=uuid();Path job=jobs.resolve(id);Files.createDirectory(job);try(ProposalAssistance service=new ProposalAssistance(new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(body,key,attempt,budget)->{calls.incrementAndGet();return reply;}))){ProposalAssistance.Failure failure=assertThrows(ProposalAssistance.Failure.class,()->service.generate(job,jobs,Json.createObjectBuilder(input).add("requestId",id).build(),capture,new WikiApi.Budget(null)));assertEquals("failed",failure.state);assertEquals(1,calls.get());assertFalse(failure.details.toString().contains("untrusted error text"));}}
    }
    @Test void savedResponseRecoversAfterRestartWithoutDispatch()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(output));done(input);Path job=root.resolve("workshop/jobs/"+input.getString("requestId"));builder.close();builder=null;Thread.sleep(100);
        for(Path dir:List.of(job.resolve("outcome"),job.resolve("artifact")))try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        builder=new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",root.resolve("player/game.json")),TopicBuilder.examples(),()->{throw new IOException();},null,ProposalProvider.environment(Map.of()));assertEquals("succeeded",builder.status(input.getString("requestId")).getString("state"));assertEquals(5,builder.open(input.getString("requestId")).getJsonObject("data").getJsonArray("clues").size());
    }
    @Test void cancelledSavedResponseOnRestartDoesNotPublishAndKeepsUsage()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(output));done(input);Path job=root.resolve("workshop/jobs/"+input.getString("requestId"));builder.close();builder=null;Thread.sleep(100);
        for(Path dir:List.of(job.resolve("outcome"),job.resolve("artifact")))try(var files=Files.walk(dir)){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}Files.createFile(job.resolve("cancel"));
        builder=new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",root.resolve("player/game.json")),TopicBuilder.examples(),()->{throw new IOException();},null,ProposalProvider.environment(Map.of()));JsonObject recovered=builder.status(input.getString("requestId"));assertEquals("cancelled",recovered.getString("state"));assertEquals(1200,recovered.getJsonObject("result").getJsonObject("usage").getInt("inputTokens"));assertFalse(Files.exists(job.resolve("artifact")));
    }
    @Test void actualHttpAdapterPostsOnceAndParsesBoundedResponse()throws Exception{
        AtomicInteger requests=new AtomicInteger();AtomicReference<String> seen=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),1);server.createContext("/",x->{requests.incrementAndGet();seen.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=IntakeFiles.json(ProposalFixtures.envelope(output));x.getResponseHeaders().add("x-request-id","req_transport");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();
        try{ProposalProvider p=new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",OpenAIProposalProvider.transport(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/")));JsonObject request=ProposalAssistance.request(ProposalAssistance.sources(capture),input,input.getString("requestId"),p.settings());assertEquals(200,p.call(request,new WikiApi.Budget(null)).status());assertEquals(1,requests.get());assertFalse(seen.get().contains("fixture-not-a-credential"));}finally{server.stop(0);}
    }
    @Test void concurrentDuplicateJobsDispatchOnceAndAnotherJobCannotRun()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}return new ProposalProvider.Reply(200,"req_fixture",IntakeFiles.json(ProposalFixtures.envelope(output)));}));
        ExecutorService pool=Executors.newFixedThreadPool(8);try{List<Future<JsonObject>> requests=new ArrayList<>();for(int n=0;n<12;n++)requests.add(pool.submit(()->builder.submit(input)));assertTrue(entered.await(3,TimeUnit.SECONDS));for(Future<JsonObject> f:requests)assertEquals(input.getString("requestId"),f.get().getString("id"));assertThrows(IOException.class,()->builder.submit(Json.createObjectBuilder(input).add("requestId",uuid()).build()));release.countDown();done(input);assertEquals(1,calls.get());}finally{release.countDown();pool.shutdownNow();}
    }
    @Test void tokenAndFinancialBudgetsRejectWithoutCallingProvider()throws Exception{
        AtomicInteger calls=new AtomicInteger();ProposalProvider.Settings limited=new ProposalProvider.Settings("test-fixture","fixture-model",4096,8192,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.TEN,"2026-11-06");builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(limited,"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();throw new AssertionError();}));assertEquals("failed",done(input).getString("state"));assertEquals(0,calls.get());
    }
    @Test void workspaceReservationsSurviveReportedCheapResponses()throws Exception{
        AtomicInteger calls=new AtomicInteger();ProposalProvider.Settings s=ProposalFixtures.settings();ProposalProvider.Settings limited=new ProposalProvider.Settings(s.identity(),s.model(),s.inputTokens(),s.outputTokens(),s.inputRate(),s.outputRate(),new BigDecimal("0.1"),new BigDecimal("0.1"),s.pricingDate());builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(limited,"fixture-not-a-credential",(b,k,i,budget)->{calls.incrementAndGet();return new ProposalProvider.Reply(200,"req_fixture",IntakeFiles.json(ProposalFixtures.envelope(output)));}));assertEquals("succeeded",done(input).getString("state"));assertEquals("failed",done(Json.createObjectBuilder(input).add("requestId",uuid()).build()).getString("state"));assertEquals(1,calls.get());
    }
    @Test void absentUsageStaysUnknownAndIncompleteOutputRetainsReportedUsage()throws Exception{
        JsonObject noUsage=Json.createObjectBuilder(ProposalFixtures.envelope(output)).remove("usage").build();builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->new ProposalProvider.Reply(200,"req_fixture",IntakeFiles.json(noUsage))));JsonObject result=done(input);assertEquals("succeeded",result.getString("state"));assertTrue(result.getJsonObject("result").getJsonObject("report").isNull("usage"));assertTrue(result.getJsonObject("result").getJsonObject("report").isNull("estimatedUsd"));
        Path job=root.resolve("workshop/jobs/"+input.getString("requestId"));builder.close();builder=null;Thread.sleep(100);Files.delete(job.resolve("outcome/result.json"));Files.delete(job.resolve("outcome"));try(var files=Files.walk(job.resolve("artifact"))){for(Path p:files.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}
        JsonObject received=IntakeFiles.read(job.resolve("received/response.json"));JsonObject incomplete=Json.createObjectBuilder(ProposalFixtures.envelope(output)).add("status","incomplete").build();Files.write(job.resolve("received/response.json"),IntakeFiles.json(Json.createObjectBuilder(received).add("bodyBase64",Base64.getEncoder().encodeToString(IntakeFiles.json(incomplete))).build()));
        builder=new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",root.resolve("player/game.json")),TopicBuilder.examples(),()->{throw new IOException();},null,ProposalProvider.environment(Map.of()));result=builder.status(input.getString("requestId"));assertEquals("failed",result.getString("state"));assertEquals(1200,result.getJsonObject("result").getJsonObject("report").getJsonObject("usage").getInt("inputTokens"));
    }
    @Test void cancelledLateResponseRecordsUsageButNeverPublishesProposals()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);builder=ProposalFixtures.builder(root,new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(b,k,i,budget)->{entered.countDown();try{release.await(2,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IOException(e);}return new ProposalProvider.Reply(200,"req_fixture",IntakeFiles.json(ProposalFixtures.envelope(output)));}));builder.submit(input);assertTrue(entered.await(3,TimeUnit.SECONDS));builder.cancel(input.getString("requestId"));release.countDown();JsonObject result=done(input);assertEquals("cancelled",result.getString("state"));assertEquals(1200,result.getJsonObject("result").getJsonObject("usage").getInt("inputTokens"));assertFalse(Files.exists(root.resolve("workshop/jobs/"+input.getString("requestId")+"/artifact")));
    }
    @Test void providerCannotSetPathsApprovalOrOtherGenerationSettings()throws Exception{
        builder=ProposalFixtures.builder(root,ProposalFixtures.provider(output));assertThrows(IOException.class,()->builder.submit(Json.createObjectBuilder(input).add("model","other").build()));assertThrows(IOException.class,()->builder.submit(Json.createObjectBuilder(input).add("path","/tmp/escape").build()));
        JsonObject hostile=Json.createObjectBuilder(first()).add("review",Json.createObjectBuilder().add("status","source-checked")).build();assertEquals(0,screen(only(hostile)).getJsonObject("validation").getInt("accepted"));
        done(input);JsonObject p=builder.open(input.getString("requestId")).getJsonObject("data");JsonObject forged=Json.createObjectBuilder(p).add("authoring",Json.createObjectBuilder(p.getJsonObject("authoring")).add("humanReviewer","Invented person")).build();assertThrows(IOException.class,()->DraftExporter.build(capture,forged,new WikiApi.Budget(null)));
    }
    @Test void actualTransportRejectsOversizeAndDoesNotRetryLostPost()throws Exception{
        for(boolean oversized:List.of(true,false)){AtomicInteger calls=new AtomicInteger();HttpServer s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),1);s.createContext("/",x->{calls.incrementAndGet();x.getRequestBody().readAllBytes();if(oversized){byte[] bytes=new byte[ProposalProvider.MAX_RESPONSE+1];x.sendResponseHeaders(200,bytes.length);try{x.getResponseBody().write(bytes);}catch(IOException ignored){}}x.close();});s.start();
            try{ProposalProvider p=new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",OpenAIProposalProvider.transport(URI.create("http://127.0.0.1:"+s.getAddress().getPort()+"/")));JsonObject request=ProposalAssistance.request(ProposalAssistance.sources(capture),input,input.getString("requestId"),p.settings());assertThrows(IOException.class,()->p.call(request,new WikiApi.Budget(null,Duration.ofSeconds(2))));assertEquals(1,calls.get());}finally{s.stop(0);}}
    }
}
