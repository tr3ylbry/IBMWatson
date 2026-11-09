package main;

import jakarta.json.*;
import java.io.IOException;
import java.util.*;
import static main.GameService.*;
import static main.PackCatalog.require;

/** Explicit storage DTO codec, deliberately separate from the public response allowlists. */
final class GameState {
    private GameState() {}

    static JsonObject write(Map<String, Session> sessions, boolean catalogMode) {
        Map<String, PackCatalog.Pack> packs = new LinkedHashMap<>();
        for (Session s : sessions.values()) {
            for (Game g : s.games.values()) packs.put(GameContent.key(g.pack), g.pack);
            for (Round r : s.rounds.values()) if (r.pack != null) packs.put(GameContent.key(r.pack), r.pack);
            for (Cycle c : s.cycles.values()) packs.put(GameContent.key(c.pack), c.pack);
        }
        for (PackCatalog.Pack pack : packs.values()) if (pack.clues.size() > 4096 || pack.collection.sources.size() > 4096)
            throw new IllegalStateException("Saved content exceeds 4096 clues/sources");
        Map<String, PackCatalog.Collection> collections = new LinkedHashMap<>();
        JsonObjectBuilder packRows = Json.createObjectBuilder(), collectionRows = Json.createObjectBuilder();
        if (sessions.size() > 32 || packs.size() > 128) throw new IllegalStateException("Game storage session/content-version capacity exceeded");
        packs.forEach((key, pack) -> { packRows.add(key, GameContent.write(pack)); collections.put(GameContent.key(pack.collection), pack.collection); });
        collections.forEach((key, collection) -> collectionRows.add(key, GameContent.write(collection)));
        JsonObjectBuilder owners = Json.createObjectBuilder();
        sessions.forEach((hash, s) -> {
            JsonArrayBuilder games = Json.createArrayBuilder(), rounds = Json.createArrayBuilder();
            for (Game g : s.games.values()) {
                JsonObjectBuilder row = Json.createObjectBuilder().add("id", g.id).add("requestId", g.requestId)
                        .add("difficulty", g.difficulty).add("replay", g.replay).add("number", g.number).add("pack", GameContent.key(g.pack))
                        .add("clues", clueIds(g.clues)).add("playerScore", g.playerScore).add("computerScore", g.computerScore)
                        .add("revision", g.revision);
                nullable(row, "result", g.result); games.add(row);
            }
            for (Round r : s.rounds.values()) {
                JsonObjectBuilder row = Json.createObjectBuilder().add("id", r.id).add("number", r.number).add("difficulty", r.difficulty)
                        .add("question", Json.createArrayBuilder().add(r.question.category).add(r.question.clue).add(r.question.answers))
                        .add("pack", r.pack == null ? "" : GameContent.key(r.pack)).add("content", r.content == null ? "" : r.content.id)
                        .add("game", r.game == null ? "" : r.game.id).add("state", r.state.name()).add("computerStatus", r.computerStatus)
                        .add("responseTimeMs", r.responseTimeMs);
                if (r.candidate == null) row.addNull("candidate"); else {
                    QueryResponse.Candidate c = r.candidate;
                    row.add("candidate", Json.createObjectBuilder().add("rank", c.rank).add("title", c.title).add("score", c.score)
                            .add("evidence", c.evidence).add("evidenceKind", c.evidenceKind).add("source", c.source));
                }
                nullable(row, "result", r.result); rounds.add(row);
            }
            JsonObjectBuilder cycles = Json.createObjectBuilder(), next = Json.createObjectBuilder();
            s.cycles.forEach((key, c) -> cycles.add(key, Json.createObjectBuilder().add("pack", GameContent.key(c.pack))
                    .add("number", c.number).add("next", c.next).add("clues", clueIds(c.clues))));
            s.nextQuestion.forEach(next::add);
            owners.add(hash, Json.createObjectBuilder().add("created", s.created).add("expiresAt", s.created + SESSION_MS)
                    .add("playerTotal", s.playerTotal).add("computerTotal", s.computerTotal).add("nextQuestion", next).add("cycles", cycles)
                    .add("games", games).add("rounds", rounds).add("current", s.current == null ? "" : s.current.id)
                    .add("currentGame", s.currentGame == null ? "" : s.currentGame.id));
        });
        return Json.createObjectBuilder().add("catalogMode", catalogMode).add("collections", collectionRows).add("packs", packRows)
                .add("sessions", owners).build();
    }
    private static JsonArray clueIds(List<PackCatalog.Clue> clues) {
        JsonArrayBuilder ids = Json.createArrayBuilder(); clues.forEach(c -> ids.add(c.id)); return ids.build();
    }
    private static void nullable(JsonObjectBuilder row, String key, JsonObject object) {
        if (object == null) row.addNull(key); else row.add(key, object);
    }

