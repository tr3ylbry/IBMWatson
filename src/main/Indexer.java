package main;

import edu.stanford.nlp.simple.Sentence;
import edu.stanford.nlp.process.PTBTokenizer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.tartarus.snowball.ext.PorterStemmer;

import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * IndexEngine is responsible for parsing the given input files
 * & generating the corresponding Lucene-supported Index to serve
 * as the backbone for IBM Watson.
 */
public class Indexer implements AutoCloseable {
    /** Searchable source material only; never pass a clue pack or its answer metadata here. */
    public static final class Article {
        final String id, title, category, text;
        public Article(String id, String title, String category, String text) {
            this.id = id; this.title = title; this.category = category; this.text = text;
        }
    }
    private final int iType;
    private IndexWriter writer;
    // Freeze the modern baseline explicitly; Lucene 7's implicit default removed English stop words.
    private final StandardAnalyzer analyzer = new StandardAnalyzer(CharArraySet.EMPTY_SET);

    private String dataPath;
    private String sourceFile = "";
    private int sourceLine;

    public Indexer(String dataPath, int indexType) {
        if (dataPath == null || dataPath.trim().isEmpty()) {
            throw new IllegalArgumentException("An index base directory is required.");
        }
        if (indexType < 1 || indexType > 3) {
            throw new IllegalArgumentException("Index type must be 1 (default), 2 (lemma), or 3 (stem).");
        }
        dataPath = Paths.get(dataPath).toString() + File.separator;
        if (indexType == 1) {
            this.dataPath = dataPath + "1_idxTypeNone";
        } else if (indexType == 2) {
            this.dataPath = dataPath + "2_idxTypeLemma";
        } else if (indexType == 3) {
            this.dataPath = dataPath + "3_idxTypeStem";
        }

        this.iType = indexType;
    }

    public void parseWikis() throws IOException {
        parseWikis(Paths.get("src/resources/wikiPages"));
    }

