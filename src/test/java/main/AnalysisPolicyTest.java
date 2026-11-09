package main;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.LMJelinekMercerSimilarity;
import org.apache.lucene.store.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalysisPolicyTest {
    @TempDir Path root;
    static List<String> tokens(StandardAnalyzer analyzer,String text) throws Exception {
        List<String> terms=new ArrayList<>();
        try(var stream=analyzer.tokenStream("body",text)) {
            var term=stream.addAttribute(CharTermAttribute.class);stream.reset();
            while(stream.incrementToken())terms.add(term.toString());stream.end();
        }
        return terms;
    }
    @Test void modernPolicyExplicitlyKeepsFunctionWords() throws Exception {
        try(Indexer indexer=new Indexer(root.toString(),1)) {
            assertTrue(indexer.getAnalyzer().getStopwordSet().isEmpty());
            assertEquals(List.of("the","of","with","british"),tokens(indexer.getAnalyzer(),"the of with British"));
        }
    }
    @Test void symbolOnlySourceTitleRetainsItsDocumentInEveryMode() throws Exception {
        String title="\u30a0";
        for(int type=1;type<=3;type++)try(Indexer indexer=new Indexer(root.resolve("symbol-"+type).toString(),type)) {
            indexer.indexArticles(List.of(new Indexer.Article("symbol",title,"Writing","A double hyphen symbol used in Japanese writing.")),Map.of(),false);
            try(QueryEngine engine=new QueryEngine(type,4,indexer)) {
                assertEquals(1,engine.describeIndex().getInt("documents"));
                assertEquals(title,engine.ask("Japanese writing",10).candidates.get(0).title);
            }
        }
    }
    @Test void changedHyphenSpacingDoesNotChangeAnalyzedTerms() throws Exception {
        try(StandardAnalyzer analyzer=new StandardAnalyzer(CharArraySet.EMPTY_SET)) {
            assertEquals(tokens(analyzer,"two-word self-publishing air-launched stand-off tiger-cats"),
                    tokens(analyzer,"two - word self - publishing air - launched stand - off tiger - cats"));
        }
    }
    static String minimal(boolean indexStops,boolean queryStops) throws Exception {
        try(Directory directory=new ByteBuffersDirectory(); StandardAnalyzer analyzer=new StandardAnalyzer(indexStops?EnglishAnalyzer.ENGLISH_STOP_WORDS_SET:CharArraySet.EMPTY_SET)) {
            try(IndexWriter writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))) {
                for(String[] source:List.of(new String[]{"Answer","british standards"},new String[]{"Distractor","the the the of of of"})) {
                    Document doc=new Document();doc.add(new StoredField("title",source[0]));doc.add(new TextField("body",source[1],Field.Store.NO));writer.addDocument(doc);
                }
            }
            try(DirectoryReader reader=DirectoryReader.open(directory);StandardAnalyzer queryAnalyzer=new StandardAnalyzer(queryStops?EnglishAnalyzer.ENGLISH_STOP_WORDS_SET:CharArraySet.EMPTY_SET)) {
                IndexSearcher searcher=new IndexSearcher(reader);searcher.setSimilarity(new LMJelinekMercerSimilarity(.5f));
                Query query=new QueryParser("body",queryAnalyzer).parse("british standards the the of of");
                return reader.storedFields().document(searcher.search(query,2).scoreDocs[0].doc).get("title");
            }
        }
    }
    @Test void minimalReproducerIsAnAnalysisPolicyDifferenceNotAnAliasFix() throws Exception {
        assertEquals("Distractor",minimal(false,false));
        assertEquals("Answer",minimal(false,true));
        assertEquals("Answer",minimal(true,false));
        assertEquals("Answer",minimal(true,true));
    }
    @Test void guidedRegressionRemainsRecordedWithoutChangingScoringOrContent() throws Exception {
        List<String[]> questions=Evaluation.readQuestions(Files.readAllBytes(Paths.get("data/demo/easy-questions.txt")));
        try(Indexer indexer=new Indexer(root.toString(),1)) {
            indexer.parseWikis(Paths.get("data/demo/corpus"));
            try(QueryEngine engine=new QueryEngine(1,4,indexer)) {
                String[] clue=questions.get(1);var response=engine.ask(clue[0]+" "+clue[1],10);
                assertEquals("British Standards",clue[2]);
                assertEquals("Behistun Inscription",response.candidates.get(0).title);
                assertEquals("British Standards",response.candidates.get(2).title);
                assertFalse(Evaluation.accepts(clue[2],response.candidates.get(0).title));
                assertTrue(Evaluation.accepts(clue[2],response.candidates.get(2).title));
            }
        }
    }
}
