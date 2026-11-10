package main;

import jakarta.json.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Test-classpath-only UI failure harness. Synthetic API data, never a factual capture or playable pack. */
public final class TopicBuilderBrowserFixture {
    public static void main(String[] args) throws Exception {
        Path root = Path.of("drafts/local/topic-builder-browser-fixture"), config = root.resolve("isolated/game.json");
        TopicBuilder.privateWorkspace(root);
        if (!Files.exists(config)) PackActivation.sandbox(Path.of("data/game.json"), config.getParent(), Path.of("indexes/topic-builder-browser-fixture"), 8789);
        Map<String, TopicBuilder.Artifact> seeds = TopicBuilder.examples();
        TopicBuilder builder = new TopicBuilder(root, Path.of("drafts"), Map.of("normal", config, "isolated", config), seeds,
                () -> new WikiApi((uri, agent, budget) -> {
                    JsonObject json;
                    if (uri.getRawQuery().contains("list=search")) {
                        JsonArrayBuilder rows = Json.createArrayBuilder();
                        for (int id = 1; id <= 3; id++) rows.add(Json.createObjectBuilder().add("pageid", id).add("ns", 0).add("title", title(id)));
                        json = Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("search", rows)).build();
                    } else {
                        int id = Integer.parseInt(Arrays.stream(uri.getRawQuery().split("&")).filter(s -> s.startsWith("pageids=")).findFirst().orElseThrow().substring(8));
                        if (id == 2) throw new IOException("Deliberate unavailable source in browser fixture");
                        if (id == 3) for (;;) budget.pause(100);
                        JsonObject page = Json.createObjectBuilder().add("pageid", id).add("ns", 0).add("title", title(id))
                                .add("extract", "Synthetic browser fixture, not factual source content. " + SourceIntakeTest.TEXT)
                                .build();
                        json = Json.createObjectBuilder().add("query", Json.createObjectBuilder().add("pages", Json.createArrayBuilder().add(page))).build();
                    }
                    return new WikiApi.Response(200, Map.of(), IntakeFiles.json(json));
                }, root.resolve("cache"), Clock.systemUTC(), SourceIntakeTest.AGENT, 0));
        TopicBuilderServer server = new TopicBuilderServer(8768, builder, "local-browser-fixture-only");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); } catch (IOException ignored) {} }));
        server.start(); System.out.println("SYNTHETIC TEST FIXTURE ONLY: " + server.url()); new CountDownLatch(1).await();
    }
    private static String title(int id) { return switch (id) { case 1 -> "Fixture <img src=x onerror=alert(1)>"; case 2 -> "Unavailable Fixture"; default -> "Slow Fixture"; }; }
}
