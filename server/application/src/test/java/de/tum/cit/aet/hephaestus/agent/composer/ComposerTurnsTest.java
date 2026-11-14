package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the composer gives the model for each sealed scenario beside its inputs: which results and problems each turn
 * carries, which sources it names as omitted, and the structure of each tool. The prompt wording is not asserted
 * (docs/contributor/testing.mdx § Prompts and model behavior); the composition set measures it.
 */
@Tag("unit")
class ComposerTurnsTest {
    /** Rewrites each tool schema file that differs from what the composer renders, without descriptions, and fails. */
    private static final boolean UPDATE = "1".equals(System.getenv("UPDATE_COMPOSITION_TOOLS"));
    /** The source copy of the scenarios, from the module directory that Gradle runs tests in. */
    private static final Path SOURCE = Path.of("src/test/resources/agent/golden-prompts/composition-turns");

    private static final List<String> SCENARIOS = List.of("pull-request", "issue", "resumed", "oversized");
    private static final List<String> PROMPTS =
            List.of("feedback-style.md", "review-composer.md", "feedback-composer.md");
    private static final Set<String> TOOLS = Set.of("check_package", "publish_package", "edit_own");
    private static final Pattern MENTIONED_NAME =
            Pattern.compile("`([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)`");
    private static final Pattern LINE_NUMBER = Pattern.compile("(?m)^\\[L\\d+\\] ?");
    private static final Pattern JSON_BLOCK = Pattern.compile("(?m)^```json\\n(.*?)\\n```$", Pattern.DOTALL);

