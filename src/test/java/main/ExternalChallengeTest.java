package main;

import jakarta.json.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalChallengeTest {
    @TempDir Path root;
    Path dataset,corpus,base;
    JsonObject frozen,source;
    static JsonObject clue(String id,String question,String title,String kind) {
        return Json.createObjectBuilder().add("id",id).add("question",question).add("questionSha256",ExternalChallenge.sha(question.getBytes(StandardCharsets.UTF_8)))
                .add("questionSource","https://example.org/fixture").add("canonicalTitle",title).add("answerType","article-title")
                .add("aliases",Json.createArrayBuilder().add(title)).add("authorship","external-verbatim").addNull("curatedSupport")
                .add("coverage",Json.createObjectBuilder().add("kind",kind).add("documents",Json.createArrayBuilder()))
                .add("upstreamAnswer",Json.createObjectBuilder().add("Type","WikipediaEntity").add("MatchedWikiEntityName",title).add("Value",title).add("Aliases",Json.createArrayBuilder().add(title))).build();
    }
    void save(String name,JsonObject value) throws Exception {
        Path path=dataset.resolve(name);Files.createDirectories(path.getParent());Files.writeString(path,value.toString());
    }
    void repin(String name) throws Exception {
        JsonObject hashes=Json.createObjectBuilder(frozen.getJsonObject("hashes")).add(name,ExternalChallenge.fingerprint(dataset.resolve(name))).build();
        frozen=Json.createObjectBuilder(frozen).add("hashes",hashes).build();save("freeze.json",frozen);
    }
    void fixture() throws Exception {
        dataset=root.resolve("dataset");corpus=root.resolve("corpus");base=root.resolve("index");Files.createDirectories(corpus.resolve("wiki"));
        String text="[[Known]]\nCATEGORIES: Science\nquartz silicate mineral.\n\n[[Other]]\nCATEGORIES: Music\nfiddle violin leaked-label.\n";
        Files.writeString(corpus.resolve("wiki/source.txt"),text);
        String members=Json.createObjectBuilder().add("file","source.txt").add("title","Known").build()+"\n"
                +Json.createObjectBuilder().add("file","source.txt").add("title","Other").build()+"\n";
        Files.writeString(corpus.resolve("members.jsonl"),members);
        source=Json.createObjectBuilder().add("schemaVersion",1).add("id","fixture-source").add("version","1.0.0").add("documents",2).add("distinctNormalizedTitles",2)
                .add("membershipSha256",ExternalChallenge.sha(members.getBytes(StandardCharsets.UTF_8))).add("sources",Json.createArrayBuilder().add(Json.createObjectBuilder().add("file","source.txt")
                        .add("documents",2).add("bytes",text.getBytes(StandardCharsets.UTF_8).length).add("sha256",ExternalChallenge.sha(text.getBytes(StandardCharsets.UTF_8))))).build();
        save("collection.json",source);
        save("plan.json",Json.createObjectBuilder().add("schemaVersion",1).add("id","fixture").add("version","1.0.0").add("corpusExpectedFiles",1)
                .add("normalization","existing-java-policy").add("developmentSize",1).add("testSize",1).build());
        for(String split:List.of("development","test"))save("private/"+split+".json",Json.createObjectBuilder().add("schemaVersion",1).add("id","fixture-"+split).add("version","1.0.0").add("split",split)
                .add("cases",Json.createArrayBuilder().add(clue(split,"quartz",split.equals("development")?"Known":"Unavailable",split.equals("development")?"canonical-title":"missing"))).build());
        JsonObjectBuilder hashes=Json.createObjectBuilder();for(String name:List.of("plan.json","collection.json","private/development.json","private/test.json"))hashes.add(name,ExternalChallenge.fingerprint(dataset.resolve(name)));
        frozen=Json.createObjectBuilder().add("schemaVersion",1).add("id","fixture").add("version","1.0.0").add("hashes",hashes).add("indexType",1).add("scorer",4).add("profile","baseline")
                .add("analysisPolicy",ExternalChallenge.POLICY).add("repetitions",3).add("normalization","existing-java-policy").add("sourceScope","fixture-source@1.0.0")
                .add("runtime",Json.createObjectBuilder().add("lucene",RuntimeInfo.lucene()).add("corenlp",RuntimeInfo.nlp()).add("indexSchema",RuntimeInfo.INDEX_SCHEMA)).build();save("freeze.json",frozen);
    }
    void index() throws Exception {ExternalChallenge.main(new String[]{"index",dataset.toString(),corpus.toString(),base.toString()});}
    @Test void pinnedFilesAndUnsupportedProtocolsFailClosed() throws Exception {
        fixture();assertEquals(2,ExternalChallenge.source(dataset,ExternalChallenge.freeze(dataset)).getInt("documents"));
        save("plan.json",Json.createObjectBuilder().add("changed",true).build());assertThrows(IOException.class,()->ExternalChallenge.source(dataset,frozen));
        frozen=Json.createObjectBuilder(frozen).add("profile","coverage").build();save("freeze.json",frozen);assertThrows(IOException.class,()->ExternalChallenge.freeze(dataset));
        save("freeze.json",Json.createObjectBuilder(frozen).add("schemaVersion",2).build());assertThrows(IOException.class,()->ExternalChallenge.freeze(dataset));
    }
    @Test void labelsCannotBeInventedOrMovedAcrossSplits() throws Exception {
        fixture();JsonObject dev=ExternalChallenge.cases(dataset,frozen,"development").get(0);
        JsonObject modified=Json.createObjectBuilder(dev).add("aliases",Json.createArrayBuilder().add("Known").add("New unsupported alias")).build();
        save("private/development.json",Json.createObjectBuilder().add("schemaVersion",1).add("id","fixture-development").add("version","1.0.0").add("split","development").add("cases",Json.createArrayBuilder().add(modified)).build());repin("private/development.json");
        assertThrows(IOException.class,()->ExternalChallenge.cases(dataset,frozen,"development"));
        assertThrows(IOException.class,()->ExternalChallenge.disjoint(List.of(dev),List.of(clue("another","different question","KNOWN","canonical-title"))));
        assertThrows(IOException.class,()->ExternalChallenge.aliases(Json.createObjectBuilder(dev).add("aliases",Json.createArrayBuilder().add("Known").add(" KNOWN ")).build()));
    }
    @Test void changedQuestionAndUnavailableAnswerTypesAreRejected() throws Exception {
        fixture();JsonObject original=ExternalChallenge.cases(dataset,frozen,"test").get(0);
        for(JsonObject modified:List.of(Json.createObjectBuilder(original).add("question","rewritten").build(),Json.createObjectBuilder(original).add("answerType","number").build())) {
            save("private/test.json",Json.createObjectBuilder().add("schemaVersion",1).add("id","fixture-test").add("version","1.0.0").add("split","test").add("cases",Json.createArrayBuilder().add(modified)).build());repin("private/test.json");
            assertThrows(IOException.class,()->ExternalChallenge.cases(dataset,frozen,"test"));
        }
    }
    @Test void indexingNeedsNoQuestionAnswerOrPrivateDirectory() throws Exception {
        fixture();Files.delete(dataset.resolve("private/development.json"));Files.delete(dataset.resolve("private/test.json"));Files.delete(dataset.resolve("private"));
        index();assertEquals(Set.of("known","other"),ExternalChallenge.indexedTitles(base,frozen,source));
        assertThrows(IOException.class,this::index);
        assertEquals(2,ExternalChallenge.indexedTitles(base,frozen,source).size());
    }
    @Test void coverageMissesStayInTheDenominatorAndSummaryContainsNoQuestionsOrKeys() throws Exception {
        fixture();index();List<JsonObject> cases=List.of(clue("covered","quartz","Known","canonical-title"),clue("absent","quartz","Unavailable","missing"));
        JsonObject report=ExternalChallenge.measure(base,frozen,source,cases);
        assertEquals(2,report.getInt("total"));assertEquals(1,report.getInt("correctFirst"));assertEquals(.5,report.getJsonNumber("accuracy").doubleValue());
        assertEquals(.5,report.getJsonNumber("mrrAt10").doubleValue());assertEquals(1,report.getJsonObject("coverage").getInt("missing"));
        assertEquals(6,report.getJsonObject("timing").getInt("samples"));
        JsonObject summary=ExternalChallenge.summary(report);assertFalse(summary.containsKey("predictions"));assertFalse(summary.containsKey("index"));
        assertFalse(summary.toString().contains("quartz"));assertFalse(summary.toString().contains("Unavailable"));
    }
    @Test void changedLabelsAndPlayerFieldsNeverEnterRetrieval() throws Exception {
        fixture();index();JsonObject first=clue("one","quartz","Known","canonical-title");
        JsonObject other=Json.createObjectBuilder(clue("two","quartz","Other","canonical-title")).add("playerAnswer","leaked-label fiddle violin").build();
        JsonObject a=ExternalChallenge.measure(base,frozen,source,List.of(first)),b=ExternalChallenge.measure(base,frozen,source,List.of(other));
        assertEquals(a.getJsonArray("predictions").getJsonObject(0).getJsonArray("candidates"),b.getJsonArray("predictions").getJsonObject(0).getJsonArray("candidates"));
        assertEquals(1,a.getInt("correctFirst"));assertEquals(0,b.getInt("correctFirst"));
        assertTrue(a.getJsonArray("predictions").getJsonObject(0).isNull("curatedSupport"));
    }
    @Test void corpusAndDeclaredCoverageMustMatchActualSourcesAndIndex() throws Exception {
        fixture();index();assertEquals("alias-only",ExternalChallenge.coverage(Json.createObjectBuilder(clue("alias","quartz","Absent","alias-only")).add("aliases",Json.createArrayBuilder().add("Absent").add("KNOWN")).build(),Set.of("known")));
        assertThrows(IOException.class,()->ExternalChallenge.measure(base,frozen,source,List.of(clue("wrong","quartz","Known","missing"))));
        Files.writeString(corpus.resolve("wiki/source.txt"),"altered");assertThrows(IOException.class,()->ExternalChallenge.corpus(corpus,source));
    }
    @Test void frozenPublicIdentityRemainsSeparateFromPlayableCatalog() throws Exception {
        JsonObject frozen=PackCatalog.read(Paths.get("data/external-challenge/v1/freeze.json"));
        assertEquals("baseline",frozen.getString("profile"));assertEquals(150,PackCatalog.read(Paths.get("data/external-challenge/v1/selection.json")).getJsonObject("splits").getJsonArray("test").size());
        assertFalse(Files.readString(Paths.get("data/packs/catalog.json")).contains("triviaqa"));
        JsonObject source=PackCatalog.read(Paths.get("data/external-challenge/v1/collection.json"));assertEquals(279775,source.getInt("documents"));
        assertTrue(source.isNull("revisionId"));assertFalse(source.containsKey("cases"));
        assertTrue(Files.readString(Paths.get("data/external-challenge/v1/UPSTREAM-README.txt")).contains("non-commercial research purposes only"));
    }
}