    /** Index validated snapshots directly, without round-tripping through the historical wiki parser. */
    public void indexArticles(List<Article> articles, Map<String, String> provenance, boolean rebuild) throws IOException {
        if (articles.isEmpty()) throw new IOException("A source collection must contain articles.");
        try (Directory directory = FSDirectory.open(Paths.get(dataPath))) {
            if (DirectoryReader.indexExists(directory) && !rebuild) throw new IOException("Index exists; use --rebuild: " + dataPath);
            protectExistingIndex(directory);
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(rebuild ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            try {
                if (!rebuild && DirectoryReader.indexExists(directory)) throw new IOException("Index exists; use --rebuild: " + dataPath);
                for (Article article : articles) {
                    sourceFile = article.id; sourceLine = 1;
                    addPage(article.title, article.category, "", article.text);
                }
                Map<String, String> metadata = new LinkedHashMap<>(provenance);
                metadata.put("cluelab.schema", RuntimeInfo.INDEX_SCHEMA); metadata.put("indexType", Integer.toString(iType));
                metadata.put("builtAt", Instant.now().toString()); metadata.put("documents", Integer.toString(articles.size()));
                metadata.put("norms", "BM25-lucene" + org.apache.lucene.util.Version.LATEST.major);
                metadata.put("luceneVersion", RuntimeInfo.lucene()); metadata.put("nlpVersion", RuntimeInfo.nlp());
                writer.setLiveCommitData(metadata.entrySet()); writer.commit();
            } catch (IOException | RuntimeException error) {
                try { writer.rollback(); } catch (IOException rollback) { error.addSuppressed(rollback); }
                finally { writer = null; }
                throw error;
            } finally { if (writer != null) { writer.close(); writer = null; } }
        }
    }

    /** Explicitly rebuild an index. Failed rebuilds retain the previous commit. */
    public void parseWikis(Path corpus) throws IOException {
        parseWikis(corpus, false);
    }

    /** Replacing an existing index requires explicit caller consent. */
    public void parseWikis(Path corpus, boolean rebuild) throws IOException {
        parseWikis(corpus, rebuild, java.util.Collections.emptyMap());
    }

    void parseWikis(Path corpus, boolean rebuild, Map<String, String> provenance) throws IOException {
        if (!Files.isDirectory(corpus)) {
            throw new IOException("Wikipedia directory does not exist: " + corpus);
        }
        List<Path> files;
        try (Stream<Path> paths = Files.list(corpus)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .sorted().collect(Collectors.toList());
        }
        if (files.isEmpty()) {
            throw new IOException("Wikipedia directory contains no .txt files: " + corpus);
        }
        try (Directory directory = FSDirectory.open(Paths.get(dataPath))) {
            if (DirectoryReader.indexExists(directory) && !rebuild) {
                throw new IOException("Index already exists; use --rebuild to replace it: " + dataPath);
            }
            protectExistingIndex(directory);
            IndexWriterConfig config = new IndexWriterConfig(analyzer)
                    .setOpenMode(rebuild ? IndexWriterConfig.OpenMode.CREATE : IndexWriterConfig.OpenMode.CREATE_OR_APPEND)
                    .setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            try {
                // Recheck while holding Lucene's writer lock to close the create/check race.
                if (!rebuild && DirectoryReader.indexExists(directory)) {
                    throw new IOException("Index already exists; use --rebuild to replace it: " + dataPath);
                }
                int pages = 0;
                for (int i = 0; i < files.size(); i++) {
                    int count;
                    try { count = buildIndex(files.get(i).toString()); }
                    catch (IOException error) {
                        throw new IOException("Cannot read corpus file " + files.get(i) + ": " + error.getMessage(), error);
                    }
                    if (count == 0) throw new IOException("No article titles in corpus file: " + files.get(i));
                    pages += count;
                }
                if (pages == 0) {
                    throw new IOException("No Wikipedia article titles found in: " + corpus);
                }
                Map<String, String> metadata = new LinkedHashMap<>(provenance);
                metadata.put("cluelab.schema", RuntimeInfo.INDEX_SCHEMA);
                metadata.put("indexType", Integer.toString(iType));
                metadata.put("builtAt", Instant.now().toString());
                metadata.put("corpus", corpus.toRealPath().toString());
                metadata.put("sourceFiles", Integer.toString(files.size()));
                metadata.put("documents", Integer.toString(pages));
                metadata.put("norms", "BM25-lucene" + org.apache.lucene.util.Version.LATEST.major);
                metadata.put("luceneVersion", RuntimeInfo.lucene()); metadata.put("nlpVersion", RuntimeInfo.nlp());
                writer.setLiveCommitData(metadata.entrySet());
                writer.commit();
            } catch (IOException | RuntimeException error) {
                try { writer.rollback(); }
                catch (IOException rollbackError) { error.addSuppressed(rollbackError); }
                finally { writer = null; }
                throw error;
            } finally {
                if (writer != null) {
                    writer.close();
                    writer = null;
                }
            }
        }
    }

    public int buildIndex(String filePath) throws IOException {
        sourceFile = Paths.get(filePath).getFileName().toString();
        boolean firstPage = true;
        int pages = 0;
        try (BufferedReader input = Files.newBufferedReader(Paths.get(filePath), StandardCharsets.UTF_8)) {
            String title = "";
            String category = "";
            StringBuilder header = new StringBuilder();
            StringBuilder text = new StringBuilder();
            int lineNumber = 0;
            String rawLine;
            while ((rawLine = input.readLine()) != null) {
                String line = rawLine.trim();
                lineNumber++;

                if (isTitle(line)) {
                    if (!firstPage) {
                        addPage(title, category, header.toString(), text.toString());
                        pages++;
                        header = new StringBuilder();
                        text = new StringBuilder();
                    }
                    category = "";
                    sourceLine = lineNumber;
                    firstPage = false;
                    title = parseTitle(line);
                } else if (firstPage) {
                    continue;
                } else if (isCategory(line)) {
                    category = parseCategory(line);
                } else if (isHeader(line)) {
                    header.append(parseHeader(line) + " ");
                } else {
                    text.append(parseText(line) + " ");
                }
            }
            if (!firstPage) {
                addPage(title, category, header.toString(), text.toString());
                pages++;
            }
        }
        return pages;
    }

    private void protectExistingIndex(Directory directory) throws IOException {
        if (!DirectoryReader.indexExists(directory)) return;
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            Map<String, String> metadata = reader.getIndexCommit().getUserData();
            if (!RuntimeInfo.INDEX_SCHEMA.equals(metadata.get("cluelab.schema"))
                    || !RuntimeInfo.lucene().equals(metadata.get("luceneVersion"))
                    || !RuntimeInfo.nlp().equals(metadata.get("nlpVersion"))) {
                throw new IOException("Refusing to replace an index from another runtime. Build into a separate index base; preserve the original: " + dataPath);
            }
        } catch (org.apache.lucene.index.IndexFormatTooOldException error) {
            throw new IOException("Refusing to replace a historical index, even with --rebuild. Choose a new index base; use legacy-baseline for read-only reproduction.", error);
        }
    }

