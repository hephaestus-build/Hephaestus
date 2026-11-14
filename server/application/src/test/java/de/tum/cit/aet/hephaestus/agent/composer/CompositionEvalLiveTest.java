package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.JSON;
import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Composes every labelled composition case with a live model through the composer that production runs: the Node
 * {@code review_compose} loop ({@code review-composer.ts}) over the real composer sessions, which render its prompts,
 * check each draft and accept its publication ({@link CompositionEvalCases#compose}). It writes each case's published
 * review, or its last checked one, to {@code COMPOSITION_EVAL_CHECKED} after each case. A rerun with the same model
 * settings and requests resumes from that file. The eval stops at preparation, before approval and delivery. The test
 * asserts nothing about semantic quality: the release-eval script scores the file against the labels.
 */
@LiveLlmTest
class CompositionEvalLiveTest {

    @Test
    void shouldComposeEveryCaseWithTheLiveModel() throws Exception {
        String checkedFile = Objects.requireNonNull(
                System.getenv("COMPOSITION_EVAL_CHECKED"), "COMPOSITION_EVAL_CHECKED names the result file");
        var creds = LiveLlmCredentials.fromEnv();
        String effort = Objects.requireNonNullElse(System.getenv("COMPOSITION_EVAL_REASONING_EFFORT"), "MEDIUM");
        int maxTokens = Integer.parseInt(
                Objects.requireNonNullElse(System.getenv("COMPOSITION_EVAL_MAX_OUTPUT_TOKENS"), "32768"));
        var reasoning = ReasoningEffort.valueOf(effort);
        var transport = new LiveChatTransport(creds);
        var sourceScenarios = CompositionEvalCases.load();
        var scenarios = new ArrayList<LiveCase>();
        for (var scenario : sourceScenarios)
            scenarios.add(new LiveCase(
                    scenario.id(),
                    scenario.turn().text(),
                    scenario.turn().toolParameters(),
                    ReviewOnWork.TOOL_DESCRIPTION,
                    CompositionEvalCases.system(),
                    CompositionEvalCases.basis(scenario, reasoning, maxTokens),
                    FeedbackDestination.ON_WORK,
                    scenario::fromModel));
        for (var scenario : CompositionEvalCases.privateScenarios(sourceScenarios))
            scenarios.add(new LiveCase(
                    scenario.id(),
                    scenario.turn(),
                    scenario.toolParameters(),
                    PrivateFeedback.TOOL_DESCRIPTION,
                    CompositionSessions.sharedStyle() + "\n\n" + CompositionSessions.prompt("feedback-composer.md"),
                    CompositionEvalCases.basis(scenario, reasoning, maxTokens),
                    FeedbackDestination.valueOf(scenario.destination()),
                    UnaryOperator.identity()));
        String only = System.getenv("COMPOSITION_EVAL_CASES");

        var model = JSON.objectNode()
                .put("id", creds.model())
                .put("reasoningEffort", effort)
                .put("maxOutputTokens", maxTokens)
                .put(
                        "endpointSha256",
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256")
                                        .digest(creds.baseUrl()
                                                .replaceAll("/+$", "")
                                                .getBytes(StandardCharsets.UTF_8))));
        String requests = requestsDigest(scenarios);
        var revision = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        String commit = new String(revision.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertThat(revision.waitFor()).isZero();
        var file = JSON.objectNode().put("version", 1).put("commit", commit);
        file.set("model", model);
        file.put("requestsSha256", requests);
        file.put("runner", "CompositionEvalLiveTest (the review_compose Node loop, OPENAI_COMPLETIONS)");
        var cases = file.putArray("cases");
        var privateCases = file.putArray("privateCases");
        var done = new HashSet<String>();
        Path existing = Path.of(checkedFile);
        if (Files.exists(existing)) {
            JsonNode earlier = MAPPER.readTree(Files.readAllBytes(existing));
            // The liveTest task stops after 20 minutes. A rerun resumes only the same measurement.
            assertThat(earlier.path("commit").asString()).isEqualTo(commit);
            assertThat(earlier.path("model"))
                    .as("%s holds another model's run; use another file", checkedFile)
                    .isEqualTo(model);
            assertThat(earlier.path("requestsSha256").asString())
                    .as("%s was composed from other requests; use another file", checkedFile)
                    .isEqualTo(requests);
            var earlierCases = new ArrayList<JsonNode>();
            earlier.path("cases").forEach(earlierCases::add);
            earlier.path("privateCases").forEach(earlierCases::add);
            for (JsonNode entry : earlierCases) {
                // An endpoint failure measures the endpoint, not the model, so the rerun composes that case again.
                var failure = entry.path("failure");
                if (failure.isString()
                        && List.of("PROVIDER", "CAPACITY").contains(failure.asString())
                        && (only == null
                                || List.of(only.split(",", -1))
                                        .contains(entry.path("id").asString()))) continue;
                (entry.path("id").asString().contains("/") ? privateCases : cases).add(entry);
                done.add(entry.path("id").asString());
            }
        }
        for (var scenario : scenarios) {
            if (only != null && !List.of(only.split(",", -1)).contains(scenario.id())) continue;
            if (done.contains(scenario.id())) continue;
            transport.startCase();
            var entry = CompositionEvalCases.compose(
                    scenario.id(),
                    scenario.basis(),
                    scenario.destination(),
                    scenario.output(),
                    transport.answering(creds.model(), new ArrayList<>()));
            // Every HTTP request counts toward the gate's call ceiling.
            entry.put("requests", transport.requests());
            entry.set(
                    "servedModels",
                    CompositionEvalCases.strings(
                            transport.servedModels().stream().sorted().toList()));
            (scenario.id().contains("/") ? privateCases : cases).add(entry);
            CompositionEvalCases.write(checkedFile, file);
        }
        var selected = scenarios.stream()
                .filter(scenario -> only == null || List.of(only.split(",", -1)).contains(scenario.id()))
                .map(LiveCase::id)
                .toList();
        var completed = new ArrayList<JsonNode>();
        cases.forEach(completed::add);
        privateCases.forEach(completed::add);
        completed.removeIf(entry -> !selected.contains(entry.path("id").asString()));
        assertThat(completed).hasSize(selected.size());
        assertThat(completed).isNotEmpty().allSatisfy(entry -> {
            assertThat(entry.path("failure").isNull())
                    .as("%s completed", entry.path("id").asString())
                    .isTrue();
            assertThat(entry.path("accepted").asBoolean())
                    .as("%s accepted", entry.path("id").asString())
                    .isTrue();
        });
    }

    /**
     * One eval case: what its first request renders, for the digest that guards a resumed file, and what the composer
     * loop composes it from.
     */
    private record LiveCase(
            String id,
            String turn,
            ObjectNode parameters,
            String description,
            String system,
            ComposerReplay.Basis basis,
            FeedbackDestination destination,
            UnaryOperator<JsonNode> output) {}

    /** One digest over every rendered request, so a resumed file belongs to the same prompts, criteria and cases. */
    private static String requestsDigest(List<LiveCase> scenarios) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var scenario : scenarios) {
            var request = JSON.objectNode()
                    .put("id", scenario.id())
                    .put("system", scenario.system())
                    .put("turn", scenario.turn())
                    .put("description", scenario.description());
            request.set("parameters", scenario.parameters());
            digest.update(request.toString().getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