    private static final JsonMapper MAPPER = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "oversized"})
    void shouldOfferTheReviewOnTheWorkOnlyItsPublicNotMetResultsWithTheirProblems(String name) throws Exception {
        var scenario = render(name);
        assertThat(scenario.reviewTurn()).isNotNull();
        for (JsonNode result : scenario.admitted()) {
            String id = result.path("resultId").asString();
            boolean raisable = result.path("outcome").asString().equals("NOT_MET")
                    && result.path("publicEligible").asBoolean();
            if (raisable) {
                assertThat(scenario.reviewTurn()).as(id).contains(id);
                for (JsonNode problem : result.path("problems"))
                    assertThat(scenario.reviewTurn())
                            .contains(problem.path("id").asString());
            } else {
                assertThat(scenario.reviewTurn()).as(id).doesNotContain(id);
            }
        }
    }

    /** What only the developer may see, and what was not found wanting, never reaches the review on the work. */
    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "oversized"})
    void shouldKeepPrivateHistoryAndMetPracticesOutOfTheReviewOnTheWork(String name) throws Exception {
        var scenario = render(name);
        String review = Objects.requireNonNull(scenario.reviewTurn());
        for (JsonNode delivered : scenario.privateHistory().path("delivered")) {
            String id = delivered.path("id").asString();
            assertThat(review).as("delivered private feedback %s", id).doesNotContain(id);
            assertThat(review).doesNotContain(delivered.path("body").asString());
            assertThat(scenario.feedbackTurn())
                    .as("the private composer sees %s", id)
                    .contains(id);
        }
        for (JsonNode queued : scenario.privateHistory().path("queued")) {
            String id = queued.path("feedbackId").asString();
            assertThat(review).as("prepared private feedback %s", id).doesNotContain(id);
            assertThat(scenario.feedbackTurn())
                    .as("the private composer sees %s", id)
                    .contains(id);
        }
        for (JsonNode result : scenario.admitted())
            if (result.path("outcome").asString().equals("MET"))
                assertThat(review).doesNotContain(result.path("practiceSlug").asString());
    }

    @Test
    void shouldOpenNoReviewOnTheWorkOnceThatReviewWasPublished() throws Exception {
        assertThat(render("resumed").reviewTurn()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "resumed", "oversized"})
    void shouldGiveThePrivateComposerEveryAdmittedResultAndEveryProblem(String name) throws Exception {
        var scenario = render(name);
        for (JsonNode result : scenario.admitted()) {
            assertThat(scenario.feedbackTurn()).contains(result.path("resultId").asString());
            for (JsonNode problem : result.path("problems"))
                assertThat(scenario.feedbackTurn()).contains(problem.path("id").asString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "resumed", "oversized"})
    void shouldGiveThePrivateComposerTheCompositionRequestUnchanged(String name) throws Exception {
        var scenario = render(name);
        JsonNode request = MAPPER.readTree(scenario.inputs().get(SandboxLayout.FEEDBACK_COMPOSITION_PATH));
        assertThat(blocks(scenario.feedbackTurn())).contains(request);
    }

    @Test
    void shouldShowThePinnedSourceThatTheResultsCiteAndNoUncitedChange() throws Exception {
        String turn = Objects.requireNonNull(render("pull-request").reviewTurn());
        assertThat(turn)
                .as("the cited file at the pinned commit, numbered")
                .contains("[L16]             return null;")
                .as("the cited file's section of the pinned diff")
                .contains("[L16] +            return null;")
                .as("the README change that no result cites")
                .doesNotContain("Retries are not supported.");
    }

    @Test
    void shouldNameEachDiscussionSourceThatTheReviewCannotShow() throws Exception {
        assertThat(omittedSources(Objects.requireNonNull(render("oversized").reviewTurn())))
                .as("a comment file too large to show, an unavailable source, and a shown one")
                .containsEntry("context/comments.json", true)
                .containsEntry("context/general_comments.json", true)
                .containsEntry("context/review_threads.json", false);
        assertThat(omittedSources(Objects.requireNonNull(render("pull-request").reviewTurn())))
                .as("the same comment file at a size that fits")
                .containsEntry("context/comments.json", false);
    }

    /**
     * The private composer reads the work's captured text only from the brief, so each captured context file is shown
     * or named as too large, never dropped. The change itself is the exception: the review turn shows it numbered.
     */
    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "resumed", "oversized"})
    void shouldBriefThePrivateComposerOnEveryCapturedContextFileWithinItsLimit(String name) throws Exception {
        var scenario = render(name);
        assertThat(scenario.brief().length()).isLessThanOrEqualTo(CapturedWork.TOTAL_CHARS);
        scenario.inputs().forEach((path, bytes) -> {
            boolean context = path.startsWith(ComposerFiles.CONTEXT_ROOT + "/")
                    && path.indexOf('/', ComposerFiles.CONTEXT_ROOT.length() + 1) < 0;
            if (context && bytes.length > 0 && !path.endsWith("/change.json"))
                assertThat(scenario.brief()).as(path).contains("`" + path + "`");
        });
        // The work's own words reach the composer, not only the file names.
        scenario.inputs().forEach((path, bytes) -> {
            if (!path.startsWith(ComposerFiles.CONTEXT_ROOT + "/") || !path.endsWith(".md")) return;
            new String(bytes, StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> !line.isBlank())
                    .findFirst()
                    .ifPresent(line -> assertThat(scenario.brief()).as(path).contains(line));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "resumed", "oversized"})
    void shouldOfferTheToolsTheModelIsToldToCall(String name) throws Exception {
        var scenario = render(name);
        if (scenario.reviewTool() != null) structure(name, "review-tool.json", scenario.reviewTool());
        structure(name, "feedback-tool.json", scenario.feedbackTool());
    }

    /**
     * A prompt that names a field, a value or a tool the model never receives sends it after something that does not
     * exist. Every name a composer prompt quotes must appear in a tool schema or in the data of a rendered turn.
     */
    @Test
    void shouldMentionOnlyNamesThatTheModelReceives() throws Exception {
        var known = new HashSet<>(TOOLS);
        known.add("null");
        // Names the model receives outside a rendered turn: the server's check receipt and the publish_package and
        // edit_own parameters carry draftId, and an earlier review record names its work (ResultHistoryContentSource
        // .putWork), which no scenario stages.
        known.addAll(List.of("draftId", "work", "number", "url"));
        for (JsonNode block : blocks(markedDiscussion().privateTurn())) vocabulary(block, known);
        for (String name : SCENARIOS) {
            var scenario = render(name);
            for (String text : Stream.of(scenario.reviewTurn(), scenario.feedbackTurn(), scenario.brief())
                    .filter(Objects::nonNull)
                    .toList()) for (JsonNode block : blocks(text)) vocabulary(block, known);
            if (scenario.reviewTool() != null) vocabulary(scenario.reviewTool(), known);
            vocabulary(scenario.feedbackTool(), known);
        }
        var instructions = new TreeMap<String, String>();
        for (String prompt : PROMPTS) instructions.put(prompt, CompositionSessions.prompt(prompt));
        instructions.put("ReviewOnWork.WRITE_CONTRACT", ReviewOnWork.WRITE_CONTRACT);
        instructions.put("ReviewOnWork.TOOL_DESCRIPTION", ReviewOnWork.TOOL_DESCRIPTION);
        instructions.put("PrivateFeedback.TOOL_DESCRIPTION", PrivateFeedback.TOOL_DESCRIPTION);
        instructions.put("ReviewOnWork.NUDGE", ReviewOnWork.NUDGE);
        instructions.put("PrivateFeedback.NUDGE", PrivateFeedback.NUDGE);
        instructions.forEach((source, text) -> {
            var matcher = MENTIONED_NAME.matcher(text);
            while (matcher.find())
                for (String segment : matcher.group(1).split("\\.", -1))
                    assertThat(known)
                            .as("%s names `%s`", source, matcher.group(1))
                            .contains(segment);
        });
    }

    @Test
    void shouldKeepUnmatchedMarkerProvenanceInTheComposersDiscussion() throws Exception {
        var marked = markedDiscussion();
        JsonNode first = marked.history().path("statements").get(0);
        assertThat(first.path("body").asString()).isEqualTo(marked.body());
        assertThat(first.path("quotesHephaestusMarker").asBoolean()).isTrue();
        assertThat(first.path("origin").asString()).isEqualTo("UNKNOWN");
        assertThat(marked.privateTurn())
                .contains(marked.body())
                .containsPattern("\"quotesHephaestusMarker\"\\s*:\\s*true");
    }

    /** The pull-request scenario with a first comment that quotes a Hephaestus marker it did not write. */
    private record Marked(String body, JsonNode history, String privateTurn) {}

    private static Marked markedDiscussion() throws Exception {
        Path scenario = scenario("pull-request");
        var inputs = workspace(scenario.resolve("workspace"));
        JsonNode comments = MAPPER.readTree(Objects.requireNonNull(inputs.get("context/comments.json")));
        String body = comments.get(0).path("body").asString() + " <!-- hephaestus-diff-note -->";
        ((ObjectNode) comments.get(0)).put("quotesHephaestusMarker", true).put("body", body);
        inputs.put("context/comments.json", MAPPER.writeValueAsBytes(comments));
        var captured = new CapturedWork(new ComposerFiles(inputs), MAPPER);
        var history = captured.publicReviewHistory(
                json(scenario.resolve("workspace/INDEX.json")), new CapturedWork.Framing(null, null));
        var state = Admission.of(json(scenario.resolve("admission.json")), Map.of());
        var request = PrivateFeedback.Request.of(
                        MAPPER.readTree(Objects.requireNonNull(inputs.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH))))
                .orElseThrow();
        var routes = PrivateFeedback.closeUnrouted(request, state, new ArrayList<>());
        return new Marked(
                body,
                history,
                CompositionSessions.privateTurn(
                        new ComposerFiles(inputs), captured, request, state, null, routes, List.of()));
    }

    /** What the composer renders for one scenario. A turn the scenario does not open is null. */
    private record Rendered(
            Map<String, byte[]> inputs,
            JsonNode admitted,
            JsonNode privateHistory,
            @Nullable String reviewTurn,
            @Nullable JsonNode reviewTool,
            String brief,
            String feedbackTurn,
            JsonNode feedbackTool) {}

    private static Rendered render(String name) throws Exception {
        Path scenario = scenario(name);
        var inputs = workspace(scenario.resolve("workspace"));
        JsonNode response = json(scenario.resolve("admission.json"));
        var decided = new HashSet<String>();
        response.path("privateProgress").path("decidedResultIds").forEach(id -> decided.add(id.asString()));
        var state = Admission.of(response, Map.of("PRACTICE_PAGE", decided, "CONVERSATION", decided));
        byte[] requestBytes = Objects.requireNonNull(inputs.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH));
        var request = PrivateFeedback.Request.of(MAPPER.readTree(requestBytes)).orElseThrow();
        var files = CompositionSessions.workspace(inputs, requestBytes, state);
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, MAPPER).orElseThrow();
        JsonNode practiceIndex =
                files.json(ComposerFiles.PRACTICE_INDEX, MAPPER).orElseThrow();
        var notReached = CompositionSessions.notReached(practiceIndex, state);
        var captured = new CapturedWork(files, MAPPER);

        String reviewTurn = null;
        JsonNode reviewTool = null;
        var reviewable = ReviewOnWork.publicResults(state.results());
        if (request.open("ON_WORK") && !state.publicReviewed() && !reviewable.isEmpty()) {
            var turn = CompositionSessions.reviewTurn(
                    files,
                    captured,
                    folderIndex,
                    practiceIndex,
                    state,
                    request,
                    json(scenario.resolve("public-history.json")),
                    notReached,
                    reader(json(scenario.resolve("reader.json"))));
            reviewTurn = turn.text();
            reviewTool = turn.toolParameters();
        }
        ReviewOnWork.ComposedReview review = null;
        Path reviewFile = scenario.resolve("review.json");
        if (Files.exists(reviewFile)) review = review(json(reviewFile));
        var routes = PrivateFeedback.closeUnrouted(request, state, new ArrayList<>());
        PrivateFeedback.stageHistory(state.privateHistory(), files);
        return new Rendered(
                inputs,
                response.path("admitted"),
                response.path("privateHistory"),
                reviewTurn,
                reviewTool,
                captured.brief(),
                PrivateFeedback.turn(files, MAPPER, request, state, review, routes, notReached),
                PrivateFeedback.toolParameters(CompositionSessions.practiceSlugs(practiceIndex), request));
    }

    /** The tool's structure equals the scenario's file: names, types, required fields and allowed values. */
    private static void structure(String scenario, String file, JsonNode rendered) throws Exception {
        JsonNode actual = withoutDescriptions(rendered.deepCopy());
        JsonNode expected = json(scenario(scenario).resolve(file));
        if (UPDATE && !actual.equals(expected)) {
            Files.writeString(
                    SOURCE.resolve(scenario).resolve(file),
                    JsonText.stringify(actual, 2) + "\n",
                    StandardCharsets.UTF_8);
            // A Gradle daemon can keep the variable after the build that set it, so a rewrite never passes.
            fail(scenario + "/" + file
                    + " was rewritten: review the diff, then run again without UPDATE_COMPOSITION_TOOLS");
        }
        assertThat(actual).as(scenario + "/" + file).isEqualTo(expected);
    }

    private static JsonNode withoutDescriptions(JsonNode node) {
        if (node instanceof ObjectNode object) object.remove("description");
        node.forEach(ComposerTurnsTest::withoutDescriptions);
        return node;
    }

    /** Every field name and every text value in the node, at any depth. */
    private static void vocabulary(JsonNode node, Set<String> known) {
        if (node.isObject())
            node.properties().forEach(entry -> {
                known.add(entry.getKey());
                vocabulary(entry.getValue(), known);
            });
        else if (node.isArray()) node.forEach(child -> vocabulary(child, known));
        else if (node.isString()) known.add(node.asString());
    }

    /** The JSON blocks of a turn. A captured file's block carries a {@code [L<n>] } prefix on each line. */
    private static List<JsonNode> blocks(String text) {
        var blocks = new ArrayList<JsonNode>();
        var matcher = JSON_BLOCK.matcher(text);
        while (matcher.find())
            blocks.add(MAPPER.readTree(LINE_NUMBER.matcher(matcher.group(1)).replaceAll("")));
        return blocks;
    }

    /** Each public-discussion source the review turn lists, and whether it says the source is omitted. */
    private static Map<String, Boolean> omittedSources(String turn) {
        var omitted = new TreeMap<String, Boolean>();
        for (JsonNode block : blocks(turn))
            for (JsonNode source : block.path("sources"))
                if (source.has("omitted"))
                    omitted.put(
                            source.path("path").asString(),
                            !source.path("omitted").isNull());
        return omitted;
    }

    private static Path scenario(String name) throws URISyntaxException {
        var url = Objects.requireNonNull(
                ComposerTurnsTest.class.getResource("/agent/golden-prompts/composition-turns/" + name));
        return Path.of(url.toURI());
    }

    private static Map<String, byte[]> workspace(Path root) throws IOException {
        var files = new TreeMap<String, byte[]>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList())
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return files;
    }

    private static JsonNode json(Path file) throws IOException {
        return MAPPER.readTree(Files.readAllBytes(file));
    }

    private static ReviewOnWork.ComposedReview review(JsonNode review) {
        JsonNode summary = review.path("summary");
        var inline = new ArrayList<ReviewOnWork.InlineNote>();
        for (JsonNode note : review.path("inline"))
            inline.add(new ReviewOnWork.InlineNote(
                    note.path("body").asString(),
                    refs(note.path("basedOn")),
                    new ReviewOnWork.Anchor(
                            note.path("anchor").path("problemId").asString(),
                            note.path("anchor").path("citationIndex").asInt())));
        var withheld = new ArrayList<ReviewOnWork.Withheld>();
        for (JsonNode decision : review.path("withheld"))
            withheld.add(new ReviewOnWork.Withheld(
                    refs(decision.path("basedOn")),
                    decision.path("reason").asString(),
                    decision.path("witnessIds")
                            .valueStream()
                            .map(JsonNode::asString)
                            .toList()));
        return new ReviewOnWork.ComposedReview(
                summary.isObject()
                        ? new ReviewOnWork.Summary(summary.path("body").asString(), refs(summary.path("basedOn")))
                        : null,
                inline,
                withheld);
    }

    private static List<Evidence.Ref> refs(JsonNode refs) {
        var list = new ArrayList<Evidence.Ref>();
        for (JsonNode ref : refs)
            list.add(new Evidence.Ref(
                    ref.path("resultId").asString(),
                    ref.path("problemId").isString() ? ref.path("problemId").asString() : null));
        return list;
    }

    /** The scenario's stand-in for a checkout: what each pinned path holds, and the pinned change. */
    private static PrimarySource.Reader reader(JsonNode fixture) {
        return new PrimarySource.Reader() {
            @Override
            public PrimarySource.Blob blob(String commit, String file, int limit) {
                JsonNode blob = fixture.path("blobs").path(commit + ":" + file);
                return switch (blob.path("kind").asString("unreadable")) {
                    case "regular" -> {
                        byte[] bytes =
                                Base64.getDecoder().decode(blob.path("base64").asString());
                        yield bytes.length > limit
                                ? new PrimarySource.TooLarge(bytes.length)
                                : new PrimarySource.Regular(bytes);
                    }
                    case "tooLarge" ->
                        new PrimarySource.TooLarge(blob.path("size").asLong());
                    case "absent" -> PrimarySource.Missing.ABSENT;
                    case "nonregular" -> PrimarySource.Missing.NONREGULAR;
                    case "unsafe" -> PrimarySource.Missing.UNSAFE;
                    default -> PrimarySource.Missing.UNREADABLE;
                };
            }

            @Override
            public PrimarySource.Diff diff(String base, String head, int limit) {
                JsonNode diff = fixture.path("diff");
                return switch (diff.path("kind").asString()) {
                    case "available" -> {
                        var files = new ArrayList<PrimarySource.ChangedFile>();
                        for (JsonNode file : diff.path("files"))
                            files.add(new PrimarySource.ChangedFile(
                                    file.path("path").asString(),
                                    file.path("oldPath").isString()
                                            ? file.path("oldPath").asString()
                                            : null));
                        String text = diff.path("text").asString();
                        yield new PrimarySource.AvailableDiff(files, text, PrimarySource.diffSections(text));
                    }
                    case "tooLarge" -> PrimarySource.UnavailableDiff.TOO_LARGE;
                    case "invalidText" -> PrimarySource.UnavailableDiff.INVALID_TEXT;
                    default -> PrimarySource.UnavailableDiff.UNREADABLE;
                };
            }

            @Override
            public @Nullable String checkedOut() {
                return fixture.path("checkedOut").isString()
                        ? fixture.path("checkedOut").asString()
                        : null;
            }
        };
    }
}
