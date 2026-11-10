package main;

import jakarta.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static main.PackCatalog.*;

/** Offline/admin entry point; no route from GameServer and no generation/retrieval coupling. */
public final class SourceIntake {
    private SourceIntake() {}
    public static void main(String[] args) throws Exception {
        List<String> a = new ArrayList<>(List.of(args)); Path cancel = null;
        if (!a.isEmpty() && a.get(a.size() - 1).startsWith("--cancel=")) cancel = Path.of(a.remove(a.size() - 1).substring(9));
        WikiApi.Budget budget = new WikiApi.Budget(cancel);
        require(!a.isEmpty(), usage());
        switch (a.get(0)) {
            case "preview": {
                require(a.size() == 3 || a.size() == 4, usage());
                WikiApi api = api(); Path output = admin(a.get(2));
                JsonObject preview = api.preview(a.get(1), a.size() == 4 ? Integer.parseInt(a.get(3)) : 8, budget);
                IntakeFiles.publish(output, Map.of("preview.json", IntakeFiles.json(preview)), budget);
                System.out.println(preview); break;
            }
            case "select": {
                require(a.size() == 5 && a.get(4).equals("--accept-cc-by-sa"), usage());
                Path preview = admin(a.get(1)).resolve("preview.json"), output = admin(a.get(2));
                Set<Integer> ids = new LinkedHashSet<>();
                for (String id : a.get(3).split(",", -1)) require(ids.add(Integer.parseInt(id)), "Duplicate selected page ID");
                JsonObject selection = select(IntakeFiles.read(preview), ids);
                IntakeFiles.publish(output, Map.of("selection.json", IntakeFiles.json(selection)), budget);
                System.out.println(selection); break;
            }
            case "capture": {
                require(a.size() == 3, usage());
                JsonObject selection = IntakeFiles.read(admin(a.get(1)).resolve("selection.json"));
                JsonObject capture = capture(selection, api(), budget);
                IntakeFiles.publish(admin(a.get(2)), Map.of("capture.json", IntakeFiles.json(capture)), budget);
                System.out.println(captureReport(capture));
                require(array(capture, "failures").isEmpty(), "Partial capture saved; inspect failures and select replacements explicitly in a new manifest/output"); break;
            }
            case "export": {
                require(a.size() == 4, usage());
                JsonObject capture = IntakeFiles.read(admin(a.get(1)).resolve("capture.json"));
                Path proposals = admin(a.get(2)), output = admin(a.get(3));
                DraftExporter.exportWorkspace(capture, IntakeFiles.read(proposals), output, budget, Path.of("drafts"), Path.of("data/packs/catalog.json"));
                System.out.println(PackTool.validation(PackCatalog.loadDraft(output.resolve("catalog.json")))); break;
            }
            case "validate": {
                require(a.size() == 2, usage());
                System.out.println(PackTool.validation(DraftExporter.validate(admin(a.get(1))))); break;
            }
            case "index": {
                require(a.size() == 2, usage()); PackCatalog catalog = DraftExporter.validate(admin(a.get(1)));
                new CollectionIndex(catalog.collections, Path.of("indexes/source-intake"), 1, 4).build(false);
                System.out.println("Built/verified source-only draft index; zero playable/evaluable clues. No proposal/support/review data indexed."); break;
            }
            default: throw new IOException(usage());
        }
    }
    private static WikiApi api() throws IOException {
        return new WikiApi(new WikipediaTransport(), Path.of("corpus/source-intake-cache"), Clock.systemUTC(), System.getenv("CLUELAB_WIKI_USER_AGENT"), 1000);
    }
    private static Path admin(String argument) throws IOException {
        Path root = Path.of("drafts").toAbsolutePath().normalize(), path = Path.of(argument).toAbsolutePath().normalize();
        require(path.startsWith(root) && !path.equals(root), "Admin inputs/outputs must stay below ./drafts/ (never active packs, archive or benchmarks)");
        IntakeFiles.safe(path); return path;
    }
    private static String usage() {
        return "SourceIntake: preview <English-topic> <drafts/output> [limit=8] | select <preview-dir> <selection-dir> <pageIds,comma-separated> --accept-cc-by-sa | capture <selection-dir> <capture-dir> | export <capture-dir> <drafts/proposals.json> <drafts/output> | validate|index <draft-dir>. Optional last argument --cancel=marker-path";
    }
    static JsonObject select(JsonObject preview, Set<Integer> ids) throws IOException {
        IntakeFiles.schema(preview, "topic language capturedAt responseSha256 candidates");
        require("en".equals(string(preview, "language", 10)), "Only English topics are supported");
        require(!ids.isEmpty() && ids.size() <= WikiApi.MAX_PAGES, "Select 1-12 distinct preview page IDs");
        JsonArrayBuilder selected = Json.createArrayBuilder(); Set<Integer> found = new HashSet<>();
        for (JsonValue value : array(preview, "candidates")) {
            JsonObject row = object(value, "candidate"); keys(row, "candidate", "pageId title url");
            int id = integer(row, "pageId"); WikiApi.title(row);
            require(id > 0 && found.add(id), "Invalid/duplicate preview page ID");
            require(WikiApi.article(row.getString("title")).equals(row.getString("url")), "Candidate URL is not its structured English title");
            if (ids.contains(id)) selected.add(row);
        }
        require(found.containsAll(ids), "Selected page ID is absent from the frozen preview");
        return Json.createObjectBuilder().add("schemaVersion", 1).add("topic", string(preview, "topic", 150)).add("language", "en")
                .add("previewSha256", sha256(IntakeFiles.json(preview))).add("policy", "english-wikipedia-text-cc-by-sa-4.0-v1")
                .add("licenseUrl", WikiApi.LICENSE).add("licenseEvidence", WikiApi.TERMS)
                .add("acknowledgement", "Operator selected these pages after inspecting source rights/attribution; not factual approval.")
                .add("selected", selected).build();
    }
    static JsonObject capture(JsonObject selection, WikiApi api, WikiApi.Budget budget) throws IOException {
        return capture(selection, api, budget, progress -> {});
    }
    static JsonObject capture(JsonObject selection, WikiApi api, WikiApi.Budget budget, java.util.function.Consumer<JsonObject> progress) throws IOException {
        IntakeFiles.schema(selection, "topic language previewSha256 policy licenseUrl licenseEvidence acknowledgement selected");
        require("en".equals(string(selection, "language", 10)) && "english-wikipedia-text-cc-by-sa-4.0-v1".equals(string(selection, "policy", 100))
                && WikiApi.LICENSE.equals(string(selection, "licenseUrl", 200)) && WikiApi.TERMS.equals(string(selection, "licenseEvidence", 300)), "Source policy acknowledgement is missing/unsupported");
        JsonArray chosen = array(selection, "selected"); require(!chosen.isEmpty() && chosen.size() <= WikiApi.MAX_PAGES, "Select 1-12 sources");
        Set<Integer> requestedIds = new HashSet<>();
        int completed = 0;
        for (JsonValue value : chosen) {
            JsonObject row = object(value, "selected source"); keys(row, "selected source", "pageId title url");
            require(requestedIds.add(integer(row, "pageId")), "Duplicate selected page ID");
            require(WikiApi.article(WikiApi.title(row)).equals(string(row, "url", 2000)), "Invalid structured article URL");
        }
        JsonArrayBuilder sources = Json.createArrayBuilder(), failures = Json.createArrayBuilder(); Set<Integer> canonical = new HashSet<>(); Set<String> texts = new HashSet<>();
        for (JsonValue value : chosen) {
            try { budget.check(); }
            catch (IOException stopped) { throw new IOException(stopped.getMessage() + "; captured " + canonical.size() + "/" + chosen.size()
                    + " selected pages. Cached successes can be reused; no capture/export replaced.", stopped); }
            JsonObject row = object(value, "selected source");
            progress.accept(Json.createObjectBuilder().add("title", row.getString("title")).add("completed", completed).add("total", chosen.size()).build());
            try {
                JsonObject source = api.capture(row, budget);
                require(canonical.add(integer(source, "pageId")), "Duplicate canonical page after redirect resolution");
                require(texts.add(source.getString("textSha256")), "Duplicate source text"); sources.add(source);
            } catch (InterruptedIOException cancelled) {
                throw new InterruptedIOException(cancelled.getMessage() + "; completed " + canonical.size() + "/" + chosen.size()
                        + " selected pages before cancellation. Cached successes can be reused; no capture/export replaced.");
            }
            catch (IOException error) {
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                failures.add(Json.createObjectBuilder().add("requestedPageId", integer(row, "pageId")).add("title", row.getString("title"))
                        .add("error", message.substring(0, Math.min(1000, message.length()))));
            } catch (RuntimeException malformed) {
                failures.add(Json.createObjectBuilder().add("requestedPageId", integer(row, "pageId")).add("title", row.getString("title"))
                        .add("error", "Malformed API article fields: " + malformed.getClass().getSimpleName()));
            }
            completed++;
            progress.accept(Json.createObjectBuilder().add("title", row.getString("title")).add("completed", completed).add("total", chosen.size()).build());
        }
        return Json.createObjectBuilder().add("schemaVersion", 1).add("topic", string(selection, "topic", 150)).add("language", "en")
                .add("selectionSha256", sha256(IntakeFiles.json(selection))).add("selection", selection).add("sources", sources).add("failures", failures).build();
    }
    static JsonObject captureReport(JsonObject capture) {
        return Json.createObjectBuilder().add("capturedSources", capture.getJsonArray("sources").size())
                .add("failures", capture.getJsonArray("failures")).add("note", "Context heuristics only; no factual/editorial approval.").build();
    }
}
