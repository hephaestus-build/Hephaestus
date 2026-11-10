package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.JSON;
import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Composes the review on the work for every labelled composition case with a live model, through main's
 * {@link ModelSession} and its system prompt, opening turn and check_package tool. The session follows the review
 * session of the composer loop ({@code review-composer.ts}): the same call bound, one finish reminder, the nudge before
 * the bound, and main's answer to a refused review. It writes each case's final review and main's check of it to
 * {@code COMPOSITION_EVAL_CHECKED} after each case. A rerun with the same model settings and requests resumes from that
 * file. The eval stops at validated drafts, before publication or delivery. The test asserts nothing about semantic
 * quality: the release-eval script scores the file against the labels.
 */
@LiveLlmTest
class CompositionEvalLiveTest {
    /** {@code hephaestus.practice-review.practice-model-calls} at its default, as staging runs it. */
    private static final int PRACTICE_MODEL_CALLS = 36;

    /** The composer's own bound, {@code MAX_COMPOSITION_CALLS}. */
    private static final int COMPOSITION_CALLS = 12;

    @Test
    void shouldComposeEveryCaseWithTheLiveModel() throws Exception {
        String checkedFile = Objects.requireNonNull(
                System.getenv("COMPOSITION_EVAL_CHECKED"), "COMPOSITION_EVAL_CHECKED names the result file");
        var creds = LiveLlmCredentials.fromEnv();
        String effort = Objects.requireNonNullElse(System.getenv("COMPOSITION_EVAL_REASONING_EFFORT"), "MEDIUM");
        int maxTokens = Integer.parseInt(
                Objects.requireNonNullElse(System.getenv("COMPOSITION_EVAL_MAX_OUTPUT_TOKENS"), "32768"));
        var settings = new ModelSession.Settings(
                LlmApiProtocol.OPENAI_COMPLETIONS, creds.model(), ReasoningEffort.valueOf(effort), maxTokens, null);
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
                    raw -> CompositionEvalCases.check(scenario, raw),
                    scenario::fromModel,
                    ReviewOnWork.finishText(
                            scenario.turn().restable().keySet().stream().toList()),
                    ReviewOnWork.NUDGE));
        for (var scenario : CompositionEvalCases.privateScenarios(sourceScenarios))
            scenarios.add(new LiveCase(
                    scenario.id(),
                    scenario.turn(),
                    scenario.toolParameters(),
                    PrivateFeedback.TOOL_DESCRIPTION,
                    CompositionSessions.sharedStyle() + "\n\n" + CompositionSessions.prompt("feedback-composer.md"),
                    scenario::check,
                    UnaryOperator.identity(),
                    "Decide every eligible practice with check_package.",
                    PrivateFeedback.NUDGE));
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
        file.put("runner", "CompositionEvalLiveTest (main's ModelSession, OPENAI_COMPLETIONS)");
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
            transport.resetServedModels();
            (scenario.id().contains("/") ? privateCases : cases).add(compose(scenario, transport, settings));
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

    private record LiveCase(
            String id,
            String turn,
            ObjectNode parameters,
            String description,
            String system,
            Function<JsonNode, CompositionEvalCases.Checked> check,
            UnaryOperator<JsonNode> output,
            String finish,
            String nudge) {}

    private static ObjectNode compose(
            LiveCase scenario, ModelSession.Transport transport, ModelSession.Settings settings) {
        var tool = new ModelSession.Tool("check_package", scenario.description(), scenario.parameters());
        var session = new ModelSession(transport, MAPPER, settings, scenario.system(), List.of(tool));
        int bound = Math.min(COMPOSITION_CALLS, PRACTICE_MODEL_CALLS);
        long started = System.nanoTime();
        Boolean firstAccepted = null;
        JsonNode last = null;
        CompositionEvalCases.Checked checked = null;
        String failure = null;
        boolean askedOnceMore = false;
        boolean nudged = false;
        var reply = session.send(scenario.turn());
        while (true) {
            boolean room = session.calls() < bound;
            var failed = reply.failure();
            if (failed != null) {
                if (failed == ModelSession.Failure.PROVIDER && room) {
                    reply = session.retry();
                    continue;
                }
                failure = failed.name();
                break;
            }
            var calls = reply.toolCalls();
            if (calls.isEmpty()) {
                if (!room || askedOnceMore) {
                    failure = "NO_DECISION";
                    break;
                }
                askedOnceMore = true;
                reply = session.send(scenario.finish());
                continue;
            }
            var results = new ArrayList<ModelSession.ToolResult>();
            boolean stored = false;
            for (var call : calls) {
                if (!call.name().equals("check_package")) {
                    results.add(new ModelSession.ToolResult(
                            call.id(),
                            "There is no tool named " + call.name() + "; check the review with check_package."));
                    continue;
                }
                if (stored) {
                    results.add(new ModelSession.ToolResult(call.id(), "The review is already stored."));
                    continue;
                }
                last = call.arguments();
                checked = scenario.check().apply(last);
                if (firstAccepted == null) firstAccepted = checked.accepted();
                stored = checked.accepted();
                results.add(new ModelSession.ToolResult(
                        call.id(), Objects.requireNonNullElse(checked.correction(), "The review is stored.")));
            }
            if (stored) break;
            if (!room) {
                failure = "OUT_OF_CALLS";
                break;
            }
            String then = null;
            if (!nudged && session.calls() >= bound - 2) {
                nudged = true;
                then = scenario.nudge();
            }
            reply = session.answer(results, then);
        }
        var entry = JSON.objectNode().put("id", scenario.id());
        entry.put("calls", session.calls());
        entry.put("elapsedMs", Duration.ofNanos(System.nanoTime() - started).toMillis());
        if (failure != null) entry.put("failure", failure);
        else entry.putNull("failure");
        if (firstAccepted != null) entry.put("firstAccepted", firstAccepted);
        else entry.putNull("firstAccepted");
        entry.put("accepted", checked != null && checked.accepted());
        entry.set("errors", CompositionEvalCases.strings(checked == null ? List.of() : checked.errors()));
        if (last != null) entry.set("review", scenario.output().apply(ReviewOnWork.unwrapArguments(last)));
        else entry.putNull("review");
        if (transport instanceof LiveChatTransport live)
            entry.set(
                    "servedModels",
                    CompositionEvalCases.strings(
                            live.servedModels().stream().sorted().toList()));
        return entry;
    }

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
