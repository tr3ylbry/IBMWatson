package main;

import jakarta.json.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/** Explicit test-classpath-only browser rehearsal. Never configured by the production server. */
public final class ProposalWorkshopFixtureServer {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath().normalize();TopicBuilder.privateWorkspace(root);
        Path config=root.resolve("player/game.json");if(!Files.exists(config))PackActivation.sandbox(Path.of("data/game.json"),config.getParent(),root.resolve("indexes"),8791);
        Path counter=root.resolve("fixture-calls.json");AtomicInteger calls=new AtomicInteger(Files.exists(counter)?IntakeFiles.read(counter).getInt("syntheticDispatches"):0);JsonObject capture=ProposalFixtures.capture(),output=ProposalFixtures.output(capture);JsonArrayBuilder rows=Json.createArrayBuilder();
        for(int i=0;i<output.getJsonArray("clues").size();i++){JsonObject clue=output.getJsonArray("clues").getJsonObject(i);rows.add(i==0?Json.createObjectBuilder(clue).add("text","<img src=x onerror=alert(1)> "+clue.getString("text")).build():clue);}JsonObject reply=Json.createObjectBuilder(output).add("clues",rows).build();
        ProposalProvider provider=new OpenAIProposalProvider(ProposalFixtures.settings(),"fixture-not-a-credential",(body,key,id,budget)->{
            JsonObject input=IntakeFiles.parse(body.getString("input"),"fixture provider input");if(!input.keySet().equals(java.util.Set.of("sources","count","difficulty")))throw new IOException("Fixture detected private generation inputs");
            Files.write(root.resolve("fixture-calls.json"),IntakeFiles.json(Json.createObjectBuilder().add("syntheticDispatches",calls.incrementAndGet()).add("liveProviderCalls",0).build()));
            if(args.length>1&&args[1].equals("uncertain"))throw new IOException("Synthetic uncertain outcome; no external call");
            return new ProposalProvider.Reply(200,"req_fixture_browser",IntakeFiles.json(ProposalFixtures.envelope(reply)));
        });
        String code=TopicBuilderServer.secret();TopicBuilder builder=new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",config,"isolated",config),TopicBuilder.examples(),()->{throw new IOException("Network source intake disabled in this test fixture; choose saved captured sources");},null,provider);
        try(TopicBuilderServer server=new TopicBuilderServer(8790,builder,code)){
            Runtime.getRuntime().addShutdownHook(new Thread(()->{try{server.close();}catch(IOException ignored){}}));server.start();System.out.println("TEST FIXTURE ONLY: "+server.url()+"; no live provider calls\nPrivate operator code: "+code);new CountDownLatch(1).await();
        }
    }
}