    public void addPage(String title, String cat, String hdr, String txt) throws IOException {
        if (writer == null) throw new IllegalStateException("Use parseWikis to open an indexing transaction first.");
        String body = hdr.toLowerCase(Locale.ROOT).trim() + " " + txt.toLowerCase(Locale.ROOT).trim();
        body = body.trim();
        if (cat.isEmpty()) {
            cat = ".";
        }

        if (body.isEmpty()) {
            body = ".";
        }

        String[] indexedFields = indexMethodinator(cat, body);

        body = title + " " + indexedFields[0].toLowerCase(Locale.ROOT) + " " + indexedFields[1].toLowerCase(Locale.ROOT);

        Document doc = new Document();
        doc.add(new StoredField("displayTitle", title));
        doc.add(new StoredField("evidence", (hdr + " " + txt).trim().replaceAll("\\s+", " ")));
        doc.add(new StoredField("sourceFile", sourceFile));
        doc.add(new StoredField("sourceLine", sourceLine));
        doc.add(new StringField("title", title.toLowerCase(Locale.ROOT), Field.Store.YES));
        doc.add(new TextField("titleText", indexMethodinator(".", title)[1].trim(), Field.Store.NO));
        doc.add(new TextField("category", indexedFields[0].toLowerCase(Locale.ROOT).trim(), Field.Store.YES));
        doc.add(new TextField("body", body, Field.Store.YES));
        writer.addDocument(doc);
    }

    public String[] indexMethodinator(String cat, String body) {
        StringBuilder catBuilder = new StringBuilder();
        StringBuilder bodyBuilder = new StringBuilder();

        //System.out.println(body);
        if (this.iType == 2) { // Lemmatization
            for (String l: sourceTokens(cat, true)) {
                catBuilder.append(l + " ");
            }
            for (String l: sourceTokens(body, true)) {
                bodyBuilder.append(l + " ");
            }
            return new String[]{catBuilder.toString().toLowerCase(Locale.ROOT), bodyBuilder.toString().toLowerCase(Locale.ROOT)};
        } else if (this.iType == 3) { // Stemming
            for (String word: sourceTokens(cat, false)) {
                catBuilder.append(getStem(word) + " ");
            }
            for (String word: sourceTokens(body, false)) {
                bodyBuilder.append(getStem(word) + " ");
            }
            return new String[]{catBuilder.toString().toLowerCase(Locale.ROOT), bodyBuilder.toString().toLowerCase(Locale.ROOT)};
        } else {
            for (String word: sourceTokens(cat, false)) {
                catBuilder.append(word + " ");
            }
            for (String word: sourceTokens(body, false)) {
                bodyBuilder.append(word + " ");
            }
            return new String[]{catBuilder.toString().toLowerCase(Locale.ROOT), bodyBuilder.toString().toLowerCase(Locale.ROOT)};
        }
    }

    static List<String> sourceTokens(String text, boolean lemmas) {
        String lower = text.toLowerCase(Locale.ROOT);
        try {
            Sentence sentence = new Sentence(lower);
            return lemmas ? sentence.lemmas() : sentence.words();
        } catch (IllegalStateException error) {
            // Some symbol-only titles (e.g. U+30A0) yield zero PTB tokens. Keep
            // the document/raw title, not a fabricated token or a skipped page.
            if ("unexpected empty sentence: []".equals(error.getMessage())
                    && !PTBTokenizer.newPTBTokenizer(new java.io.StringReader(lower)).hasNext()) {
                return java.util.Collections.emptyList();
            }
            throw error;
        }
    }

    private String getStem(String w) {
        PorterStemmer ps = new PorterStemmer();
        ps.setCurrent(w);
        ps.stem();
        return ps.getCurrent();
    }

    public boolean isTitle(String inLine) {
        if (inLine == null || inLine.length() < 5 || !inLine.startsWith("[[") || !inLine.endsWith("]]")) {
            return false;
        }
        String title = inLine.substring(2, inLine.length() - 2).trim();
        return !title.isEmpty() && !title.contains("[") && !title.contains("]")
                && !title.contains("|") && !title.startsWith("File:") && !title.startsWith("Image:");
    }

    public String parseTitle(String inLine) {
        if (!isTitle(inLine)) throw new IllegalArgumentException("Invalid article title boundary: " + inLine);
        return inLine.substring(2, inLine.length()-2).trim();
    }

    public boolean isCategory(String inLine) {
        return inLine.length() > 0
                && inLine.contains("CATEGORIES:")
                && inLine.indexOf("CATEGORIES:") == 0;
    }

    public String parseCategory(String inLine) {
        return inLine.substring("CATEGORIES:".length()).trim();
    }

    public boolean isHeader(String inLine) {
        return inLine.length() > 0
                && inLine.charAt(0) == '='
                && inLine.charAt(inLine.length() - 1) == '=';
    }

    public String parseHeader(String inLine) {
        return inLine.replace("=", "");
    }

    /**
     * Sanitizes data by removing unnecessary tags to prepare for
     * processing.
     * @param inLine
     * @return
     */
    public String parseText(String inLine) {
        if (inLine.contains("tpl")) {
            inLine = inLine.replaceAll("\\[tpl\\]", " ");
            inLine = inLine.replaceAll("\\[/tpl\\]", " ");
        }
        return inLine.trim();
    }

    public String getIndexPath() {
        return this.dataPath;
    }

    public int getIndexType() { return iType; }

    public StandardAnalyzer getAnalyzer() {
        return analyzer;
    }

    @Override
    public void close() {
        analyzer.close();
    }
}
