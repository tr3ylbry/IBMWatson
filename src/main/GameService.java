package main;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;
import java.util.concurrent.*;

/** Server-owned games and rounds, optionally backed by a private durable store. */
public final class GameService implements AutoCloseable {
    @FunctionalInterface
    public interface Computer {
        QueryResponse answer(String category, String clue) throws Exception;
    }

    @FunctionalInterface
    public interface ScopedComputer {
        QueryResponse answer(PackCatalog.Scope scope, String category, String clue) throws Exception;
    }

    static final class GameError extends RuntimeException {
        final int status;
        GameError(int status, String message) { super(message); this.status = status; }
    }

    enum State { THINKING, READY, FINALIZED }
    static final int GAME_LENGTH = 5;
    private static final int MAX_ROUNDS = 64, MAX_GAMES = 12;

    static final class Question {
        final String category, clue, answers;
        Question(String[] row) { category = row[0]; clue = row[1]; answers = row[2]; }
    }

    static final class Round {
        String id = UUID.randomUUID().toString();
        final Question question;
        final int number;
        final String difficulty;
        final PackCatalog.Pack pack;
        final PackCatalog.Clue content;
        final Game game;
        final int clueNumber;
        State state = State.THINKING;
        QueryResponse.Candidate candidate;
        double responseTimeMs;
        String computerStatus = "unavailable";
        JsonObject result;
        Future<?> task;
        ScheduledFuture<?> timeout;
        Round(Question question, int number, String difficulty) {
            this(question, number, difficulty, null, null);
        }
        Round(Question question, int number, String difficulty, PackCatalog.Pack pack, PackCatalog.Clue content) {
            this(question, number, difficulty, pack, content, null);
        }
        Round(Question question, int number, String difficulty, PackCatalog.Pack pack, PackCatalog.Clue content, Game game) {
            this.question = question; this.number = number; this.difficulty = difficulty;
            this.pack = pack; this.content = content;
            this.game = game; this.clueNumber = game == null ? 0 : game.rounds.size() + 1;
        }
    }

    /** A pinned private selection. Future rounds do not acquire public IDs until opened. */
    static final class Game {
        String id = UUID.randomUUID().toString();
        final String requestId, difficulty;
        final boolean replay;
        final int number;
        final PackCatalog.Pack pack;
        final List<PackCatalog.Clue> clues;
        final List<Round> rounds = new ArrayList<>();
        int playerScore, computerScore, revision;
        JsonObject result;
        Game(String requestId, String difficulty, boolean replay, int number, PackCatalog.Pack pack, List<PackCatalog.Clue> clues) {
            this.requestId = requestId; this.difficulty = difficulty; this.replay = replay; this.number = number; this.pack = pack;
            this.clues = Collections.unmodifiableList(new ArrayList<>(clues));
        }
        Round current() { return rounds.get(rounds.size() - 1); }
    }

    static final class Session {
        final long created;
        final Map<String, Round> rounds = new LinkedHashMap<>();
        Round current;
        int playerTotal, computerTotal;
        final Map<String, Integer> nextQuestion = new HashMap<>();
        final Map<String, Cycle> cycles = new HashMap<>();
        final Map<String, Game> games = new LinkedHashMap<>();
        Game currentGame;
        Session(long created) { this.created = created; }
    }

    static final class Cycle {
        final PackCatalog.Pack pack;
        final List<PackCatalog.Clue> clues;
        final int number;
        int next;
        Cycle(PackCatalog.Pack pack, List<PackCatalog.Clue> clues, int number) {
            this.pack = pack;
            this.clues = new ArrayList<>(clues); this.number = number;
            Collections.shuffle(this.clues);
        }
    }

