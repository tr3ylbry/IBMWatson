package main;

import jakarta.json.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Synthetic provider replies over preserved source snapshots; never shipped on the main classpath. */
final class ProposalFixtures {
    static JsonObject capture()throws IOException{return IntakeFiles.read(Path.of("drafts/jovian-v1/capture/capture.json"));}
    static ProposalProvider.Settings settings(){return new ProposalProvider.Settings("test-fixture","fixture-model",65536,8192,new BigDecimal("1"),new BigDecimal("4"),new BigDecimal("1"),new BigDecimal("10"),"2026-11-06");}
    static JsonObject input(String id){return Json.createObjectBuilder().add("requestId",id).add("operation","generate").add("captureId","jovian-capture").add("difficulty","standard").add("count",5)
            .add("draftId","assistance-rehearsal").add("topicId","jovian-assisted").add("version","1.0.0").add("label","Jovian Assistance Rehearsal")
            .add("description","Synthetic provider integration QA over saved sources; not a live model, human approval or difficulty study.").add("confirmed",true).build();}
    static JsonObject output(JsonObject capture){
        String[][] examples={
                {"Io (moon)","With over 400 active volcanoes","Which Galilean moon is known for more than 400 active volcanoes?","Io"},
                {"Ganymede (moon)","Ganymede is the only natural satellite in the solar system to possess","Which moon has its own internally generated magnetic field?","Ganymede"},
                {"Callisto (moon)","It is not in an orbital resonance like the three other Galilean satellites","Which Galilean moon lies outside the orbital resonance shared by the other three?","Callisto"},
                {"Europa (moon)","Europa ( ) or Jupiter II is the smallest","Which is the smallest of Jupiter's four Galilean moons?","Jupiter II"},
                {"Jupiter Icy Moons Explorer","Launched by ESA, from Guiana Space Centre","Which ESA spacecraft departed French Guiana on 14 April 2023?","Juice"}
        };
        JsonArrayBuilder clues=Json.createArrayBuilder();int n=0;
        for(String[] example:examples){n++;JsonObject source=capture.getJsonArray("sources").stream().map(v->(JsonObject)v).filter(v->v.getString("title").equals(example[0])).findFirst().orElseThrow();String text=source.getString("text");int start=text.indexOf(example[1]),end=text.indexOf('.',start)+1;if(start<0||end<=start)throw new AssertionError("Fixture source phrase unavailable: "+example[0]);
            clues.add(Json.createObjectBuilder().add("factId","fixture-fact-"+n).add("category","Jovian Facts").add("text",example[2]).add("hint","").add("difficulty","standard").add("answerType","article-title").add("answerPageId",source.getInt("pageId"))
                    .add("answerTitle",example[0]).add("aliases",Json.createArrayBuilder().add(example[0]).add(example[3])).add("support",Json.createObjectBuilder().add("pageId",source.getInt("pageId")).add("text",text.substring(start,end))));
        }return Json.createObjectBuilder().add("clues",clues).build();
    }
    static JsonObject envelope(JsonObject output){return Json.createObjectBuilder().add("id","resp_fixture").add("model","fixture-model").add("status","completed")
            .add("usage",Json.createObjectBuilder().add("input_tokens",1200).add("output_tokens",900))
            .add("output",Json.createArrayBuilder().add(Json.createObjectBuilder().add("type","message").add("role","assistant").add("content",Json.createArrayBuilder().add(Json.createObjectBuilder().add("type","output_text").add("text",output.toString()))))).build();}
    static ProposalProvider provider(JsonObject output){return new OpenAIProposalProvider(settings(),"fixture-not-a-credential",(body,key,id,budget)->new ProposalProvider.Reply(200,"req_fixture",IntakeFiles.json(envelope(output))));}
    static TopicBuilder builder(Path root,ProposalProvider provider)throws Exception{
        PackActivation.sandbox(Path.of("data/game.json"),root.resolve("player"),root.resolve("indexes"),8791);
        return new TopicBuilder(root.resolve("workshop"),root.resolve("drafts"),Map.of("normal",root.resolve("player/game.json"),"isolated",root.resolve("player/game.json")),TopicBuilder.examples(),()->{throw new IOException("No network in proposal fixtures");},null,provider);
    }
}
