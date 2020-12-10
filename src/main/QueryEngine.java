package main;


import edu.stanford.nlp.simple.Sentence;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.search.similarities.BooleanSimilarity;
import org.apache.lucene.search.similarities.ClassicSimilarity;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.tartarus.snowball.ext.PorterStemmer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;

public class QueryEngine {
    private int qType;
    private int iType;

    private Indexer indexer;
    private IndexReader reader;
    private IndexSearcher searcher;

    public QueryEngine(int indexType, int queryType, Indexer indexer) {
        this.iType = indexType;
        this.qType = queryType;
        this.indexer = indexer;

        try {
            Directory index = FSDirectory.open(new File(indexer.getIndexPath()).toPath());
            reader = DirectoryReader.open(index);
        } catch (IOException e) {
            e.printStackTrace();
        }

        searcher = new IndexSearcher(reader); // BM25 by default
        searcher.setSimilarity(new BM25Similarity());
        if (this.qType == 2) {
            searcher.setSimilarity(new BooleanSimilarity());
        } else if (this.qType == 3) {
            searcher.setSimilarity(new ClassicSimilarity()); // TF-IDF
        } else if (this.qType == 4) {
            searcher.setSimilarity(new LMJelinekMercerSimilarity((float) 0.5));
        }
    }

    public void sherlockinator() {
        // CATEGORY
        // Hint
        // Expected Answer
        // *Blank Line*

        Scanner sc = null;
        try {
            sc = new Scanner(new File("questions.txt"));
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(0);
        }
        String category = "";
        String hint = "";
        String expected;

        int lineNum = 0;
        int correctAnswers = 0;
        int totalQueries = 0;
        while (sc.hasNextLine()) {
            String line = sc.nextLine();
            switch (lineNum % 4) {
                case 0:
                    category = line.trim();
                    break;
                case 1:
                    hint = line.trim();
                    break;
                case 2:
                    expected = line.toLowerCase().trim();

                    ArrayList<Result> answersFound = questionHelper(category + " " + hint);

                    //System.out.println(topDocName.get("title") + ", expected: " + expected);
                    if (answersFound.size() > 0 && answersFound.get(0).getDocName().get("title").equals(expected)) {
                        Document topDocName = answersFound.get(0).getDocName();
                        System.out.println(topDocName.get("title") + ", expected: " + expected);
                        correctAnswers++;
                    }
                    totalQueries++;
                    break;
                case 3:
                    break;
            }
            lineNum++;
        }
        double score = (double) correctAnswers / (double) totalQueries;

        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Percentage Correct: " + score);
    }

    private ArrayList<Result> questionHelper(String query) {
        // TODO: later, put the lines below in a helper function; when the
        // other indexing and query methods are supported
        ArrayList<Result> answersFound = new ArrayList<>();

        String refinedQuery = queryMethodinator(query);

        try {
            Query q = new QueryParser("body", indexer.getAnalyzer()).parse(refinedQuery);

            int hitsPerPage = 10;
            TopDocs docs = searcher.search(q, hitsPerPage);
            ScoreDoc[] hits = docs.scoreDocs;

            for (ScoreDoc docId : hits) {
                Document d = searcher.doc(docId.doc);

                Result rslt = new Result();
                rslt.setDocName(d);
                rslt.setDocScore(docId.score);

                answersFound.add(rslt);
            }
        } catch (IOException | ParseException e) {
            e.printStackTrace();
        } // TODO Auto-generated catch block

        return answersFound;
    }

    public String queryMethodinator(String q) {
        Sentence s = new Sentence(q.toLowerCase());
        StringBuilder retStr = new StringBuilder();

        if (this.iType == 2) {
            for (String l : s.lemmas()) {
                retStr.append(l + " ");
            }
        } else if (this.iType == 3) {
            PorterStemmer ps = new PorterStemmer();
            for (String w : s.words()) {
                ps.setCurrent(w);
                ps.stem();
                retStr.append(ps.getCurrent() + " ");
            }
        } else {
            for (String word : s.words()) {
                retStr.append(word + " ");
            }
        }

        return retStr.toString();
    }


}
