package main;

import jakarta.json.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Routes immutable source scopes to indexes; has no dependency on private Clue objects. */
public final class CollectionIndex {
    private final Map<String, PackCatalog.Collection> collections;
    private final Path indexBase;
    private final int type, scorer;
    public CollectionIndex(GameConfig config) { this(config.catalog.collections, config.indexBase, config.indexType, config.scorer); }
    CollectionIndex(Map<String, PackCatalog.Collection> collections, Path indexBase, int type, int scorer) {
        this.collections = Collections.unmodifiableMap(new LinkedHashMap<>(collections)); this.indexBase = indexBase;
        this.type = type; this.scorer = scorer;
    }
    Path base(PackCatalog.Scope scope) {
        return indexBase.resolve(RuntimeInfo.indexNamespace()).resolve(scope.id + "-" + scope.version);
    }
    private PackCatalog.Collection collection(PackCatalog.Scope scope) throws IOException {
        PackCatalog.Collection collection = collections.get(scope.id);
        if (collection == null || !collection.scope.key().equals(scope.key()) || !collection.scope.digest.equals(scope.digest)) {
            throw new IOException("Unknown or changed source scope: " + scope.key());
        }
        return collection;
    }
    public QueryResponse ask(PackCatalog.Scope scope, String category, String clue) throws Exception {
        PackCatalog.Collection collection = collection(scope);
        try (Indexer indexer = new Indexer(base(scope).toString(), type); QueryEngine engine = new QueryEngine(type, scorer, indexer)) {
            verify(engine.describeIndex(), collection);
            QueryResponse response = engine.ask(category + " " + clue, 10);
            for (QueryResponse.Candidate candidate : response.candidates) {
                if (collection.title(candidate.title) == null) throw new IOException("Index returned an article outside its source scope.");
            }
            return response;
        }
    }
    public void verify(PackCatalog.Collection collection) throws IOException {
        describe(collection);
    }
    public JsonObject describe(PackCatalog.Collection collection) throws IOException {
        try (Indexer indexer = new Indexer(base(collection.scope).toString(), type); QueryEngine engine = new QueryEngine(type, scorer, indexer)) {
            JsonObject description = engine.describeIndex();
            verify(description, collection);
            return description;
        }
    }
    private void verify(JsonObject index, PackCatalog.Collection collection) throws IOException {
        JsonObject metadata = index.getJsonObject("metadata");
        if (metadata == null || !RuntimeInfo.lucene().equals(metadata.getString("luceneVersion", ""))
                || !RuntimeInfo.nlp().equals(metadata.getString("nlpVersion", ""))
                || !collection.scope.digest.equals(metadata.getString("collectionDigest", ""))
                || !collection.scope.id.equals(metadata.getString("collectionId", ""))
                || !collection.scope.version.equals(metadata.getString("collectionVersion", ""))
                || index.getInt("documents") != collection.sources.size()) {
            throw new IOException("Index does not match " + collection.scope.key() + "; explicitly rebuild pack indexes.");
        }
    }
    public void build(boolean rebuild) throws IOException {
        for (PackCatalog.Collection collection : collections.values()) {
            Path base = base(collection.scope);
            try (Indexer indexer = new Indexer(base.toString(), type)) {
                Path directory = Paths.get(indexer.getIndexPath());
                if (Files.exists(directory) && !rebuild) {
                    verify(collection); // Reuse only an exact, already built collection snapshot.
                    continue;
                }
                List<Indexer.Article> articles = new ArrayList<>();
                for (PackCatalog.Source source : collection.sources.values()) {
                    articles.add(new Indexer.Article(source.id, source.title, source.category, source.text));
                }
                Map<String, String> provenance = new LinkedHashMap<>();
                provenance.put("collectionId", collection.scope.id); provenance.put("collectionVersion", collection.scope.version);
                provenance.put("collectionDigest", collection.scope.digest);
                indexer.indexArticles(articles, provenance, rebuild);
            }
        }
    }
}
