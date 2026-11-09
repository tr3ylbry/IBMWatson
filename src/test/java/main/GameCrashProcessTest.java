package main;

import jakarta.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real child JVMs, real HTTP/cookies, and abrupt OS process termination, not simulated reloads. */
class GameCrashProcessTest {
    @TempDir Path temp;
    Path config, root;
    Process process;
    String url;
    List<String> output = new CopyOnWriteArrayList<>();
    HttpClient client;
    PackCatalog catalog;
    @BeforeEach void setup() throws Exception {
        temp=temp.toRealPath();root=temp.resolve("private");
        PackActivation.sandbox(Path.of("data/game.json"),temp.resolve("player"),temp.resolve("indexes"),18791);
        config=temp.resolve("player/game.json");GameConfig loaded=GameConfig.load(config);catalog=loaded.catalog;new CollectionIndex(loaded).build(false);
        client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).connectTimeout(Duration.ofSeconds(3)).build();
    }
    @AfterEach void cleanup() throws Exception { stop(true); }
    void boot(String engine,String operation,String phase) throws Exception {
        output.clear();
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",System.getProperty("java.class.path"),
                "main.GameCrashFixtureServer",config.toString(),root.toString(),"0",engine,operation,phase).redirectErrorStream(true);
        builder.environment().put("CLUELAB_ASSISTANCE_ENABLED","false");process=builder.start();
        Thread reader=new Thread(()->{try(BufferedReader lines=process.inputReader()){String line;while((line=lines.readLine())!=null)output.add(line);}catch(IOException ignored){}});reader.setDaemon(true);reader.start();
        awaitLine("READY ");url=output.stream().filter(s->s.startsWith("READY ")).findFirst().orElseThrow().split(" ")[1];
    }
    void stop(boolean abrupt) throws Exception {
        if(process==null)return;
        if(process.isAlive()){if(abrupt)process.destroyForcibly();else process.destroy();assertTrue(process.waitFor(5,TimeUnit.SECONDS),"Owned child did not stop");}
        process=null;
    }
    void awaitLine(String prefix) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(12).toNanos();
        while(System.nanoTime()<end){if(output.stream().anyMatch(s->s.startsWith(prefix)))return;if(!process.isAlive())fail("Child stopped: "+output);Thread.sleep(15);}
        fail("Timed out waiting for "+prefix+": "+output);
    }
    HttpRequest request(String method,String path,JsonObject body) {
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(url+path)).timeout(Duration.ofSeconds(15));
        if(body!=null)request.header("Origin",url).header("Content-Type","application/json").header("X-ClueLab-Request","1");
        return request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body.toString())).build();
    }
    HttpResponse<String> call(String method,String path,JsonObject body) throws Exception {return client.send(request(method,path,body),HttpResponse.BodyHandlers.ofString());}
    JsonObject ok(String method,String path,JsonObject body) throws Exception {var response=call(method,path,body);assertEquals(200,response.statusCode(),response.body());return PackCatalog.parse(response.body(),"HTTP result");}
    JsonObject session() throws Exception {return ok("GET","/api/session",null);}
    static JsonObject startBody(String id,boolean replay){return Json.createObjectBuilder().add("requestId",id).add("topicId","music").add("difficulty","easy").add("packVersion","1.0.0").add("replay",replay).build();}
    JsonObject start() throws Exception {return ok("POST","/api/games",startBody(UUID.randomUUID().toString(),false));}
    JsonObject ready(JsonObject game) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<end){game=ok("GET","/api/games/"+game.getString("id"),null);if(!game.getJsonObject("round").getString("state").equals("THINKING"))return game;Thread.sleep(20);}
        throw new AssertionError("Computer did not commit");
    }
    String guess(JsonObject game){String clue=game.getJsonObject("round").getString("clue");return catalog.topics.get("music").clues.stream().filter(c->c.text.equals(clue)).findFirst().orElseThrow().aliases.get(0);}
    JsonObject answerBody(JsonObject game){return Json.createObjectBuilder().add("roundId",game.getJsonObject("round").getString("id")).add("answer",guess(game)).build();}
    JsonObject score(JsonObject game)throws Exception{game=ready(game);return ok("POST","/api/games/"+game.getString("id")+"/answer",answerBody(game));}
    JsonObject nextBody(JsonObject game){return Json.createObjectBuilder().add("roundId",game.getJsonObject("round").getString("id")).build();}
    JsonObject advance(JsonObject game)throws Exception{return ok("POST","/api/games/"+game.getString("id")+"/advance",nextBody(game));}
    void crashMutation(String path,JsonObject body,String barrier)throws Exception{
        var lost=client.sendAsync(request("POST",path,body),HttpResponse.BodyHandlers.ofString());awaitLine("PAUSED "+barrier);stop(true);
        try{lost.get(3,TimeUnit.SECONDS);}catch(ExecutionException expected){}finally{lost.cancel(true);}
    }

    @Test void abruptStartAfterAtomicSwitchRecoversOriginalIntentionAndInterruptedRound()throws Exception{
        boot("real","start","after-replace");session();String retry=UUID.randomUUID().toString();JsonObject body=startBody(retry,false);
        crashMutation("/api/games",body,"start");boot("real","none","none");JsonObject game=session().getJsonObject("game");
        assertEquals(game,ok("POST","/api/games",body));assertEquals("READY",game.getJsonObject("round").getString("state"));
        assertEquals("interrupted",score(game).getJsonObject("round").getString("computerStatus"));
    }
    @Test void abruptStartBeforeSwitchDoesNotReserveCluesOrCreateAGame()throws Exception{
        boot("real","start","before-replace");session();JsonObject body=startBody(UUID.randomUUID().toString(),false);crashMutation("/api/games",body,"start");
        boot("real","none","none");assertTrue(session().isNull("game"));assertEquals(1,ok("POST","/api/games",body).getInt("number"));
    }
    @Test void gracefulRestartKeepsCommittedRealEngineGuessAndEvidenceExactly()throws Exception{
        boot("real","none","none");session();JsonObject game=ready(start());JsonObject body=answerBody(game);stop(false);boot("failure","none","none");
        assertEquals(game,session().getJsonObject("game"));JsonObject result=ok("POST","/api/games/"+game.getString("id")+"/answer",body).getJsonObject("round");
        assertEquals("answered",result.getString("computerStatus"));assertNotNull(result.getJsonObject("evidence"));stop(true);boot("real","none","none");
        assertEquals(result,ok("GET","/api/rounds/"+result.getString("id"),null));
    }
    @Test void killWhileThinkingDoesNotRerunSearchOrResetItsDeadline()throws Exception{
        boot("thinking","none","none");session();JsonObject game=start();awaitLine("SEARCH_BLOCKED");stop(true);boot("real","none","none");
        JsonObject receipt=score(session().getJsonObject("game")).getJsonObject("round");assertEquals("interrupted",receipt.getString("computerStatus"));assertEquals(100,receipt.getInt("playerPoints"));assertTrue(receipt.isNull("evidence"));
    }
    @Test void killAfterScoringCommitRecoversSameGuessAndNeverAwardsTwice()throws Exception{
        boot("real","score","after-replace");session();JsonObject game=ready(start());JsonObject body=answerBody(game);
        crashMutation("/api/games/"+game.getString("id")+"/answer",body,"score");boot("real","none","none");JsonObject recovered=session().getJsonObject("game");
        assertEquals(100,recovered.getInt("playerScore"));assertEquals(recovered,ok("POST","/api/games/"+game.getString("id")+"/answer",Json.createObjectBuilder(body).add("answer","Changed").build()));
    }
    @Test void killBeforeScoringCommitLeavesThePlayerReadyAndUnscored()throws Exception{
        boot("real","score","before-replace");session();JsonObject game=ready(start());crashMutation("/api/games/"+game.getString("id")+"/answer",answerBody(game),"score");
        boot("real","none","none");assertEquals(game,session().getJsonObject("game"));assertEquals(100,score(game).getInt("playerScore"));
    }
    @Test void killDuringAdvancementOpensExactlyOneSecondClue()throws Exception{
        boot("real","advance","after-replace");session();JsonObject game=score(start());JsonObject body=nextBody(game);
        crashMutation("/api/games/"+game.getString("id")+"/advance",body,"advance");boot("real","none","none");JsonObject recovered=session().getJsonObject("game");
        assertEquals(2,recovered.getInt("clueNumber"));assertEquals(recovered,ok("POST","/api/games/"+game.getString("id")+"/advance",body));assertEquals("interrupted",score(recovered).getJsonObject("round").getString("computerStatus"));
    }
    @Test void killDuringFifthScoreAndReplayPreservesBothReceiptsAndSessionTotals()throws Exception{
        boot("real","complete","after-replace");session();JsonObject game=start();
        for(int i=0;i<4;i++){game=score(game);game=advance(game);}game=ready(game);String id=game.getString("id");
        crashMutation("/api/games/"+id+"/answer",answerBody(game),"complete");boot("real","none","none");JsonObject summary=session().getJsonObject("game");
        assertEquals("COMPLETED",summary.getString("state"));assertEquals(500,summary.getInt("playerScore"));assertEquals(5,summary.getJsonArray("rounds").size());
        stop(true);boot("real","replay","after-replace");JsonObject replay=startBody(UUID.randomUUID().toString(),true);crashMutation("/api/games",replay,"replay");
        boot("real","none","none");JsonObject snapshot=session(),active=snapshot.getJsonObject("game");assertEquals(2,active.getInt("number"));assertEquals(0,active.getInt("playerScore"));
        assertEquals(500,snapshot.getJsonObject("sessionTotals").getInt("player"));assertEquals(summary,ok("GET","/api/games/"+id,null));assertEquals(active,ok("POST","/api/games",replay));
    }
    @Test void engineFailureGameCompletesAcrossRestartWithNoInventedEvidence()throws Exception{
        boot("failure","none","none");session();JsonObject game=start();
        for(int i=0;i<5;i++){game=score(game);if(i==1){stop(true);boot("failure","none","none");game=session().getJsonObject("game");}if(i<4)game=advance(game);}
        assertEquals(500,game.getInt("playerScore"));assertEquals(0,game.getInt("computerScore"));
        for(JsonValue value:game.getJsonArray("rounds")){JsonObject r=(JsonObject)value;assertEquals("unavailable",r.getString("computerStatus"));assertTrue(r.isNull("evidence"));assertNotNull(r.getJsonObject("curatedEvidence"));}
    }
    JsonObject[] prepareRequest()throws Exception{
        session();ok("GET","/api/topic-requests",null);
        JsonObject row=ok("POST","/api/topic-requests",Json.createObjectBuilder().add("retryId",UUID.randomUUID().toString()).add("topic","Music").add("difficulty","easy").build());
        String selection=row.getJsonArray("choices").getJsonObject(0).getString("selectionId");
        row=ok("POST","/api/topic-requests/"+row.getString("id")+"/choose",Json.createObjectBuilder().add("retryId",UUID.randomUUID().toString()).add("selectionId",selection).build());assertEquals("ready",row.getString("state"));
        return new JsonObject[]{row,Json.createObjectBuilder().add("retryId",UUID.randomUUID().toString()).add("selectionId",selection).add("replay",false).build()};
    }
    @Test void requestIntentionBeforeGameCommitAndLostPlayResponseRecoverTheSameGame()throws Exception{
        boot("real","start","after-replace");JsonObject[] request=prepareRequest();String path="/api/topic-requests/"+request[0].getString("id")+"/play";
        crashMutation(path,request[1],"start");boot("real","none","none");JsonObject game=session().getJsonObject("game");
        assertEquals(game,ok("POST",path,request[1]));assertEquals(1,game.getInt("number"));
        JsonObject ledger=IntakeFiles.read(root.resolve("requests/ledger.json"));
        CookieManager cookies=(CookieManager)client.cookieHandler().orElseThrow();for(HttpCookie cookie:cookies.getCookieStore().getCookies())if(cookie.getName().startsWith("cluelab_game_"))assertFalse(ledger.toString().contains(cookie.getValue()));
    }
    @Test void requestIntentionWithoutCommittedGameCannotCreateAReplacementAfterCrash()throws Exception{
        boot("real","start","before-replace");JsonObject[] request=prepareRequest();String path="/api/topic-requests/"+request[0].getString("id")+"/play";
        crashMutation(path,request[1],"start");boot("real","none","none");assertTrue(session().isNull("game"));assertEquals(410,call("POST",path,request[1]).statusCode());assertTrue(session().isNull("game"));
    }
    @Test void explicitFirstUpgradeResetAndForeignCookieDoNotTransferOwnership()throws Exception{
        boot("real","none","none");CookieManager cookies=(CookieManager)client.cookieHandler().orElseThrow();HttpCookie old=new HttpCookie("cluelab",UUID.randomUUID().toString());old.setPath("/");cookies.getCookieStore().add(URI.create(url),old);
        assertEquals(410,call("GET","/api/session",null).statusCode());assertEquals(400,call("POST","/api/session/identity",Json.createObjectBuilder().add("confirmed",false).build()).statusCode());
        ok("POST","/api/session/identity",Json.createObjectBuilder().add("confirmed",true).build());JsonObject game=ready(start());
        for(HttpCookie cookie:cookies.getCookieStore().getCookies())if(cookie.getName().startsWith("cluelab_game_"))cookie.setValue(UUID.randomUUID().toString());
        assertEquals(410,call("GET","/api/session",null).statusCode());assertEquals(410,call("GET","/api/games/"+game.getString("id"),null).statusCode());
        ok("POST","/api/session/identity",Json.createObjectBuilder().add("confirmed",true).build());assertEquals(404,call("GET","/api/games/"+game.getString("id"),null).statusCode());
    }
}
