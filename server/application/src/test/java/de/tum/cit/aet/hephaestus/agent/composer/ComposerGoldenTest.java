package de.tum.cit.aet.hephaestus.agent.composer;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.TreeMap;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Each scenario's turns and tool schemas equal the golden files beside its inputs. Run with
 * {@code UPDATE_COMPOSITION_GOLDENS=1} to rewrite the golden files from what the composer renders, then review the diff.
 */
@Tag("unit")
class ComposerGoldenTest {
    private static final boolean UPDATE = "1".equals(System.getenv("UPDATE_COMPOSITION_GOLDENS"));
    /** The source copy of the scenarios, from the module directory that Gradle runs tests in. */
    private static final Path SOURCE = Path.of("src/test/resources/agent/golden-prompts/composition-turns");

    private final JsonMapper mapper = new JsonMapper();

    @ParameterizedTest
    @ValueSource(strings = {"pull-request", "issue", "resumed", "oversized"})
    void shouldRenderTheGoldenTurnsAndToolsOfEachScenario(String name) throws Exception {
        Path scenario = scenario(name);
        var inputs = workspace(scenario.resolve("workspace"));
        JsonNode response = json(scenario.resolve("admission.json"));
        var decided = new HashSet<String>();
        response.path("privateProgress").path("decidedResultIds").forEach(id -> decided.add(id.asString()));
        var state = Admission.of(response, Map.of("PRACTICE_PAGE", decided, "CONVERSATION", decided));
        byte[] requestBytes = Objects.requireNonNull(inputs.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH));
        var request = PrivateFeedback.Request.of(mapper.readTree(requestBytes)).orElseThrow();
        var files = WorkerFeedbackComposer.workspace(inputs, requestBytes, state);
        JsonNode folderIndex = files.json(SandboxLayout.MANIFEST_PATH, mapper).orElseThrow();
        JsonNode practiceIndex =
                files.json(ComposerFiles.PRACTICE_INDEX, mapper).orElseThrow();
        var notReached = WorkerFeedbackComposer.notReached(practiceIndex, state);
        var captured = new CapturedWork(files, mapper);

        var reviewable = ReviewOnWork.publicResults(state.results());
        if (request.open("ON_WORK") && !state.publicReviewed() && !reviewable.isEmpty()) {
            var turn = WorkerFeedbackComposer.reviewTurn(
                    files,
                    captured,
                    folderIndex,
                    practiceIndex,
                    state,
                    request,
                    json(scenario.resolve("public-history.json")),
                    notReached,
                    reader(json(scenario.resolve("reader.json"))));
            golden(name, "review-turn.md", turn.text());
            golden(name, "review-tool.json", JsonText.stringify(turn.toolParameters(), 2) + "\n");
        } else {
            assertThat(scenario.resolve("review-turn.md")).doesNotExist();
        }

        ReviewOnWork.ComposedReview review = null;
        Path reviewFile = scenario.resolve("review.json");
        if (Files.exists(reviewFile)) review = review(json(reviewFile));
        var routes = PrivateFeedback.closeUnrouted(request, state, new ArrayList<>());
        PrivateFeedback.stageHistory(state.privateHistory(), files);
        golden(name, "brief.md", captured.brief());
        golden(
                name,
                "feedback-turn.md",
                PrivateFeedback.turn(files, mapper, request, state, review, routes, notReached));
        golden(
                name,
                "feedback-tool.json",
                JsonText.stringify(
                                PrivateFeedback.toolParameters(
                                        WorkerFeedbackComposer.practiceSlugs(practiceIndex), request),
                                2)
                        + "\n");
    }

    private static void golden(String scenario, String file, String rendered) throws IOException, URISyntaxException {
        if (UPDATE) Files.writeString(SOURCE.resolve(scenario).resolve(file), rendered, StandardCharsets.UTF_8);
        else assertThat(rendered).isEqualTo(text(scenario(scenario).resolve(file)));
    }

    private static Path scenario(String name) throws URISyntaxException {
        var url = Objects.requireNonNull(
                ComposerGoldenTest.class.getResource("/agent/golden-prompts/composition-turns/" + name));
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

    private JsonNode json(Path file) throws IOException {
        return mapper.readTree(Files.readAllBytes(file));
    }

    private static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
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
