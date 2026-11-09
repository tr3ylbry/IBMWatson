package main;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class DemoIntegrationTest {
    @Test void guidedDemoPlaysEightRealEngineRoundsWithPrivateKeysAndAliasScoring() throws Exception {
        Path root = Files.createTempDirectory("cluelab-play-");
        try (Indexer indexer = new Indexer(root.toString(), 1)) {
            indexer.parseWikis(Paths.get("data/demo/corpus"));
            try (QueryEngine engine = new QueryEngine(1, 4, indexer);
                 GameService game = new GameService(Paths.get("data/demo/questions.txt"), Paths.get("data/demo/easy-questions.txt"),
                         (category, clue) -> engine.ask(category + " " + clue, 10))) {
                String session = game.openSession(null);
                int number = 0;
                for (String[] question : Evaluation.readQuestions(Files.readAllBytes(Paths.get("data/demo/easy-questions.txt")))) {
                    jakarta.json.JsonObject round = game.start(session, "easy");
                    assertEquals(question[1], round.getString("clue"));
                    assertFalse(round.containsKey("acceptedAnswers"));
                    assertFalse(round.containsKey("computerAnswer"));
                    String id = round.getString("id");
                    GameServiceTest.ready(game, session, id);
                    String[] aliases = question[2].split("\\|");
                    jakarta.json.JsonObject result = game.submit(session, id, aliases[aliases.length - 1]);
                    assertEquals(100, result.getInt("playerPoints"));
                    int expected = Evaluation.accepts(question[2], result.getString("computerAnswer")) ? 100 : 0;
                    assertEquals(expected, result.getInt("computerPoints"));
                    assertEquals(++number * 100, result.getInt("playerTotal"));
                    assertFalse(result.isNull("evidence"));
                }
                assertEquals(8, number);
            }
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
            }
        }
    }

    @Test void demoBuildsAndQueriesEveryProcessingAndScoringCombination() throws Exception {
        Path root = Files.createTempDirectory("cluelab-demo-");
        try {
            for (int type = 1; type <= 3; type++) {
                try (Indexer indexer = new Indexer(root.toString(), type)) {
                    indexer.parseWikis(Paths.get("data/demo/corpus"));
                    for (int scorer = 1; scorer <= 4; scorer++) {
                        try (QueryEngine engine = new QueryEngine(type, scorer, indexer);
                             PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
                            Evaluation result = Evaluation.evaluate(engine, Paths.get("data/demo/questions.txt"), sink, true);
                            assertEquals(8, result.getTotal());
                            assertEquals(8, result.getFound());
                            QueryResponse response = engine.ask("Henry Sproatt gargoyles turrets", 10);
                            assertEquals("Burwash Hall", response.candidates.get(0).title);
                            assertTrue(response.candidates.get(0).evidence.contains("Construction began in 1911"));
                        }
                    }
                }
            }
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.delete(path);
            }
        }
    }
}
