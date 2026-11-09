package main;

import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.util.Version;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import java.io.IOException;
import java.util.Map;

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

    /** Untagged native-format reads remain unverified; any declaration requires the complete identity. */
    static void requireIndexIdentity(Map<String, String> metadata, int indexType, boolean allowUntagged) throws IOException {
        Map<String, String> running = Map.of("cluelab.schema", INDEX_SCHEMA, "indexType", Integer.toString(indexType),
                "luceneVersion", lucene(), "nlpVersion", nlp());
        if (allowUntagged && running.keySet().stream().noneMatch(metadata::containsKey)) return;
        if (running.entrySet().stream().allMatch(entry -> entry.getValue().equals(metadata.get(entry.getKey())))) return;
        StringBuilder declared = new StringBuilder(), expected = new StringBuilder();
        for (String key : new String[]{"cluelab.schema", "indexType", "luceneVersion", "nlpVersion"}) {
            if (!declared.isEmpty()) { declared.append(", "); expected.append(", "); }
            declared.append(key).append('=').append(metadata.getOrDefault(key, "<missing>"));
            expected.append(key).append('=').append(running.get(key));
        }
        throw new IOException("Incompatible index identity: declared {" + declared + "}; running {" + expected
                + "}. Declared identities must be complete and match. Use a matching runtime (legacy-baseline for historical indexes)"
                + " or build from trusted sources into a separate index base; the index was not changed.");
    }

    public static JsonObject json() {
        return Json.createObjectBuilder().add("java", System.getProperty("java.version"))
                .add("lucene", lucene()).add("corenlp", nlp()).add("indexSchema", INDEX_SCHEMA).build();
    }
}
