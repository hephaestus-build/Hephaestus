package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.handler.FeedbackTextCheck;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The labelled composition cases of {@code scripts/feedback-eval/compositions.json}, each rendered by main's review
 * composer: the opening turn and the report_review tool that {@link WorkerFeedbackComposer#reviewTurn} renders, and
 * the acceptance check of {@link ReviewOnWork#read}. Each case becomes a scenario in the layout of the golden
 * pull-request scenario. {@code docs/contributor/feedback-quality-evals.md} owns the use.
 */
final class CompositionEvalCases {
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String HEAD = "2222222222222222222222222222222222222222";
    private static final String BASE = "1111111111111111111111111111111111111111";
    private static final String EARLIER = "3333333333333333333333333333333333333333";
    private static final String CAPTURED_AT = "2026-10-30T10:00:00Z";
    private static final String READ_AT = "2026-10-30T10:06:00Z";
    /** From the module directory that Gradle runs tests in. */
    private static final Path CASES = Path.of("../../scripts/feedback-eval/compositions.json");

    static final JsonMapper MAPPER = new JsonMapper();

    private CompositionEvalCases() {}

    /** Every case of the set, as main's composer reads it. */
    static List<Scenario> load() throws Exception {
        JsonNode corpus = MAPPER.readTree(Files.readAllBytes(CASES));
        JsonNode catalog = catalog();
        var scenarios = new ArrayList<Scenario>();
        for (JsonNode item : corpus.path("cases")) scenarios.add(scenario(item, catalog));
        return scenarios;
    }

    /** The composer's system prompt for the review on the work. */
    static String system() {
        return WorkerFeedbackComposer.sharedStyle() + "\n\n" + WorkerFeedbackComposer.prompt("review-composer.md");
    }

    /** One model request: the case's opening turn and its report_review tool. */
    static ObjectNode request(Scenario scenario) {
        var request = JSON.objectNode();
        request.put("id", scenario.id());
        request.put("turn", scenario.turn().text());
        var tool = request.putObject("tool");
        tool.put("name", "report_review");
        tool.put("description", ReviewOnWork.TOOL_DESCRIPTION);
        tool.set("parameters", scenario.turn().toolParameters());
        return request;
    }

    /** One case as main's composer reads it, with the case's short ids mapped to the ids the model sees. */
    record Scenario(
            String id,
            JsonNode item,
            WorkerFeedbackComposer.ReviewTurn turn,
            Map<String, String> ids,
            Map<String, String> back) {
        JsonNode toModel(JsonNode review) {
            return rename(review, ids);
        }

        JsonNode fromModel(JsonNode review) {
            return rename(review, back);
        }

        private static JsonNode rename(JsonNode node, Map<String, String> names) {
            if (node.isString()) return JSON.stringNode(names.getOrDefault(node.asString(), node.asString()));
            if (node.isArray()) {
                var copy = JSON.arrayNode();
                node.forEach(value -> copy.add(rename(value, names)));
                return copy;
            }
            if (node.isObject()) {
                var copy = JSON.objectNode();
                node.properties().forEach(entry -> copy.set(entry.getKey(), rename(entry.getValue(), names)));
                return copy;
            }
            return node;
        }
    }

    /**
     * Main's verdict on a review: whether preparation accepts it, its reasons, and the tool answer that the composer
     * sends back to the model when it refuses.
     */
    record Checked(
            boolean accepted, List<String> errors, @Nullable String correction) {}

    /** {@link ReviewOnWork#read}, then the text policy that preparation applies, as the composer answers each. */
    static Checked check(Scenario scenario, JsonNode review) {
        var read = ReviewOnWork.read(
                review,
                scenario.turn().restable(),
                scenario.turn().lineNotes(),
                scenario.turn().witnesses());
        var composed = read.review();
        if (composed == null)
            return new Checked(
                    false,
                    read.errors(),
                    "review refused, nothing was stored:\n"
                            + String.join(
                                    "\n",
                                    read.errors().stream()
                                            .map(error -> "- " + error)
                                            .toList()));
        var texts = new LinkedHashMap<String, String>();
        var summary = composed.summary();
        if (summary != null) texts.put("summary", summary.body());
        for (int index = 0; index < composed.inline().size(); index++)
            texts.put("notes[" + index + "]", composed.inline().get(index).body());
        var refused = FeedbackTextCheck.refusal(texts);
        if (refused == null) return new Checked(true, List.of(), null);
        String reason = WorkerFeedbackComposer.textRefusal(refused);
        return new Checked(
                false,
                List.of("text: " + reason),
                "review refused, nothing was stored: " + reason + "\nSend the whole review again.");
    }

    static ObjectNode checkRun(JsonNode run, Map<String, Scenario> scenarios) {
        var file = JSON.objectNode();
        file.put("version", 1);
        file.set("model", run.path("model"));
        file.set("runner", run.path("runner"));
        var cases = file.putArray("cases");
        for (JsonNode output : run.path("cases")) {
            var scenario = Objects.requireNonNull(
                    scenarios.get(output.path("id").asString()),
                    output.path("id").asString());
            var entry = cases.addObject();
            entry.put("id", scenario.id());
            JsonNode arguments = output.path("arguments");
            if (!arguments.isObject()) {
                entry.put("accepted", false);
                entry.set("errors", strings(List.of("no report_review call")));
                entry.putNull("review");
                continue;
            }
            var checked = check(scenario, arguments);
            entry.put("accepted", checked.accepted());
            entry.set("errors", strings(checked.errors()));
            entry.set("review", scenario.fromModel(ReviewOnWork.unwrapArguments(arguments)));
        }
        return file;
    }

    // ---------------------------------------------------------------- the scenario

    private static Scenario scenario(JsonNode item, JsonNode catalog) throws Exception {
        String id = item.path("id").asString();
        var ids = new HashMap<String, String>();
        var back = new HashMap<String, String>();
        var files = golden("workspace");
        files.keySet().removeIf(path -> path.startsWith("texts/") || path.startsWith("inputs/practices/"));
        JsonNode work = item.path("work");
        boolean gitlab = "gitlab".equals(item.path("provider").asString());
        String url = gitlab
                ? "https://gitlab.com/" + work.path("repository").asString() + "/-/merge_requests/"
                        + work.path("number").asInt()
                : "https://github.com/" + work.path("repository").asString() + "/pull/"
                        + work.path("number").asInt();

        var metadata = JSON.objectNode();
        metadata.putObject("capture").put("completeness", "COMPLETE").putArray("limitations");
        metadata.putObject("basis").put("other_fields", "CAPTURE").put("captured_at", CAPTURED_AT);
        metadata.put("title", work.path("title").asString());
        metadata.put("pr_url", url);
        metadata.put("repository_full_name", work.path("repository").asString());
        metadata.put("pr_number", work.path("number").asInt());
        metadata.put("state", work.path("state").asString("open"));
        metadata.put("is_draft", work.path("draft").asBoolean(false));
        metadata.put("is_merged", false);
        metadata.put("source_branch", work.path("sourceBranch").asString("feature"));
        metadata.put("target_branch", work.path("targetBranch").asString("main"));
        metadata.put("commit_sha", HEAD);
        // The captured author is the review's recipient: only someone else's comment can stand as advice it received.
        metadata.put("author", work.path("author").asString());
        metadata.put("author_id", 100);
        metadata.put("body", work.path("description").asString(""));
        metadata.putArray("notInCapture").add("closed_at").add("merged_at");
        files.put("context/metadata.json", json(metadata));
        files.put("context/description.md", utf8(work.path("description").asString("")));
        files.put(
                "context/linked_work_items.json",
                utf8(
                        "{\"completeness\":\"COMPLETE\",\"limitations\":[],\"truncated\":false,\"workItems\":[],\"unresolvedReferences\":[]}"));
        files.put("context/review_threads.json", utf8("[]"));
        files.put(
                "context/commits.json",
                json(JSON.arrayNode()
                        .add(JSON.objectNode()
                                .put("sha", HEAD)
                                .put("message", work.path("title").asString()))));

        var sources = new TreeMap<String, String>();
        item.path("files")
                .properties()
                .forEach(entry -> sources.put(entry.getKey(), entry.getValue().asString()));
        var change = JSON.objectNode().put("base_sha", BASE).put("head_sha", HEAD);
        var changed = change.putArray("changed_paths");
        var diff = new StringBuilder();
        for (var source : sources.entrySet()) {
            changed.add(source.getKey());
            diff.append(addedFile(source.getKey(), source.getValue()));
        }
        change.put("diff", diff.toString());
        files.put("context/change.json", json(change));

        boolean commentsCaptured =
                !"UNAVAILABLE".equals(item.path("discussionCapture").asString("COMPLETE"));
        var comments = JSON.arrayNode();
        int nativeId = 600;
        for (JsonNode statement : item.path("discussion")) {
            nativeId++;
            back.put(
                    "comment:context/comments.json:" + nativeId,
                    statement.path("id").asString());
            ids.put(statement.path("id").asString(), "comment:context/comments.json:" + nativeId);
            var comment = comments.addObject();
            comment.put("id", nativeId - 500);
            comment.put("thread", nativeId - 500);
            comment.put("native_id", nativeId);
            comment.put("author", statement.path("author").asString());
            comment.put("author_id", 200 + nativeId);
            comment.put("body", statement.path("body").asString());
            comment.put("created_at", statement.path("statedAt").asString());
            comment.put("updated_at", statement.path("statedAt").asString());
            comment.put("commit_id", HEAD);
            if (statement.path("path").isString()) {
                comment.put("path", statement.path("path").asString());
                comment.put("line", statement.path("line").asInt());
                comment.put("side", "RIGHT");
                comment.put("state", "open");
                comment.put("outdated", false);
            }
        }
        if (commentsCaptured) files.put("context/comments.json", json(comments));
        else files.remove("context/comments.json");

        var index = (ObjectNode) MAPPER.readTree(Objects.requireNonNull(files.get("INDEX.json")));
        for (JsonNode source : index.path("sources")) {
            var state = (ObjectNode) source.path("state");
            String kind = source.path("kind").asString();
            if (kind.equals("scm.linked-work-items")) {
                state.put("completeness", "COMPLETE");
                state.putArray("limitations");
            }
            if (kind.equals("scm.pull-request.comments") && !commentsCaptured) {
                state.removeAll();
                state.put("availability", "UNAVAILABLE").put("reasonCode", "PROVIDER_ERROR");
                ((ObjectNode) source).putArray("files");
            }
        }
        if (!commentsCaptured) {
            var kept = JSON.arrayNode();
            index.path("files").forEach(file -> {
                if (!file.path("kind").asString().equals("scm.pull-request.comments")) kept.add(file);
            });
            index.set("files", kept);
        }
        index.put("capturedAt", CAPTURED_AT);
        var citable = index.putArray("citableTexts");
        var shas = new HashMap<String, String>();
        int staged = 0;
        for (var source : sources.entrySet()) {
            staged++;
            String stagedPath = "texts/file-" + staged + ".txt";
            String sha = sha256(source.getValue());
            shas.put(source.getKey(), sha);
            files.put(stagedPath, utf8(source.getValue()));
            for (String kind : List.of("scm.repository.tree", "scm.pull-request.diff"))
                citable.addObject()
                        .put("source", kind)
                        .put("path", source.getKey())
                        .put("side", "NEW")
                        .put("commit", HEAD)
                        .put("stagedPath", stagedPath)
                        .put("sha256", sha);
        }
        files.put("INDEX.json", json(index));
        // The checkout the tree capture vouches for: PrimarySource reads the cited files at this commit.
        files.put("repos/reviewed/.git/HEAD", utf8(HEAD + "\n"));
        files.put("repos/reviewed/.git/hephaestus-captured-refs", utf8(HEAD + " refs/heads/retry\n"));

        // The practices: every NOT_MET, limited and not-reached practice, with the catalog's criteria as staged.
        var practiceSlugs = new LinkedHashSet<String>();
        item.path("results")
                .forEach(result -> practiceSlugs.add(result.path("practice").asString()));
        item.path("limits")
                .forEach(limit -> practiceSlugs.add(limit.path("practice").asString()));
        item.path("notReached").forEach(slug -> practiceSlugs.add(slug.asString()));
        var practiceIndex = JSON.arrayNode();
        for (String slug : practiceSlugs) {
            JsonNode practice = Objects.requireNonNull(practice(catalog, slug), id + ": unknown practice " + slug);
            JsonNode definition = practice.path("definition");
            var entry = practiceIndex.addObject();
            entry.put("slug", slug);
            entry.put("name", definition.path("name").asString());
            entry.putArray("readsSources").add("scm.pull-request.diff");
            entry.putArray("exhaustiveSources");
            var limits = entry.putArray("knownLimitations");
            definition.path("reviewedBy").path("limits").forEach(limit -> {
                if (limit.isString()) limits.add(limit.asString());
            });
            entry.put("versionId", "1");
            files.put(
                    "inputs/practices/" + slug + ".md",
                    utf8(definition.path("criteria").asString()));
        }
        files.put("inputs/practices/index.json", json(practiceIndex));
        files.put("inputs/history/problems.json", utf8("{\"results\":[]}"));

        // The admitted results, in the admission response's shape.
        var admission = (ObjectNode) json(golden("admission.json"));
        var admitted = admission.putArray("admitted");
        for (JsonNode result : item.path("results")) {
            String resultId = uuid(id, result.path("id").asString());
            ids.put(result.path("id").asString(), resultId);
            back.put(resultId, result.path("id").asString());
            var entry = admitted.addObject();
            entry.put("practiceSlug", result.path("practice").asString());
            entry.put("itemId", "7");
            entry.put("resultId", resultId);
            entry.put("outcome", "NOT_MET");
            entry.put("summary", result.path("summary").asString());
            entry.put("explanation", result.path("explanation").asString());
            entry.putObject("evidence").set("citations", citations(id, result.path("citations"), sources, shas));
            entry.putNull("example");
            entry.putObject("coverage")
                    .put("source", "claimed")
                    .put("complete", true)
                    .put("selected", 1)
                    .put("checked", 1)
                    .put("total", 1);
            var problems = entry.putArray("problems");
            int position = 0;
            for (JsonNode problem : result.path("problems")) {
                String problemId = uuid(id, problem.path("id").asString());
                ids.put(problem.path("id").asString(), problemId);
                back.put(problemId, problem.path("id").asString());
                var shown = problems.addObject();
                shown.put("id", problemId);
                shown.put("severity", problem.path("severity").asString("MINOR"));
                shown.put("summary", problem.path("summary").asString());
                shown.put("resultId", resultId);
                shown.put("position", position++);
                shown.putNull("key");
                shown.put("explanation", problem.path("explanation").asString());
                shown.set("citations", citations(id, problem.path("citations"), sources, shas));
                shown.putNull("basis");
                if (problem.path("nextStep").isString())
                    shown.put("nextStep", problem.path("nextStep").asString());
                else shown.putNull("nextStep");
                shown.put("publicEligible", true);
            }
            entry.put("summaryOnly", result.path("summaryOnly").asBoolean(false));
            entry.put("publicEligible", true);
        }
        for (JsonNode limit : item.path("limits")) {
            var entry = admitted.addObject();
            String outcome = limit.path("outcome").asString();
            entry.put("practiceSlug", limit.path("practice").asString());
            entry.put("itemId", "7");
            entry.put("resultId", uuid(id, "limit:" + limit.path("practice").asString()));
            entry.put("outcome", outcome);
            entry.put("summary", outcome.equals("DOES_NOT_APPLY") ? "Does not apply" : "Could not be decided");
            entry.putNull("explanation");
            var evidence = entry.putObject("evidence");
            evidence.putArray("citations");
            if (outcome.equals("COULD_NOT_DETERMINE")) evidence.set("openQuestions", limit.path("openQuestions"));
            else evidence.put("inapplicability", limit.path("inapplicability").asString());
            entry.putNull("example");
            entry.putObject("coverage")
                    .put("source", "claimed")
                    .put("complete", false)
                    .put("selected", 0)
                    .put("checked", 0)
                    .put("total", 1);
            entry.putArray("problems");
            entry.put("summaryOnly", false);
            entry.put("publicEligible", true);
        }
        admission.putArray("withheld");
        admission
                .putObject("practicePageSupport")
                .put("state", "COMPLETE")
                .put("readAt", READ_AT)
                .putArray("practices");
        admission
                .putObject("conversationEligibility")
                .put("state", "COMPLETE")
                .put("readAt", READ_AT)
                .putArray("results");
        ((ObjectNode) admission.path("privateHistory")).putArray("delivered");

        // What Hephaestus already delivered on this work.
        var history = (ObjectNode) json(golden("public-history.json"));
        history.put("readAt", READ_AT);
        var historyItem = (ObjectNode) history.path("history").path("item");
        historyItem.put("provider", gitlab ? "GITLAB" : "GITHUB");
        historyItem.put(
                "label",
                work.path("repository").asString()
                        + (gitlab ? "!" : "#")
                        + work.path("number").asInt());
        historyItem.put("title", work.path("title").asString());
        historyItem.put("url", url);
        var feedback = ((ObjectNode) history.path("history")).putArray("feedback");
        for (JsonNode said : item.path("alreadySaid")) {
            String feedbackId = uuid(id, said.path("id").asString());
            ids.put(said.path("id").asString(), "feedback:" + feedbackId);
            back.put("feedback:" + feedbackId, said.path("id").asString());
            var entry = feedback.addObject();
            entry.put("id", feedbackId);
            entry.put("destination", "ON_WORK");
            entry.put("state", "DELIVERED");
            entry.put("deliveredAt", said.path("deliveredAt").asString());
            entry.put("itemVersion", said.path("sameVersion").asBoolean(false) ? HEAD : EARLIER);
            if (said.path("body").isString())
                entry.put("body", said.path("body").asString());
            else entry.putNull("body");
            entry.put("bodyUnavailable", false);
            entry.put("currentness", said.path("currentness").asString("CURRENT"));
            entry.put("withdrawn", false);
            entry.putArray("basedOn")
                    .addObject()
                    .put("resultId", uuid(id, "earlier:" + said.path("id").asString()))
                    .put("practiceSlug", said.path("practice").asString())
                    .put("practiceVersionId", "1")
                    .put("outcome", "NOT_MET");
            var copies = entry.putArray("copies");
            int copy = 0;
            for (JsonNode note : said.path("notes")) {
                copy++;
                copies.addObject()
                        .put("id", uuid(id, said.path("id").asString() + ":copy:" + copy))
                        .put("kind", "LINE_NOTE")
                        .put("body", note.path("body").asString())
                        .put("path", note.path("path").asString())
                        .put("startLine", note.path("line").asInt())
                        .put("endLine", note.path("line").asInt())
                        .put("side", "NEW")
                        .put("externalRef", "n" + copy)
                        .put("externalUrl", url + "#note-" + copy)
                        .put("postedAt", said.path("deliveredAt").asString())
                        .put("state", "POSTED");
            }
        }

        return new Scenario(id, item, render(files, admission, history), ids, back);
    }

    /** The path the golden test drives, which is the path the composer drives from its inputs. */
    private static WorkerFeedbackComposer.ReviewTurn render(
            Map<String, byte[]> inputs, JsonNode response, JsonNode history) throws IOException {
        var decided = new HashSet<String>();
        var state = Admission.of(response, Map.of("PRACTICE_PAGE", decided, "CONVERSATION", decided));
        byte[] requestBytes = Objects.requireNonNull(inputs.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH));
        var request = PrivateFeedback.Request.of(MAPPER.readTree(requestBytes)).orElseThrow();
        var files = WorkerFeedbackComposer.workspace(inputs, requestBytes, state);
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, MAPPER).orElseThrow();
        JsonNode practiceIndex =
                files.json(ComposerFiles.PRACTICE_INDEX, MAPPER).orElseThrow();
        var notReached = WorkerFeedbackComposer.notReached(practiceIndex, state);
        var captured = new CapturedWork(files, MAPPER);
        return WorkerFeedbackComposer.reviewTurn(
                files,
                captured,
                folderIndex,
                practiceIndex,
                state,
                request,
                history,
                notReached,
                PrimarySource.captured(files, folderIndex, ComposerFiles.CONTEXT_ROOT, ComposerFiles.REPOSITORY_ROOT));
    }

    private static ArrayNode citations(
            String caseId, JsonNode cited, Map<String, String> sources, Map<String, String> shas) {
        var list = JSON.arrayNode();
        int index = 0;
        for (JsonNode citation : cited) {
            String path = citation.path("path").asString();
            int start = citation.path("startLine").asInt();
            int end = citation.path("endLine").asInt(start);
            var entry = list.addObject();
            if (path.equals("description")) {
                entry.put("sourceKind", "scm.pull-request.core");
                entry.put("sourcePath", "context/description.md");
                entry.put("path", "context/description.md");
                entry.putNull("side");
                entry.putNull("commit");
            } else {
                String text =
                        Objects.requireNonNull(sources.get(path), caseId + ": cites a file it does not hold: " + path);
                String[] lines = text.split("\n", -1);
                assertThat(start)
                        .as("%s: %s lines %d-%d", caseId, path, start, end)
                        .isBetween(1, end);
                assertThat(end).as("%s: %s line %d", caseId, path, end).isLessThanOrEqualTo(lines.length);
                String quote = lines[start - 1].strip();
                entry.put("sourceKind", "scm.pull-request.diff");
                entry.put("sourcePath", "context/change.json");
                entry.put("path", path);
                entry.put("side", "NEW");
                entry.put("commit", HEAD);
                entry.put("startLine", start);
                entry.put("endLine", end);
                entry.put("quote", quote);
                entry.putObject("verification")
                        .put("status", "VERIFIED")
                        .put("itemSha256", shas.get(path))
                        .put("quoteSha256", sha256(quote));
                entry.put("index", index++);
                entry.put("anchorable", citation.path("anchorable").asBoolean(false));
                continue;
            }
            entry.put("startLine", start);
            entry.put("endLine", end);
            entry.put("quote", "");
            entry.put("index", index++);
            entry.put("anchorable", false);
        }
        return list;
    }

    /** A file the change adds, as Git prints it. */
    private static String addedFile(String path, String text) {
        var lines = text.endsWith("\n") ? text.substring(0, text.length() - 1).split("\n", -1) : text.split("\n", -1);
        var diff = new StringBuilder("diff --git a/" + path + " b/" + path
                + "\nnew file mode 100644\n--- /dev/null\n+++ b/" + path + "\n@@ -0,0 +1," + lines.length + " @@\n");
        for (String line : lines) diff.append('+').append(line).append('\n');
        return diff.toString();
    }

    private static @Nullable JsonNode practice(JsonNode catalog, String slug) {
        for (JsonNode group : catalog.path("groups"))
            for (JsonNode practice : group.path("practices"))
                if (slug.equals(practice.path("slug").asString())) return practice;
        return null;
    }

    private static JsonNode catalog() throws IOException {
        try (var stream = CompositionEvalCases.class.getResourceAsStream("/practices/default-catalog.json")) {
            return MAPPER.readTree(Objects.requireNonNull(stream).readAllBytes());
        }
    }

    private static String uuid(String caseId, String name) {
        return UUID.nameUUIDFromBytes((caseId + "/" + name).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private static Map<String, byte[]> golden(String name) throws IOException, URISyntaxException {
        var url = Objects.requireNonNull(
                CompositionEvalCases.class.getResource("/agent/golden-prompts/composition-turns/pull-request/" + name));
        Path root = Path.of(url.toURI());
        var files = new TreeMap<String, byte[]>();
        if (Files.isRegularFile(root)) {
            files.put(name, Files.readAllBytes(root));
            return files;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList())
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return files;
    }

    private static JsonNode json(Map<String, byte[]> single) throws IOException {
        return MAPPER.readTree(single.values().iterator().next());
    }

    private static byte[] json(JsonNode node) {
        return utf8(JsonText.stringify(node, 1));
    }

    /** Replaces the file in one move, so a run stopped mid-write leaves the earlier file whole for its resume. */
    static void write(String file, JsonNode node) throws IOException {
        Path target = Path.of(file).toAbsolutePath();
        Path partial = target.resolveSibling(target.getFileName() + ".partial");
        Files.writeString(partial, JsonText.stringify(node, 1) + "\n", StandardCharsets.UTF_8);
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static ArrayNode strings(List<String> values) {
        var list = JSON.arrayNode();
        values.forEach(list::add);
        return list;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(utf8(text)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
