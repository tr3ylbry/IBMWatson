package main;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Scanner;

/**
 * IndexEngine is responsible for parsing the given input files
 * & generating the corresponding Lucene-supported Index to serve
 * as the backbone for IBM Watson.
 */
public class IndexEngine {

    public String dataPath;

    public IndexEngine(String dataPath) {
        this.dataPath = dataPath;
    }

    public void parseWikiData() throws IOException {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        Directory index = FSDirectory.open(new File(dataPath).toPath());
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        IndexWriter w = new IndexWriter(index, config);

        File dir = new File("src/resources");

        // For files in directory
        String filePath;
        for(String file : dir.list()) {
            filePath = "src/resources/" + file;
            System.out.println("File: " + filePath);
            parseFile(filePath);

        }
        w.close();
        index.close();
    }

    public void parseFile(String filePath) {
        try (Scanner sc = new Scanner(new File(filePath))) {
            while (sc.hasNextLine()) {
                String lineToProcess = sc.nextLine();

                String title = "";
                String category = "";
                String header = "";
                String text = "";
                // TODO: Pre-processing to extract title & other info
                if (isTitle(lineToProcess)) {
                    title = extractTitle(lineToProcess);
                } else if (isCategory(lineToProcess)) {
                    category = extractCategory(lineToProcess);
                } else if (isHeader(lineToProcess)) {
                    // TODO: Extract header & add to the TEXT value to allow it to be indexable
                    header = extractHeader(lineToProcess);
                } else {
                    // TODO: Extract sanitized 'Text' corresponding to title/category
                }
            }
        } catch (FileNotFoundException ex) {
            ex.printStackTrace();
            System.exit(0);
        }
    }

    public boolean isTitle(String lineToProcess) {
        return lineToProcess.length() > 0
                && lineToProcess.charAt(0) == '['
                && lineToProcess.contains("[[")
                && lineToProcess.contains("]]")
                && lineToProcess.contains("File:") == false
                && lineToProcess.contains("Image:") == false;
    }

    public String extractTitle(String lineToProcess) {
        return lineToProcess.trim().substring(2, lineToProcess.length()-2);
    }

    public boolean isCategory(String lineToProcess) {
        return lineToProcess.length() > 0
                && lineToProcess.contains("CATEGORIES:")
                && lineToProcess.indexOf("CATEGORIES:") == 0;
    }

    public String extractCategory(String lineToProcess) {
        return lineToProcess.trim().substring(12);
    }

    public boolean isHeader(String lineToProcess) {
        return true; // TODO: Fix this logic
    }

    public String extractHeader(String lineToProcess) {
        return "";
    }

    /**
     * Sanitizes data by removing unnecessary tags to prepare for
     * processing.
     * @param data
     * @return
     */
    public String sanitize(String data) {
        return "";
    }
}