    private final Map<String, List<Question>> packs = new LinkedHashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Computer computer;
    private final PackCatalog catalog;
    private final ScopedComputer scopedComputer;
    private final Clock clock;
    private final long timeoutMs;
    private GameStore store;
    private boolean storageFailed, closed;
    private final ThreadPoolExecutor searches = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), daemonThreads("cluelab-search"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(daemonThreads("cluelab-timeout"));
    static final long SESSION_MS = TimeUnit.HOURS.toMillis(2);

    /** Attach before exposing the service or opening sessions. Constructors remain ephemeral for embedders. */
    synchronized GameService durable(GameStore store) throws IOException {
        if (this.store != null || !sessions.isEmpty()) throw new IllegalStateException("Attach storage before use");
        this.store = store;
        try {
            JsonObject saved = store.read();
            if (saved != null) {
                if (saved.getBoolean("catalogMode") != usesCatalog()) throw new IOException("Saved game mode differs from this server");
                sessions.putAll(GameState.read(saved));
            }
            // A crashed search has no recoverable task or new deadline. Never rerun it.
            sessions.entrySet().removeIf(e -> clock.millis() >= e.getValue().created + SESSION_MS);
            for (Session session : sessions.values()) for (Round round : session.rounds.values()) if (round.state == State.THINKING) {
                round.state = State.READY; round.computerStatus = "interrupted";
                if (round.game != null) round.game.revision++;
            }
            persist();
            return this;
        } catch (IOException | RuntimeException error) {
            close(); throw new IOException("Game recovery stopped; private saves were not reset: " + error.getMessage(), error);
        }
    }

    private void healthy() {
        if (storageFailed || closed) throw new GameError(503, "Private game storage is unavailable. Stop the player, check its saved files, then restart and reconnect. No unsaved scores can be read.");
    }
    private void persist() {
        healthy();
        if (store == null) return;
        try { store.write(GameState.write(sessions, usesCatalog())); }
        catch (IOException | RuntimeException error) {
            storageFailed = true; sessions.values().forEach(GameService::cancel);
            System.err.println("ClueLab game save failed (" + error.getClass().getSimpleName() + "); access stopped until restart.");
            throw new GameError(503, "The game could not be saved. Stop the player and restart after checking private storage, then reconnect to the committed result.");
        }
    }
    String ownerIdentity(String credential) { return GameStore.identity(credential == null ? "" : credential); }
    synchronized String authorizedIdentity(String credential) { session(credential); return ownerIdentity(credential); }
    String cookieName() { return store == null ? "cluelab" : "cluelab_game_" + store.target; }
    private String ownerKey(String credential) { return store == null ? credential : ownerIdentity(credential); }
    synchronized long remainingSeconds(String owner) { return Math.max(1, (session(owner).created + SESSION_MS - clock.millis()) / 1000); }

    public GameService(Path questions, Computer computer) throws IOException {
        this(questions, computer, Clock.systemUTC(), 15000);
    }

    public GameService(Path questions, Path easyQuestions, Computer computer) throws IOException {
        this(questions, computer);
        if (easyQuestions != null) packs.put("easy", load(easyQuestions));
    }

    GameService(Path path, Computer computer, Clock clock, long timeoutMs) throws IOException {
        packs.put("standard", load(path));
        this.computer = computer;
        this.catalog = null; this.scopedComputer = null;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
    }

    public GameService(PackCatalog catalog, ScopedComputer computer) {
        this(catalog, computer, Clock.systemUTC(), 15000);
    }

    GameService(PackCatalog catalog, ScopedComputer computer, Clock clock, long timeoutMs) {
        this.catalog = catalog; this.scopedComputer = computer; this.computer = null;
        this.clock = clock; this.timeoutMs = timeoutMs;
    }

    public boolean usesCatalog() { return catalog != null; }

    private static List<Question> load(Path path) throws IOException {
        List<Question> questions = new ArrayList<>();
        for (String[] row : Evaluation.readQuestions(Files.readAllBytes(path))) questions.add(new Question(row));
        return questions;
    }

    public JsonArray difficulties() {
        JsonArrayBuilder values = Json.createArrayBuilder();
        if (catalog != null) {
            catalog.difficulties.keySet().forEach(values::add);
            return values.build();
        }
        if (packs.containsKey("easy")) values.add("easy");
        values.add("standard");
        return values.build();
    }

    static ThreadFactory daemonThreads(String name) {
        return task -> { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; };
    }

    public synchronized String openSession(String existing) {
        healthy();
        if (store != null && existing != null && !existing.isEmpty()) {
            session(existing); return existing; // Never silently replace an unavailable recovery identity.
        }
        sessions.entrySet().removeIf(entry -> {
            boolean expired = clock.millis() - entry.getValue().created >= SESSION_MS;
            if (expired) cancel(entry.getValue());
            return expired;
        });
        if (sessions.containsKey(ownerKey(existing))) return existing;
        if (sessions.size() >= 32) throw new GameError(503, "Local session limit reached. Wait for absolute session expiry.");
        String id = UUID.randomUUID().toString();
        sessions.put(ownerKey(id), new Session(clock.millis()));
        persist();
        return id;
    }

    private Session session(String id) {
        healthy();
        Session session = sessions.get(ownerKey(id));
        if (session == null || clock.millis() - session.created >= SESSION_MS) {
            throw new GameError(410, "This game session is expired or unavailable. Old memory-only sessions cannot be restored. Explicitly start a new session; old IDs never transfer ownership.");
        }
        return session;
    }

    public synchronized JsonObject current(String owner) {
        Session session = session(owner);
        JsonObjectBuilder result = Json.createObjectBuilder().add("difficulties", difficulties());
        if (catalog != null) {
            result.add("catalog", catalogView(session)).add("gameLength", GAME_LENGTH)
                    .add("sessionTotals", Json.createObjectBuilder().add("player", session.playerTotal).add("computer", session.computerTotal));
            if (session.currentGame == null) result.addNull("game"); else result.add("game", gameView(session, session.currentGame));
            JsonArrayBuilder history = Json.createArrayBuilder();
            for (Game played : session.games.values()) if (played.result != null) history.add(gameBase(played));
            result.add("completedGames", history);
        }
        return session.current == null ? result.addNull("round").build()
                : result.add("round", view(session, session.current)).build();
    }

    private JsonObject catalogView(Session session) {
        JsonArrayBuilder topics = Json.createArrayBuilder();
        for (PackCatalog.Pack pack : catalog.topics.values()) {
            JsonArrayBuilder levels = Json.createArrayBuilder();
            for (Map.Entry<String, String> level : catalog.difficulties.entrySet()) {
                int count = pack.eligible(level.getKey()).size();
                Cycle cycle = session.cycles.get(cycleKey(pack, level.getKey()));
                int remaining = cycle == null ? count : count - cycle.next;
                levels.add(Json.createObjectBuilder().add("id", level.getKey()).add("label", level.getValue())
                        .add("eligible", count).add("remaining", remaining).add("exhausted", count > 0 && remaining == 0)
                        .add("cycle", cycle == null ? 1 : cycle.number));
            }
            topics.add(Json.createObjectBuilder().add("id", pack.topicId).add("label", pack.label)
                    .add("description", pack.description).add("language", pack.language)
                    .add("packId", pack.id).add("packVersion", pack.version).add("reviewStatus", pack.review.getString("status"))
                    .add("collectionId", pack.collection.scope.id).add("collectionVersion", pack.collection.scope.version)
                    .add("documents", pack.collection.sources.size()).add("difficulties", levels));
        }
        return Json.createObjectBuilder().add("id", catalog.id).add("version", catalog.version)
                .add("defaultTopic", catalog.defaultTopic).add("defaultDifficulty", catalog.defaultDifficulty)
                .add("topics", topics).build();
    }

    private static String cycleKey(PackCatalog.Pack pack, String difficulty) {
        return GameContent.key(pack) + "/" + difficulty;
    }

    public synchronized JsonObject startGame(String owner, String requestId, String topic, String difficulty, String version, boolean replay) {
        Session session = session(owner);
        if (catalog == null) throw new GameError(400, "Five-clue games require a catalog configuration.");
        requireUuid(requestId);
        // Keep start intentions with their games, even after completion and replay.
        for (Game previous : session.games.values()) if (previous.requestId.equals(requestId)) {
            if (!previous.pack.topicId.equals(topic) || !previous.difficulty.equals(difficulty)
                    || !previous.pack.version.equals(version) || previous.replay != replay) {
                throw new GameError(409, "This start request ID already belongs to another selection.");
            }
            return gameView(session, previous);
        }
        if (session.currentGame != null && session.currentGame.result == null) {
            throw new GameError(409, "Finish the active game before starting another. Reconnect to resume it.");
        }
        if (session.current != null && session.current.state != State.FINALIZED) {
            throw new GameError(409, "Finish the active single-clue round before starting a game.");
        }
        PackCatalog.Pack pack = catalog.topics.get(topic);
        if (pack == null || !catalog.difficulties.containsKey(difficulty)) throw new GameError(400, "Choose a listed topic and difficulty.");
        if (!pack.version.equals(version)) throw new GameError(409, "Pack version changed. Reload the catalog.");
        List<PackCatalog.Clue> eligible = pack.eligible(difficulty);
        if (eligible.size() < GAME_LENGTH) throw new GameError(409, "This selection has fewer than five eligible clues. Choose another topic or difficulty.");
        if (session.games.size() >= MAX_GAMES || session.rounds.size() + GAME_LENGTH > MAX_ROUNDS) {
            throw new GameError(409, "Local session capacity reached. Five free round slots are required; restart for a new session.");
        }
        String key = cycleKey(pack, difficulty);
        Cycle cycle = session.cycles.get(key);
        int remaining = cycle == null ? eligible.size() : cycle.clues.size() - cycle.next;
        if (remaining < GAME_LENGTH && !replay) {
            throw new GameError(409, remaining + " unused clues remain; a game requires five. Explicitly reset this selection to replay.");
        }
        if (remaining >= GAME_LENGTH && replay) throw new GameError(409, "Reset is only available when fewer than five unused clues remain.");
        if (cycle == null || replay) cycle = new Cycle(pack, eligible, cycle == null ? 1 : cycle.number + 1);
        Game match = new Game(requestId, difficulty, replay, session.games.size() + 1, pack,
                cycle.clues.subList(cycle.next, cycle.next + GAME_LENGTH));
        cycle.next += GAME_LENGTH;
        session.cycles.put(key, cycle);
        session.games.put(match.id, match); session.currentGame = match;
        openRound(session, match);
        return gameView(session, match);
    }

    synchronized JsonObject retryGame(String owner, String requestId) {
        Session session = session(owner);
        for (Game previous : session.games.values()) if (previous.requestId.equals(requestId)) return gameView(session, previous);
        return null;
    }

    private static void requireUuid(String value) {
        try {
            if (value != null && UUID.fromString(value).toString().equals(value)) return;
        } catch (IllegalArgumentException ignored) { /* Report one actionable input error. */ }
        throw new GameError(400, "requestId must be a canonical lowercase UUID for this start intention.");
    }

    private Game game(Session session, String id) {
        Game match = session.games.get(id);
        if (match == null) throw new GameError(404, "Game not found in this session.");
        return match;
    }

    private Round gameRound(Game match, String id) {
        for (Round round : match.rounds) if (round.id.equals(id)) return round;
        throw new GameError(404, "Round not found in this game.");
    }

    public synchronized JsonObject getGame(String owner, String id) {
        Session session = session(owner);
        return gameView(session, game(session, id));
    }

    public synchronized JsonObject answerGame(String owner, String id, String roundId, String answer) {
        Session session = session(owner); Game match = game(session, id);
        Round round = gameRound(match, roundId);
        submit(owner, round.id, answer);
        return gameView(session, match);
    }

    public synchronized JsonObject advanceGame(String owner, String id, String roundId) {
        Session session = session(owner); Game match = game(session, id);
        Round previous = gameRound(match, roundId);
        // A repeated advancement observes the current game without opening another clue.
        if (match.result != null || previous != match.current()) return gameView(session, match);
        if (previous.state != State.FINALIZED) throw new GameError(409, "Score this clue before advancing.");
        openRound(session, match);
        return gameView(session, match);
    }

    private void openRound(Session session, Game match) {
        PackCatalog.Clue clue = match.clues.get(match.rounds.size());
        Round round = new Round(new Question(new String[]{clue.category, clue.retrievalText(), clue.answers()}),
                session.rounds.size() + 1, match.difficulty, match.pack, clue, match);
        match.rounds.add(round); match.revision++;
        launch(session, round);
    }

    private JsonObjectBuilder gameBase(Game match) {
        int finalized = 0;
        for (Round round : match.rounds) if (round.state == State.FINALIZED) finalized++;
        return Json.createObjectBuilder().add("id", match.id).add("number", match.number).add("revision", match.revision)
                .add("state", finalized == GAME_LENGTH ? "COMPLETED" : "ACTIVE")
                .add("topicId", match.pack.topicId).add("topicLabel", match.pack.label).add("difficulty", match.difficulty)
                .add("packId", match.pack.id).add("packVersion", match.pack.version)
                .add("collectionId", match.pack.collection.scope.id).add("collectionVersion", match.pack.collection.scope.version)
                .add("length", GAME_LENGTH).add("clueNumber", match.rounds.size()).add("completedRounds", finalized)
                .add("playerScore", match.playerScore).add("computerScore", match.computerScore);
    }

    private JsonObject gameView(Session session, Game match) {
        return match.result != null ? match.result : gameBase(match).add("round", view(session, match.current())).build();
    }

    private void completeGame(Game match) {
        JsonArrayBuilder rounds = Json.createArrayBuilder();
        for (Round round : match.rounds) rounds.add(round.result);
        match.result = gameBase(match).addNull("round").add("rounds", rounds)
                .add("outcome", match.playerScore == match.computerScore ? "tie" : match.playerScore > match.computerScore ? "player" : "computer").build();
    }

    private void requireNoActiveGame(Session session) {
        if (session.currentGame != null && session.currentGame.result == null) {
            throw new GameError(409, "This session has an active five-clue game. Resume it instead of starting a standalone round.");
        }
    }

    public synchronized JsonObject start(String owner, String topic, String difficulty, String version, boolean replay) {
        Session session = session(owner);
        if (catalog == null) throw new GameError(400, "This server uses legacy questions.");
        requireNoActiveGame(session);
        PackCatalog.Pack pack = catalog.topics.get(topic);
        if (pack == null || !catalog.difficulties.containsKey(difficulty)) throw new GameError(400, "Choose a listed topic and difficulty.");
        if (!pack.version.equals(version)) throw new GameError(409, "Pack version changed. Reload the catalog.");
        List<PackCatalog.Clue> eligible = pack.eligible(difficulty);
        if (eligible.isEmpty()) throw new GameError(409, "No reviewed clues are available for this topic and difficulty.");
        if (session.current != null && session.current.state != State.FINALIZED) {
            Round active = session.current;
            if (active.pack == null || !GameContent.key(active.pack).equals(GameContent.key(pack)) || !active.difficulty.equals(difficulty)) {
                throw new GameError(409, "Finish the active round before changing its topic, difficulty, or pack.");
            }
            return view(session, active); // Recover a lost start response, including an explicit replay.
        }
        if (session.rounds.size() >= MAX_ROUNDS) throw new GameError(409, "This session has reached 64 rounds. Restart for a new game.");
        String key = cycleKey(pack, difficulty);
        Cycle cycle = session.cycles.get(key);
        boolean exhausted = cycle != null && cycle.next == cycle.clues.size();
        if (exhausted && !replay) throw new GameError(409, "This selection is exhausted. Choose Replay This Selection to begin a new cycle.");
        if (!exhausted && replay) throw new GameError(409, "Replay is only available after completing this selection's cycle.");
        if (cycle == null || exhausted) {
            cycle = new Cycle(pack, eligible, cycle == null ? 1 : cycle.number + 1);
            session.cycles.put(key, cycle);
        }
        PackCatalog.Clue clue = cycle.clues.get(cycle.next++);
        Round round = new Round(new Question(new String[]{clue.category, clue.retrievalText(), clue.answers()}),
                session.rounds.size() + 1, difficulty, pack, clue);
        session.currentGame = null;
        launch(session, round);
        return view(session, round);
    }

    public synchronized JsonObject start(String owner) {
        return start(owner, "standard");
    }

    public synchronized JsonObject start(String owner, String difficulty) {
        if (catalog != null) throw new GameError(400, "Topic and pack version are required.");
        Session session = session(owner);
        if (!packs.containsKey(difficulty)) throw new GameError(400, "Choose an available difficulty.");
        if (session.current != null && session.current.state != State.FINALIZED) return view(session, session.current);
        if (session.rounds.size() >= MAX_ROUNDS) throw new GameError(409, "This session has reached 64 rounds. Restart for a new game.");
        List<Question> questions = packs.get(difficulty);
        int next = session.nextQuestion.getOrDefault(difficulty, 0);
        Round round = new Round(questions.get(next % questions.size()), session.rounds.size() + 1, difficulty);
        session.nextQuestion.put(difficulty, next + 1);
        launch(session, round);
        return view(session, round);
    }

    private void launch(Session session, Round round) {
        session.rounds.put(round.id, round);
        session.current = round;
        persist(); // Reservation/THINKING is durable before any task or response can escape.
        try {
            round.task = searches.submit(() -> compute(round));
            round.timeout = timer.schedule(() -> finishSearch(round, null, "timeout"), timeoutMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException busy) {
            finishSearch(round, null, "unavailable");
        }
    }

    private void compute(Round round) {
        try {
            // This boundary deliberately has no answer key, player guess, or round object.
            QueryResponse response = round.pack == null ? computer.answer(round.question.category, round.question.clue)
                    : scopedComputer.answer(round.pack.collection.scope, round.question.category, round.question.clue);
            if (response == null || !Double.isFinite(response.responseTimeMs) || response.responseTimeMs < 0) {
                throw new IOException("Invalid engine timing.");
            }
            if (!response.candidates.isEmpty()) {
                QueryResponse.Candidate candidate = response.candidates.get(0);
                if (candidate == null || candidate.title == null || candidate.title.trim().isEmpty()
                        || !Double.isFinite(candidate.score) || candidate.evidence == null
                        || candidate.evidenceKind == null || candidate.source == null) {
                    throw new IOException("Invalid engine candidate.");
                }
                if (round.pack != null && round.pack.collection.title(candidate.title) == null) {
                    throw new IOException("Candidate outside the allowed source collection.");
                }
            }
            finishSearch(round, response, response.candidates.isEmpty() ? "no-match" : "answered");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("ClueLab retrieval failed (" + failure.getClass().getSimpleName() + ").");
            finishSearch(round, null, "unavailable");
        }
    }

    private synchronized void finishSearch(Round round, QueryResponse response, String status) {
        if (closed || storageFailed) return;
        if (round.state != State.THINKING) return; // Late/timeout results cannot change a committed answer.
        round.computerStatus = status;
        if (response != null) {
            round.responseTimeMs = response.responseTimeMs;
            if (!response.candidates.isEmpty()) round.candidate = response.candidates.get(0);
        }
        round.state = State.READY;
        if (round.game != null) round.game.revision++;
        persist(); // A READY view always represents a durable, immutable commitment.
        if (round.timeout != null) round.timeout.cancel(false);
        if ("timeout".equals(status) && round.task != null) {
            round.task.cancel(true);
            searches.purge();
        }
    }

    private Round round(Session session, String id) {
        Round round = session.rounds.get(id);
        if (round == null) throw new GameError(404, "Round not found in this session.");
        return round;
    }

    public synchronized JsonObject get(String owner, String id) {
        Session session = session(owner);
        return view(session, round(session, id));
    }

    public synchronized JsonObject submit(String owner, String id, String answer) {
        Session session = session(owner);
        Round round = round(session, id);
        if (round.state == State.FINALIZED) return round.result;
        if (round.state != State.READY) throw new GameError(409, "The computer is still searching. Please wait.");
        if (answer == null || answer.trim().isEmpty() || answer.length() > 200 || answer.chars().anyMatch(Character::isISOControl)) {
            throw new GameError(400, "Enter an answer of 1-200 characters, without control characters.");
        }
        String guess = answer.trim();
        String computerAnswer = round.candidate == null ? "" : round.candidate.title;
        int playerPoints = Evaluation.accepts(round.question.answers, guess) ? 100 : 0;
        int computerPoints = Evaluation.accepts(round.question.answers, computerAnswer) ? 100 : 0;
        JsonArrayBuilder accepted = Json.createArrayBuilder();
        for (String alias : round.question.answers.split("\\|")) accepted.add(alias.trim());
        JsonObjectBuilder result = base(session, round)
                .add("state", State.FINALIZED.name())
                .add("playerTotal", session.playerTotal + playerPoints).add("computerTotal", session.computerTotal + computerPoints)
                .add("playerAnswer", guess).add("computerAnswer", computerAnswer)
                .add("playerPoints", playerPoints).add("computerPoints", computerPoints)
                .add("acceptedAnswers", accepted).add("computerStatus", round.computerStatus)
                .add("responseTimeMs", round.responseTimeMs)
                .add("outcome", playerPoints == computerPoints ? "tie" : playerPoints > computerPoints ? "player" : "computer");
        if (round.candidate == null) result.addNull("evidence");
        else if (round.pack != null) {
            PackCatalog.Source source = round.pack.collection.title(round.candidate.title);
            result.add("evidence", source.evidence(round.candidate.evidence, "opponent-retrieved"));
        }
        else {
            QueryResponse.Candidate candidate = round.candidate;
            String encoded = urlEncode(candidate.title.replace(' ', '_'));
            result.add("evidence", Json.createObjectBuilder().add("text", candidate.evidence)
                    .add("kind", candidate.evidenceKind).add("source", candidate.source)
                    .add("title", candidate.title).add("score", candidate.score)
                    .add("articleUrl", "https://en.wikipedia.org/wiki/" + encoded)
                    .add("historyUrl", "https://en.wikipedia.org/w/index.php?title=" + encoded + "&action=history")
                    .add("attribution", "Adapted from Wikipedia contributors, historical coursework sample. CC BY-SA 3.0. Exact revision unavailable."));
        }
        if (round.content != null) {
            result.add("clueId", round.content.id).add("curatedEvidence",
                    round.content.supportingSource.evidence(round.content.supportingPassage(), "curated-answer-support"));
        }
        if (round.game != null) result.add("gamePlayerTotal", round.game.playerScore + playerPoints)
                .add("gameComputerTotal", round.game.computerScore + computerPoints);
        // Build the immutable receipt before changing state or awarding any points.
        round.result = result.build();
        session.playerTotal += playerPoints;
        session.computerTotal += computerPoints;
        round.state = State.FINALIZED;
        if (round.game != null) {
            Game match = round.game;
            match.playerScore += playerPoints; match.computerScore += computerPoints; match.revision++;
            if (match.rounds.size() == GAME_LENGTH) completeGame(match);
        }
        persist(); // Scoring and the fifth-clue summary are one atomic snapshot.
        return round.result;
    }

    private static String urlEncode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }

    private JsonObjectBuilder base(Session session, Round round) {
        JsonObjectBuilder out = Json.createObjectBuilder().add("id", round.id).add("number", round.number)
                .add("difficulty", round.difficulty)
                .add("state", round.state.name()).add("category", round.question.category).add("clue", round.question.clue)
                .add("playerTotal", session.playerTotal).add("computerTotal", session.computerTotal);
        if (round.pack != null) out.add("topicId", round.pack.topicId).add("topicLabel", round.pack.label)
                .add("packId", round.pack.id).add("packVersion", round.pack.version)
                .add("collectionId", round.pack.collection.scope.id).add("collectionVersion", round.pack.collection.scope.version)
                .add("clue", round.content.text).add("hint", round.content.hint);
        if (round.game != null) out.add("gameId", round.game.id).add("clueNumber", round.clueNumber)
                .add("gamePlayerTotal", round.game.playerScore).add("gameComputerTotal", round.game.computerScore);
        return out;
    }

    private JsonObject view(Session session, Round round) {
        // Explicit allowlist: never serialize private state or the engine's index metadata.
        return round.state == State.FINALIZED ? round.result : base(session, round).build();
    }

    private static void cancel(Session session) {
        for (Round round : session.rounds.values()) {
            if (round.task != null) round.task.cancel(true);
            if (round.timeout != null) round.timeout.cancel(false);
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        sessions.values().forEach(GameService::cancel);
        sessions.clear();
        searches.shutdownNow();
        timer.shutdownNow();
        if (store != null) try { store.close(); } catch (IOException error) { System.err.println("Private game lock could not be closed cleanly."); }
    }
}
