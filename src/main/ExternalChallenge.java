package main;

import jakarta.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.apache.lucene.index.*;
import org.apache.lucene.store.*;
import org.apache.lucene.util.BytesRef;

/** Local research benchmark only. Source indexing never opens question/answer files. */
public final class ExternalChallenge {
    private ExternalChallenge() {}
    static final String POLICY = "standard-no-stopwords-v1";
    static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }
    static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static String fingerprint(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536]; int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    static JsonObject verified(Path root, JsonObject freeze, String name) throws IOException {
        Path file = root.resolve(name);
        require(Files.isRegularFile(file), "Missing " + name + "; run the documented external preparation commands (private data is intentionally not committed).");
        require(fingerprint(file).equals(freeze.getJsonObject("hashes").getString(name)), "Frozen fingerprint differs: " + name + "; publish a new version, do not edit evaluated data.");
        return PackCatalog.read(file);
    }
    static JsonObject freeze(Path root) throws IOException {
        JsonObject frozen = PackCatalog.read(root.resolve("freeze.json"));
        require(frozen.getInt("schemaVersion") == 1, "Unsupported external challenge schema");
        require(frozen.getInt("indexType") == 1 && frozen.getInt("scorer") == 4
                && frozen.getString("profile").equals("baseline") && frozen.getString("analysisPolicy").equals(POLICY)
                && frozen.getInt("repetitions") == 3, "Only the frozen baseline/plain/JM/three-repeat protocol is supported; no test-set tuning.");
        JsonObject runtime = frozen.getJsonObject("runtime");
        require(runtime.getString("lucene").equals(RuntimeInfo.lucene()) && runtime.getString("corenlp").equals(RuntimeInfo.nlp())
                && runtime.getString("indexSchema").equals(RuntimeInfo.INDEX_SCHEMA), "Frozen runtime differs; preserve this challenge and record any later comparison separately.");
        return frozen;
    }
    static JsonObject source(Path root, JsonObject frozen) throws IOException {
        JsonObject plan = verified(root, frozen, "plan.json"), source = verified(root, frozen, "collection.json");
        require(plan.getInt("schemaVersion") == 1 && source.getInt("schemaVersion") == 1, "Unsupported plan/collection schema");
        require(plan.getString("id").equals(frozen.getString("id")) && plan.getString("version").equals(frozen.getString("version"))
                && frozen.getString("sourceScope").equals(source.getString("id")+"@"+source.getString("version")), "Frozen dataset/source identity differs");
        require(source.getJsonArray("sources").size() == plan.getInt("corpusExpectedFiles"), "Source shard count differs from plan");
        require(plan.getString("normalization").equals(frozen.getString("normalization")), "Normalization policy differs");
        Set<String> names = new HashSet<>(); int count = 0;
        for (JsonObject shard : source.getJsonArray("sources").getValuesAs(JsonObject.class)) {
            String file = shard.getString("file");
            require(file.matches("[A-Za-z0-9_.-]+\\.txt") && names.add(file), "Unsafe or duplicate source filename: " + file);
            require(shard.getInt("documents") > 0 && shard.getInt("bytes") > 0, "Empty source shard");
            count += shard.getInt("documents");
        }
        require(count == source.getInt("documents"), "Source document count differs");
        return source;
    }
    static List<JsonObject> cases(Path root, JsonObject frozen, String split) throws IOException {
        require(split.equals("development") || split.equals("test"), "Split must be development or test");
        JsonObject file = verified(root, frozen, "private/" + split + ".json"), plan = verified(root, frozen, "plan.json");
        require(file.getInt("schemaVersion") == 1 && file.getString("split").equals(split), "Invalid split identity/schema");
        require(file.getString("id").equals(frozen.getString("id")+"-"+split) && file.getString("version").equals(frozen.getString("version")), "Split ID/version differs");
        List<JsonObject> cases = file.getJsonArray("cases").getValuesAs(JsonObject.class);
        int requested = plan.getInt(split.equals("development") ? "developmentSize" : "testSize");
        require(!cases.isEmpty() && cases.size() <= requested, "Invalid split size; report any content shortfall explicitly");
        for (JsonObject clue : cases) {
            require(clue.getString("answerType").equals("article-title") && clue.getString("authorship").equals("external-verbatim"), "Unavailable answer type/authorship");
            require(PackCatalog.validText(clue.getString("question"), 4096), "Invalid question: " + clue.getString("id"));
            require(sha(clue.getString("question").getBytes(StandardCharsets.UTF_8)).equals(clue.getString("questionSha256")), "Question wording fingerprint differs");
            Set<String> labels = aliases(clue);
            require(labels.contains(Evaluation.normalize(clue.getString("canonicalTitle"))), "Canonical title missing from aliases");
            JsonObject upstream = clue.getJsonObject("upstreamAnswer");
            require(upstream.getString("Type").equals("WikipediaEntity") && upstream.getString("MatchedWikiEntityName").equals(clue.getString("canonicalTitle")), "Upstream answer identity differs");
            Set<String> supplied = new HashSet<>();
            supplied.add(Evaluation.normalize(upstream.getString("MatchedWikiEntityName")));
            supplied.add(Evaluation.normalize(upstream.getString("Value")));
            upstream.getJsonArray("Aliases").getValuesAs(JsonString.class).forEach(v -> supplied.add(Evaluation.normalize(v.getString())));
            require(labels.equals(supplied), "Invented or omitted upstream aliases: " + clue.getString("id"));
            String kind = clue.getJsonObject("coverage").getString("kind");
            require(Set.of("canonical-title", "alias-only", "missing").contains(kind), "Invalid coverage classification");
            require(clue.isNull("curatedSupport"), "No curated support is part of this external benchmark");
        }
        return cases;
    }
    static Set<String> aliases(JsonObject clue) throws IOException {
        Set<String> values = new HashSet<>();
        for (JsonValue value : clue.getJsonArray("aliases")) {
            require(value instanceof JsonString, "Aliases must be strings"); String alias = ((JsonString) value).getString();
            require(PackCatalog.validText(alias, 200) && !alias.contains("|") && values.add(Evaluation.normalize(alias)), "Invalid or repeated alias: " + clue.getString("id"));
        }
        require(!values.isEmpty(), "No aliases"); return values;
    }
    static void disjoint(List<JsonObject> dev, List<JsonObject> test) throws IOException {
        Set<String> ids = new HashSet<>(), questions = new HashSet<>(), answers = new HashSet<>();
        for (JsonObject clue : java.util.stream.Stream.concat(dev.stream(), test.stream()).toList()) {
            require(ids.add(clue.getString("id")) && questions.add(Evaluation.normalize(clue.getString("question"))), "Duplicate/cross-split ID or question");
            for (String alias : aliases(clue)) require(answers.add(alias), "Duplicate/cross-split answer alias");
        }
    }
    static String coverage(JsonObject clue, Set<String> titles) throws IOException {
        if (titles.contains(Evaluation.normalize(clue.getString("canonicalTitle")))) return "canonical-title";
        return aliases(clue).stream().anyMatch(titles::contains) ? "alias-only" : "missing";
    }
    static Set<String> corpus(Path directory, JsonObject source) throws IOException {
        Path wiki = directory.resolve("wiki"), members = directory.resolve("members.jsonl");
        require(fingerprint(members).equals(source.getString("membershipSha256")), "Corpus membership fingerprint differs");
        Set<String> expected = new HashSet<>();
        for (JsonObject shard : source.getJsonArray("sources").getValuesAs(JsonObject.class)) {
            String name = shard.getString("file"); expected.add(name); Path file = wiki.resolve(name);
            require(Files.size(file) == shard.getInt("bytes") && fingerprint(file).equals(shard.getString("sha256")), "Wrong source snapshot: " + name);
        }
        try (var files = Files.list(wiki)) {
            require(files.filter(p -> p.toString().endsWith(".txt")).map(p -> p.getFileName().toString()).collect(java.util.stream.Collectors.toSet()).equals(expected), "Unexpected/missing corpus .txt files");
        }
        Set<String> titles = new HashSet<>(); int count = 0;
        try (BufferedReader lines = Files.newBufferedReader(members, StandardCharsets.UTF_8)) {
            String line;
            while ((line = lines.readLine()) != null) {
                require(line.length() <= 16384, "Oversized membership row");
                try (JsonReader reader = Json.createReader(new StringReader(line))) {
                    JsonObject row = reader.readObject(); require(expected.contains(row.getString("file")), "Unknown membership source");
                    titles.add(Evaluation.normalize(row.getString("title"))); count++;
                }
            }
        }
        require(count == source.getInt("documents") && titles.size() == source.getInt("distinctNormalizedTitles"), "Corpus membership/title count differs");
        return titles;
    }
    static Set<String> indexedTitles(Path base, JsonObject frozen, JsonObject source) throws IOException {
        try (Indexer indexer = new Indexer(base.toString(), 1); Directory directory = FSDirectory.open(Paths.get(indexer.getIndexPath()));
             DirectoryReader reader = DirectoryReader.open(directory)) {
            Map<String,String> metadata = reader.getIndexCommit().getUserData();
            require(reader.numDocs() == source.getInt("documents")
                    && frozen.getJsonObject("hashes").getString("collection.json").equals(metadata.get("externalCollectionSha256"))
                    && source.getString("membershipSha256").equals(metadata.get("externalMembershipSha256"))
                    && POLICY.equals(metadata.get("analysisPolicy")) && RuntimeInfo.INDEX_SCHEMA.equals(metadata.get("cluelab.schema"))
                    && RuntimeInfo.lucene().equals(metadata.get("luceneVersion")) && RuntimeInfo.nlp().equals(metadata.get("nlpVersion")), "Index identity differs from frozen source/runtime; build a separate index.");
            Set<String> titles = new HashSet<>(); Terms terms = MultiTerms.getTerms(reader, "title");
            require(terms != null, "No indexed article titles"); TermsEnum iterator = terms.iterator(); BytesRef term;
            while ((term = iterator.next()) != null) titles.add(Evaluation.normalize(term.utf8ToString()));
            require(titles.size() == source.getInt("distinctNormalizedTitles"), "Indexed title coverage differs from source membership");
            return titles;
        }
    }
    static JsonObject measure(Path base, JsonObject frozen, JsonObject source, List<JsonObject> cases) throws Exception {
        Set<String> titles = indexedTitles(base, frozen, source);
        int first=0, hit=0, covered=0, canonical=0, aliasOnly=0; double reciprocal=0;
        List<Double> times = new ArrayList<>(); JsonArrayBuilder predictions=Json.createArrayBuilder();
        try (Indexer indexer = new Indexer(base.toString(),1); QueryEngine engine = new QueryEngine(1,4,indexer)) {
            double warmup = engine.ask("retrieval warmup",10).responseTimeMs;
            for (JsonObject clue : cases) {
                String available = coverage(clue,titles);
                require(available.equals(clue.getJsonObject("coverage").getString("kind")), "Declared answer coverage differs from actual index: " + clue.getString("id"));
                if (!available.equals("missing")) covered++;
                if (available.equals("canonical-title")) canonical++; else if (available.equals("alias-only")) aliasOnly++;
                QueryResponse response=null; List<String> previous=null;
                for(int repeat=0;repeat<frozen.getInt("repetitions");repeat++) {
                    // Entire retrieval input. No labels, coverage, sources, player answer or support.
                    response=engine.ask(clue.getString("question"),10); times.add(response.responseTimeMs);
                    List<String> ordered=response.candidates.stream().map(c->c.title).toList();
                    require(previous==null || previous.equals(ordered), "Nonrepeatable ranking: "+clue.getString("id")); previous=ordered;
                }
                Set<String> labels=aliases(clue); int rank=0; JsonArrayBuilder candidates=Json.createArrayBuilder();
                for(var candidate:response.candidates) {
                    if(rank==0 && labels.contains(Evaluation.normalize(candidate.title)))rank=candidate.rank;
                    candidates.add(Json.createObjectBuilder().add("rank",candidate.rank).add("title",candidate.title).add("score",candidate.score)
                            .add("retrievedEvidence",candidate.evidence).add("kind","opponent-retrieved").add("source",candidate.source));
                }
                if(rank==1)first++; if(rank>0){hit++;reciprocal+=1.0/rank;}
                require(!available.equals("missing") || rank==0, "Coverage/evaluation inconsistency");
                predictions.add(Json.createObjectBuilder().add("id",clue.getString("id")).add("question",clue.getString("question"))
                        .add("questionSource",clue.get("questionSource")).add("canonicalTitle",clue.getString("canonicalTitle"))
                        .add("aliases",clue.getJsonArray("aliases")).add("coverage",available).add("acceptedRank",rank).add("candidates",candidates).addNull("curatedSupport"));
            }
            Collections.sort(times); int size=cases.size();
            return Json.createObjectBuilder().add("schemaVersion",1).add("measuredAt",Instant.now().toString()).add("runtime",RuntimeInfo.json())
                    .add("profile","baseline").add("index",engine.describeIndex()).add("documents",source.getInt("documents"))
                    .add("total",size).add("correctFirst",first).add("foundWithin10",hit).add("accuracy",(double)first/size).add("hitAt10",(double)hit/size).add("mrrAt10",reciprocal/size)
                    .add("coverage",Json.createObjectBuilder().add("available",covered).add("canonicalTitle",canonical).add("aliasOnly",aliasOnly).add("missing",size-covered).add("rate",(double)covered/size)
                            .add("coveredNotFirst",covered-first).add("coveredOutsideTop10",covered-hit))
                    .add("timing",Json.createObjectBuilder().add("samples",times.size()).add("warmupMs",warmup).add("minMs",times.get(0))
                            .add("medianMs",percentile(times,.5)).add("p95Ms",percentile(times,.95)).add("meanMs",times.stream().mapToDouble(Double::doubleValue).average().orElseThrow()).add("maxMs",times.get(times.size()-1)))
                    .add("predictions",predictions).build();
        }
    }
    static double percentile(List<Double> sorted,double fraction){return sorted.get(Math.max(0,(int)Math.ceil(sorted.size()*fraction)-1));}
    static JsonObject summary(JsonObject report) {
        JsonObjectBuilder result=Json.createObjectBuilder(report); result.remove("predictions"); result.remove("index");
        JsonArrayBuilder cases=Json.createArrayBuilder(); report.getJsonArray("predictions").getValuesAs(JsonObject.class).forEach(p->cases.add(Json.createObjectBuilder()
                .add("id",p.getString("id")).add("coverage",p.getString("coverage")).add("acceptedRank",p.getInt("acceptedRank"))));
        return result.add("outcomes",cases).add("classification","external-verbatim-local-title-retrieval-not-official-TriviaQA-score").build();
    }
    static JsonObject implementation() throws IOException {
        JsonObjectBuilder hashes=Json.createObjectBuilder();
        for(String name:List.of("pom.xml","src/main/Indexer.java","src/main/QueryEngine.java","src/main/Evaluation.java","src/main/ExternalChallenge.java","tools/prepare-external-challenge.mjs"))
            hashes.add(name,fingerprint(Paths.get(name)));
        return hashes.build();
    }
    public static void main(String[] args) throws Exception {
        if(args.length<2)throw new IllegalArgumentException("ExternalChallenge validate <dataset> [corpus] | index <dataset> <corpus> <new-index-base> | evaluate <dataset> <index-base> <development|test>");
        Path root=Paths.get(args[1]); JsonObject frozen=freeze(root), source=source(root,frozen);
        if(args[0].equals("index")) {
            require(args.length==4,"index needs corpus and new index base"); corpus(Paths.get(args[2]),source);
            // Only public source metadata and corpus have been opened here.
            try(Indexer indexer=new Indexer(args[3],1)) {
                indexer.parseWikis(Paths.get(args[2]).resolve("wiki"),false,Map.of("externalCollectionSha256",frozen.getJsonObject("hashes").getString("collection.json"),
                        "externalMembershipSha256",source.getString("membershipSha256"),"analysisPolicy",POLICY));
            }
            indexedTitles(Paths.get(args[3]),frozen,source); System.out.println("Built frozen source-only external index."); return;
        }
        List<JsonObject> dev=cases(root,frozen,"development"), test=cases(root,frozen,"test"); disjoint(dev,test);
        if(args[0].equals("validate")) {
            require(args.length<=3,"validate accepts optional corpus directory");
            JsonObject selection=verified(root,frozen,"selection.json"), ledger=verified(root,frozen,"exclusions.json");
            Set<String> selected=new HashSet<>();
            for(JsonObject row:ledger.getJsonArray("candidates").getValuesAs(JsonObject.class))if(row.getString("disposition").equals("selected"))require(selected.add(row.getString("id")),"Duplicate selection ledger ID");
            for(String split:List.of("development","test")) {
                List<JsonObject> records=split.equals("development")?dev:test;
                List<JsonObject> identities=selection.getJsonObject("splits").getJsonArray(split).getValuesAs(JsonObject.class);
                require(records.size()==identities.size(),"Selection size differs");
                for(int i=0;i<records.size();i++)require(records.get(i).getString("id").equals(identities.get(i).getString("id"))
                        && records.get(i).getString("questionSha256").equals(identities.get(i).getString("questionSha256")) && selected.remove(records.get(i).getString("id")),"Selection/ledger identity differs");
            }
            require(selected.isEmpty(),"Extra ledger selections");
            if(args.length==3) {
                Set<String> titles=corpus(Paths.get(args[2]),source);
                for(JsonObject clue:java.util.stream.Stream.concat(dev.stream(),test.stream()).toList())
                    require(coverage(clue,titles).equals(clue.getJsonObject("coverage").getString("kind")),"Coverage differs: "+clue.getString("id"));
            }
            System.out.println(Json.createObjectBuilder().add("valid",true).add("development",dev.size()).add("test",test.size()).add("documents",source.getInt("documents")).add("humanApproved",false).build()); return;
        }
        require(args[0].equals("evaluate") && args.length==4,"evaluate needs dataset, index base, split");
        String split=args[3]; require(split.equals("development")||split.equals("test"),"Unknown split");
        JsonObject report=Json.createObjectBuilder(measure(Paths.get(args[2]),frozen,source,split.equals("development")?dev:test))
                .add("datasetId",frozen.getString("id")).add("datasetVersion",frozen.getString("version")).add("split",split)
                .add("freezeSha256",fingerprint(root.resolve("freeze.json"))).add("hashes",frozen.getJsonObject("hashes"))
                .add("implementationSha256",implementation()).build();
        Path details=Paths.get("corpus","external-challenge-results","external-"+split+"-"+System.currentTimeMillis()+".json"); Files.createDirectories(details.getParent());
        Files.writeString(details,report.toString()+"\n",StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);
        System.out.println(Json.createObjectBuilder(summary(report)).add("localDetails",details.toString()).add("localDetailsSha256",fingerprint(details)).build());
    }
}
