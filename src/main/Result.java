package main;

import org.apache.lucene.document.Document;

public class Result {
    public Document docName;
    public double docScore;

    public Document getDocName() {
        return this.docName;
    }

    public double getDocScore() {
        return this.docScore;
    }

    public void setDocName(Document d) {
        this.docName = d;
    }

    public void setDocScore(double score) {
        this.docScore = score;
    }


}