    static Map<String, Session> read(JsonObject root) throws IOException {
        try { return decode(root); }
        catch (IOException error) { throw error; }
        catch (RuntimeException error) { throw new IOException("Malformed private game state; recovery stopped", error); }
    }
    private static Map<String, Session> decode(JsonObject root) throws IOException {
        PackCatalog.keys(root, "game state", "catalogMode collections packs sessions");
        Map<String, PackCatalog.Collection> collections = new LinkedHashMap<>();
        require(root.getJsonObject("collections").size() <= 128 && root.getJsonObject("packs").size() <= 128, "Too many saved content versions (maximum 128)");
        for (var entry : root.getJsonObject("collections").entrySet()) {
            PackCatalog.Collection collection = GameContent.collection((JsonObject) entry.getValue());
            require(entry.getKey().equals(GameContent.key(collection)), "Saved collection identity mismatch"); collections.put(entry.getKey(), collection);
        }
        Map<String, PackCatalog.Pack> packs = new LinkedHashMap<>();
        for (var entry : root.getJsonObject("packs").entrySet()) {
            PackCatalog.Pack pack = GameContent.pack((JsonObject) entry.getValue(), collections);
            require(entry.getKey().equals(GameContent.key(pack)), "Saved pack identity mismatch"); packs.put(entry.getKey(), pack);
        }
        Map<String, Session> sessions = new LinkedHashMap<>();
        require(root.getJsonObject("sessions").size() <= 32, "Saved session limit exceeds 32");
        for (var entry : root.getJsonObject("sessions").entrySet()) {
            require(entry.getKey().matches("[a-f0-9]{64}"), "Saved owner must be a credential hash, not a raw cookie");
            JsonObject row = (JsonObject) entry.getValue();
            PackCatalog.keys(row, "saved session", "created expiresAt playerTotal computerTotal nextQuestion cycles games rounds current currentGame");
            long created = row.getJsonNumber("created").longValueExact();
            require(created >= 0 && created <= Long.MAX_VALUE - SESSION_MS && row.getJsonNumber("expiresAt").longValueExact() == created + SESSION_MS,
                    "Invalid absolute game-session expiry");
            Session session = new Session(created);
            require(row.getJsonArray("games").size() <= 12 && row.getJsonArray("rounds").size() <= 64, "Saved game/round count exceeds 12/64");
            Set<String> intentions = new HashSet<>();
            for (JsonValue value : row.getJsonArray("games")) {
                JsonObject g = (JsonObject) value;
                PackCatalog.keys(g, "saved game", "id requestId difficulty replay number pack clues playerScore computerScore revision result");
                PackCatalog.Pack pack = pack(packs, g);
                List<PackCatalog.Clue> clues = clues(g.getJsonArray("clues"), pack);
                require(clues.size() == GAME_LENGTH && clues.stream().allMatch(c -> c.difficulty.equals(g.getString("difficulty"))), "Saved game selection is inconsistent");
                Game game = new Game(uuid(g, "requestId"), g.getString("difficulty"), g.getBoolean("replay"), g.getInt("number"), pack, clues);
                game.id = uuid(g, "id"); game.playerScore = points(g, "playerScore", 500); game.computerScore = points(g, "computerScore", 500);
                game.revision = g.getInt("revision"); require(game.revision > 0 && game.revision <= 15, "Invalid saved game revision");
                game.result = optional(g, "result");
                require(game.number == session.games.size() + 1 && intentions.add(game.requestId) && session.games.put(game.id, game) == null, "Duplicate or unordered saved game/intention");
            }
            int player = 0, computer = 0;
            for (JsonValue value : row.getJsonArray("rounds")) {
                JsonObject r = (JsonObject) value;
                PackCatalog.keys(r, "saved round", "id number difficulty question pack content game state computerStatus responseTimeMs candidate result");
                JsonArray q = r.getJsonArray("question"); require(q.size() == 3, "Malformed saved question");
                for (int i = 0; i < 3; i++) require(!q.getString(i).isBlank() && q.getString(i).length() <= (i == 2 ? 4 * 1024 * 1024 : 4096), "Malformed saved question text");
                PackCatalog.Pack pack = r.getString("pack").isEmpty() ? null : pack(packs, r);
                PackCatalog.Clue clue = pack == null ? null : clue(pack, r.getString("content"));
                Game game = r.getString("game").isEmpty() ? null : session.games.get(r.getString("game"));
                require(r.getString("game").isEmpty() || game != null, "Missing saved game reference");
                Round round = new Round(new Question(new String[]{q.getString(0), q.getString(1), q.getString(2)}), r.getInt("number"), r.getString("difficulty"), pack, clue, game);
                round.id = uuid(r, "id"); round.state = State.valueOf(r.getString("state")); round.computerStatus = r.getString("computerStatus");
                require(Set.of("unavailable", "answered", "no-match", "timeout", "interrupted").contains(round.computerStatus), "Invalid saved computer status");
                round.responseTimeMs = r.getJsonNumber("responseTimeMs").doubleValue();
                require(Double.isFinite(round.responseTimeMs) && round.responseTimeMs >= 0, "Invalid saved search timing");
                if (!r.isNull("candidate")) {
                    JsonObject c = r.getJsonObject("candidate");
                    round.candidate = new QueryResponse.Candidate(c.getInt("rank"), c.getString("title"), c.getJsonNumber("score").doubleValue(), c.getString("evidence"), c.getString("evidenceKind"), c.getString("source"));
                    require(round.candidate.rank == 1 && Double.isFinite(round.candidate.score) && !round.candidate.title.isBlank()
                            && (pack == null || pack.collection.title(round.candidate.title) != null), "Saved candidate is invalid or outside its scope");
                }
                require((round.candidate != null) == round.computerStatus.equals("answered"), "Saved search commitment is inconsistent");
                require(round.state != State.THINKING || round.candidate == null, "THINKING round cannot have a committed candidate");
                round.result = optional(r, "result");
                require((round.state == State.FINALIZED) == (round.result != null), "Missing or premature saved receipt");
                if (clue != null) require(clue.category.equals(round.question.category) && clue.retrievalText().equals(round.question.clue)
                        && clue.answers().equals(round.question.answers) && clue.difficulty.equals(round.difficulty), "Saved round differs from its content pin");
                if (game != null) {
                    require(game.pack == pack && game.rounds.size() < GAME_LENGTH && game.clues.get(game.rounds.size()) == clue
                            && (game.rounds.isEmpty() || game.current().state == State.FINALIZED), "Saved game has inconsistent round order");
                    game.rounds.add(round);
                }
                require(round.number == session.rounds.size() + 1 && session.rounds.put(round.id, round) == null, "Duplicate or unordered saved round");
                if (round.result != null) {
                    JsonObject result = round.result;
                    require(result.getString("id").equals(round.id) && result.getString("state").equals("FINALIZED"), "Saved receipt identity mismatch");
                    int p = points(result, "playerPoints", 100), c = points(result, "computerPoints", 100);
                    require(p == (Evaluation.accepts(round.question.answers, result.getString("playerAnswer")) ? 100 : 0)
                            && c == (Evaluation.accepts(round.question.answers, round.candidate == null ? "" : round.candidate.title) ? 100 : 0), "Saved awards disagree with pinned grading");
                    player += p; computer += c;
                    require(result.getInt("playerTotal") == player && result.getInt("computerTotal") == computer, "Saved running totals disagree with receipts");
                }
            }
            session.playerTotal = points(row, "playerTotal", 6400); session.computerTotal = points(row, "computerTotal", 6400);
            require(player == session.playerTotal && computer == session.computerTotal, "Saved session scores disagree with receipts");
            for (Game game : session.games.values()) {
                require(!game.rounds.isEmpty(), "Saved game has no opened clue");
                int p = 0, c = 0; JsonArrayBuilder receipts = Json.createArrayBuilder();
                for (Round round : game.rounds) if (round.result != null) { p += round.result.getInt("playerPoints"); c += round.result.getInt("computerPoints"); receipts.add(round.result); }
                require(p == game.playerScore && c == game.computerScore, "Saved game scores disagree with receipts");
                boolean completed = game.rounds.size() == GAME_LENGTH && game.current().state == State.FINALIZED;
                require(completed == (game.result != null), "Missing or premature completed-game receipt");
                if (completed) require(game.result.getString("id").equals(game.id) && game.result.getJsonArray("rounds").equals(receipts.build())
                        && game.result.getInt("playerScore") == p && game.result.getInt("computerScore") == c, "Saved completed receipt disagrees with rounds");
            }
            require(row.getJsonObject("cycles").size() <= 128, "Saved cycle limit exceeds 128");
            for (var cycleEntry : row.getJsonObject("cycles").entrySet()) {
                JsonObject c = (JsonObject) cycleEntry.getValue(); PackCatalog.Pack pack = pack(packs, c);
                List<PackCatalog.Clue> clues = clues(c.getJsonArray("clues"), pack);
                require(!clues.isEmpty(), "Empty saved cycle"); String difficulty = clues.get(0).difficulty;
                require(new HashSet<>(pack.eligible(difficulty)).equals(new HashSet<>(clues)), "Saved cycle does not match eligible content");
                require(cycleEntry.getKey().equals(GameContent.key(pack) + "/" + difficulty), "Saved cycle identity mismatch");
                Cycle cycle = new Cycle(pack, clues, c.getInt("number")); cycle.clues.clear(); cycle.clues.addAll(clues); // Do not reshuffle on recovery.
                cycle.next = c.getInt("next"); require(cycle.number > 0 && cycle.next >= 0 && cycle.next <= clues.size(), "Invalid saved cycle cursor");
                session.cycles.put(cycleEntry.getKey(), cycle);
            }
            for (var next : row.getJsonObject("nextQuestion").entrySet()) {
                int index = ((JsonNumber) next.getValue()).intValueExact(); require(index >= 0 && index <= 64, "Invalid saved legacy cursor"); session.nextQuestion.put(next.getKey(), index);
            }
            session.current = row.getString("current").isEmpty() ? null : session.rounds.get(row.getString("current"));
            session.currentGame = row.getString("currentGame").isEmpty() ? null : session.games.get(row.getString("currentGame"));
            require(session.rounds.isEmpty() == (session.current == null) && (session.current == null || session.current.number == session.rounds.size()), "Invalid saved current-round pointer");
            require(row.getString("currentGame").isEmpty() || session.currentGame != null && session.currentGame.current() == session.current, "Invalid saved current-game pointer");
            require(session.games.values().stream().filter(g -> g.result == null).allMatch(g -> g == session.currentGame), "Saved active game is not current");
            require(session.rounds.values().stream().filter(r -> r.state != State.FINALIZED).allMatch(r -> r == session.current), "Saved unfinished round is not current");
            sessions.put(entry.getKey(), session);
        }
        return sessions;
    }
    private static PackCatalog.Pack pack(Map<String, PackCatalog.Pack> packs, JsonObject row) throws IOException {
        PackCatalog.Pack pack = packs.get(row.getString("pack")); require(pack != null, "Missing saved pack pin"); return pack;
    }
    private static PackCatalog.Clue clue(PackCatalog.Pack pack, String id) throws IOException {
        for (PackCatalog.Clue clue : pack.clues) if (clue.id.equals(id)) return clue;
        throw new IOException("Missing saved clue pin: " + id);
    }
    private static List<PackCatalog.Clue> clues(JsonArray rows, PackCatalog.Pack pack) throws IOException {
        List<PackCatalog.Clue> clues = new ArrayList<>(); Set<String> ids = new HashSet<>();
        require(rows.size() <= 4096, "Saved selection too large");
        for (JsonValue value : rows) { String id = ((JsonString)value).getString(); require(ids.add(id), "Duplicate saved clue in selection"); clues.add(clue(pack, id)); }
        return clues;
    }
    private static String uuid(JsonObject row, String field) throws IOException {
        String id = row.getString(field); require(TopicBuilder.uuid(id), "Invalid saved " + field); return id;
    }
    private static int points(JsonObject row, String field, int maximum) throws IOException {
        int points = row.getJsonNumber(field).intValueExact(); require(points >= 0 && points <= maximum && points % 100 == 0, "Invalid saved " + field); return points;
    }
    private static JsonObject optional(JsonObject row, String key) { return row.isNull(key) ? null : row.getJsonObject(key); }
}
