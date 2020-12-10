package main;

import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.tartarus.snowball.ext.PorterStemmer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Scanner;

/**
 * IndexEngine is responsible for parsing the given input files
 * & generating the corresponding Lucene-supported Index to serve
 * as the backbone for IBM Watson.
 */
public class Indexer {
    private final int iType;
    private Directory index;
    private IndexWriter writer;
    private StandardAnalyzer analyzer = new StandardAnalyzer();

    private String dataPath;

    public Indexer(String dataPath, int indexType) {
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
        //analyzer = new StandardAnalyzer();
        index = FSDirectory.open(new File(dataPath).toPath());
        IndexWriterConfig config = new IndexWriterConfig(analyzer);

       writer = null;
        try {
            writer = new IndexWriter(index, config);
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }

        File wikis = new File("src/resources/wikiPages/");

        // For files in directory
        int i = 1;
        String filePath;
        for (String file : wikis.list()) {
            filePath = "src/resources/wikiPages/" + file;
            System.out.println("(" + i + "/80) File: " + filePath);
            buildIndex(filePath);
            i++;
        }
        writer.close();
        index.close();
    }

    public void buildIndex(String filePath) {
        boolean firstPage = true;
        try (Scanner sc = new Scanner(new File(filePath))) {
            String title = "";
            String category = "";
            StringBuilder header = new StringBuilder();
            StringBuilder text = new StringBuilder();
            while (sc.hasNextLine()) {
                String line = sc.nextLine().trim();

                if (isTitle(line)) {
                    if (!firstPage) {
                        addPage(title, category, header.toString(), text.toString());
                        header = new StringBuilder();
                        text = new StringBuilder();
                    }
                    firstPage = false;
                    title = parseTitle(line);
                } else if (isCategory(line)) {
                    category = parseCategory(line);
                } else if (isHeader(line)) {
                    header.append(parseHeader(line) + " ");
                } else {
                    text.append(parseText(line) + " ");
                }
            }
            addPage(title, category, header.toString(), text.toString());
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
            System.exit(0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

//    public void addPage(String title, String category, String content) {
//        // Trim whitespace before processing
//        title = title.trim();
//        category = category.trim();
//        content = content.trim();
//
//        // Pre-process params for empty fields
//        if (category.isEmpty()) {
//            category = ".";
//        }
//        if (content.isEmpty()) {
//            content = ".";
//        }
//
//        // Use StringBuilders to aggregate terms below
//        StringBuilder categorySB = new StringBuilder();
//        StringBuilder contentSB = new StringBuilder();
//
//        if (this.iType == 2) {
//            // Aggregate lemmas of category & content
//            for (String lemma: new Sentence(category).lemmas()) {
//                categorySB.append(lemma + " ");
//            }
//            for (String lemma: new Sentence(content).lemmas()) {
//                contentSB.append(lemma + " ");
//            }
//        } else if (this.iType == 3) {
//            // Use stemmer to stem category & content
//            PorterStemmer stemmer = new PorterStemmer();
//            for (String term: new Sentence(category).words()) {
//                stemmer.setCurrent(term);
//                stemmer.stem();
//                categorySB.append(stemmer.getCurrent() + " ");
//            }
//            for (String term: new Sentence(content).words()) {
//                stemmer.setCurrent(term);
//                stemmer.stem();
//                contentSB.append(stemmer.getCurrent() + " ");
//            }
//        } else {
//            // Default: Process without Lemma/Stemming, append as-is
//            categorySB.append(category);
//            contentSB.append(content);
//        }
//
//        // Create document & add to index
//        Document doc = new Document();
//
//        String textAttr = title + " " + categorySB.toString() + " " + contentSB.toString();
//        doc.add(new StringField("title", title, Field.Store.YES));
//        doc.add(new TextField("category", categorySB.toString(), Field.Store.YES));
//        doc.add(new TextField("text", textAttr, Field.Store.YES));
//
//        try {
//            writer.addDocument(doc);
//        } catch (IOException ex) {
//            ex.printStackTrace();
//            System.exit(0);
//        }
//    }
    public void addPage(String title, String cat, String hdr, String txt) throws IOException {
        String body = hdr.toLowerCase().trim() + " " + txt.toLowerCase().trim();
        body = body.trim();
        if (cat.isEmpty()) {
            cat = ".";
        }

        if (body.isEmpty()) {
            body = ".";
        }

        String[] indexedFields = indexMethodinator(cat, body);

        body = title + " " + indexedFields[0].toLowerCase() + " " + indexedFields[1].toLowerCase();
        
        Document doc = new Document();
        doc.add(new StringField("title", title.toLowerCase(), Field.Store.YES));
        doc.add(new TextField("category", indexedFields[0].toLowerCase().trim(), Field.Store.YES));
        doc.add(new TextField("body", body, Field.Store.YES));
        writer.addDocument(doc);
    }

    public String[] indexMethodinator(String cat, String body) {
        StringBuilder catBuilder = new StringBuilder();
        StringBuilder bodyBuilder = new StringBuilder();

        //System.out.println(body);
        if (this.iType == 2) { // Lemmatization
            for (String l: new Sentence(cat.toLowerCase()).lemmas()) {
                catBuilder.append(l + " ");
            }
            for (String l: new Sentence(body.toLowerCase()).lemmas()) {
                bodyBuilder.append(l + " ");
            }
            return new String[]{catBuilder.toString().toLowerCase(), bodyBuilder.toString().toLowerCase()};
        } else if (this.iType == 3) { // Stemming
            for (String word: new Sentence(cat.toLowerCase()).words()) {
                catBuilder.append(getStem(word) + " ");
            }
            for (String word: new Sentence(body.toLowerCase()).words()) {
                bodyBuilder.append(getStem(word) + " ");
            }
            return new String[]{catBuilder.toString().toLowerCase(), bodyBuilder.toString().toLowerCase()};
        } else {
            for (String word: new Sentence(cat.toLowerCase()).words()) {
                catBuilder.append(word + " ");
            }
            for (String word: new Sentence(body.toLowerCase()).words()) {
                bodyBuilder.append(word + " ");
            }
            return new String[]{catBuilder.toString().toLowerCase(), bodyBuilder.toString().toLowerCase()};
        }
    }

    private String getStem(String w) {
        PorterStemmer ps = new PorterStemmer();
        ps.setCurrent(w);
        ps.stem();
        return ps.getCurrent();
    }

    public boolean isTitle(String inLine) {
        return inLine.length() > 0
                && inLine.charAt(0) == '['
                && inLine.contains("[[")
                && inLine.contains("]]")
                && inLine.indexOf("[[") == 0
                && !inLine.contains("File:")
                && !inLine.contains("Image:");
    }

    public String parseTitle(String inLine) {
        return inLine.substring(2, inLine.length()-2);
    }

    public boolean isCategory(String inLine) {
        return inLine.length() > 0
                && inLine.contains("CATEGORIES:")
                && inLine.indexOf("CATEGORIES:") == 0;
    }

    public String parseCategory(String inLine) {
        return inLine.substring(12);
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

    public StandardAnalyzer getAnalyzer() {
        return analyzer;
    }
}
