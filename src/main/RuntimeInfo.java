package main;

import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.util.Version;
import jakarta.json.Json;
import jakarta.json.JsonObject;

/** Runtime identity is part of index provenance, rather than a content-pack version. */
public final class RuntimeInfo {
    private RuntimeInfo() {}
    public static final String INDEX_SCHEMA = "3";
    public static String lucene() { return Version.LATEST.toString(); }
    public static String nlp() {
        String version = Sentence.class.getPackage().getImplementationVersion();
        if (version == null || !version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) {
            throw new IllegalStateException("CoreNLP runtime must declare its release version.");
        }
        return version;
    }
    public static String indexNamespace() { return "lucene-" + lucene() + "-nlp-" + nlp() + "-schema-" + INDEX_SCHEMA; }
    public static JsonObject json() {
        return Json.createObjectBuilder().add("java", System.getProperty("java.version"))
                .add("lucene", lucene()).add("corenlp", nlp()).add("indexSchema", INDEX_SCHEMA).build();
    }
}
