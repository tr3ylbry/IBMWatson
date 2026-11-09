package main;
import jakarta.json.*;
import java.nio.file.*;
import java.util.*;

/** Post-evaluation explanations only. Original question is the sole engine input. */
public final class ExternalFailureProbe {
    public static void main(String[] args) throws Exception {
        if(args.length<3)throw new IllegalArgumentException("Expected index-base local-detailed-report question-id...");
        JsonObject report=PackCatalog.read(Paths.get(args[1]));Set<String> wanted=new HashSet<>(Arrays.asList(args).subList(2,args.length));
        JsonArrayBuilder rows=Json.createArrayBuilder();
        try(Indexer indexer=new Indexer(args[0],1);QueryEngine engine=new QueryEngine(1,4,indexer)) {
            for(JsonObject prediction:report.getJsonArray("predictions").getValuesAs(JsonObject.class))if(wanted.remove(prediction.getString("id"))) {
                JsonArrayBuilder hits=Json.createArrayBuilder();String question=prediction.getString("question");
                for(Result hit:engine.search(question,3))hits.add(Json.createObjectBuilder().add("title",hit.getDocName().get("displayTitle"))
                        .add("score",hit.getDocScore()).add("explanation",engine.explain(question,hit.getDocumentId())));
                rows.add(Json.createObjectBuilder().add("id",prediction.getString("id")).add("baselineExplanations",hits));
            }
        }
        if(!wanted.isEmpty())throw new IllegalArgumentException("IDs missing from measured report: "+wanted);
        System.out.println(Json.createObjectBuilder().add("classification","post-evaluation-baseline-explanations-not-tuning").add("rows",rows).build());
    }
}
