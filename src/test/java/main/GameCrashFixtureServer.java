package main;

import jakarta.json.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Test classpath only: deterministic crash barriers, never a production mode or fixture opponent. */
public final class GameCrashFixtureServer {
    public static void main(String[] args) throws Exception {
        GameConfig config = GameConfig.load(Path.of(args[0])); Path root = Path.of(args[1]);
        int port = Integer.parseInt(args[2]); String computer = args[3], operation = args[4], phase = args[5];
        String target = PlayerReadiness.targetKey(config.configPath); AtomicBoolean armed = new AtomicBoolean();
        GameStore store = new GameStore(root.resolve("games"), target, point -> {
            if (!armed.get() || !point.equals(phase)) return;
            JsonObject state = GameStoreSnapshot.read(root.resolve("games").resolve(target).resolve(point.equals("after-replace") ? "state.json" : "pending.json"));
            if (matches(state, operation)) {
                System.out.println("PAUSED " + operation + " " + phase); System.out.flush();
                try { new CountDownLatch(1).await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.InterruptedIOException("Crash barrier interrupted"); }
            }
        });
        CollectionIndex indexes = new CollectionIndex(config);
        GameService.ScopedComputer engine = (scope, category, clue) -> {
            if (computer.equals("thinking")) { System.out.println("SEARCH_BLOCKED"); System.out.flush(); new CountDownLatch(1).await(); }
            if (computer.equals("failure")) throw new java.io.IOException("Test-only unavailable engine");
            return indexes.ask(scope, category, clue);
        };
        GameService game = new GameService(config.catalog, engine).durable(store); armed.set(true);
        try (game; GameServer server = new GameServer(port, game, config, new TopicRequests(root.resolve("requests")))) {
            Runtime.getRuntime().addShutdownHook(new Thread(server::close)); server.start();
            System.out.println("READY " + server.url() + " PID " + ProcessHandle.current().pid()); System.out.flush();
            new CountDownLatch(1).await();
        }
    }
    private static boolean matches(JsonObject state, String operation) {
        for (JsonValue value : state.getJsonObject("sessions").values()) {
            JsonObject session = (JsonObject)value; JsonArray games = session.getJsonArray("games"), rounds = session.getJsonArray("rounds");
            if (games.isEmpty()) continue;
            switch (operation) {
                case "start": if (games.size() == 1 && rounds.size() == 1) return true; break;
                case "score": if (rounds.size() == 1 && rounds.getJsonObject(0).getString("state").equals("FINALIZED")) return true; break;
                case "advance": if (rounds.size() == 2) return true; break;
                case "complete": if (games.size() == 1 && !games.getJsonObject(0).isNull("result")) return true; break;
                case "replay": if (games.size() == 2) return true; break;
                default: break;
            }
        }
        return false;
    }
    private static final class GameStoreSnapshot {
        static JsonObject read(Path path) throws java.io.IOException {
            return PackCatalog.parse(IntakeFiles.utf8(IntakeFiles.read(path, GameStore.MAX_BYTES)), "test crash barrier").getJsonObject("state");
        }
    }
}
