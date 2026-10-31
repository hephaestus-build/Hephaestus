package de.tum.cit.aet.hephaestus.agent.composer;

import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.JSON;
import static de.tum.cit.aet.hephaestus.agent.composer.CompositionEvalCases.MAPPER;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmCredentials;
import de.tum.cit.aet.hephaestus.testconfig.LiveLlmTest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Composes the review on the work for every labelled composition case with a live model, through main's
 * {@link ModelSession} and its system prompt, opening turn and report_review tool. The session follows
 * {@code WorkerFeedbackComposer}'s review session: the same call bound, one finish reminder, the nudge before the bound,
 * and main's answer to a refused review. It writes each case's final review and main's check of it to
 * {@code COMPOSITION_EVAL_CHECKED} after each case. A rerun with the same model settings and requests resumes from that
 * file. The test asserts nothing about quality: the feedback-eval script scores the file against the labels.
 */
@LiveLlmTest
class CompositionEvalLiveTest {
    /** {@code hephaestus.practice-review.practice-model-calls} at its default, as staging runs it. */
    private static final int PRACTICE_MODEL_CALLS = 12;

    private static final int PRACTICES_PER_TURN = 3;

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
        var transport = new Transport(creds);
        String system = CompositionEvalCases.system();
        var scenarios = CompositionEvalCases.load();
        String only = System.getenv("COMPOSITION_EVAL_CASES");

        var model = JSON.objectNode()
                .put("id", creds.model())
                .put("reasoningEffort", effort)
                .put("maxOutputTokens", maxTokens);
        String requests = requestsDigest(system, scenarios);
        var file = JSON.objectNode().put("version", 1);
        file.set("model", model);
        file.put("requestsSha256", requests);
        file.put("runner", "CompositionEvalLiveTest (main's ModelSession, OPENAI_COMPLETIONS)");
        var cases = file.putArray("cases");
        var done = new HashSet<String>();
        Path existing = Path.of(checkedFile);
        if (Files.exists(existing)) {
            JsonNode earlier = MAPPER.readTree(Files.readAllBytes(existing));
            // The liveTest task stops after 20 minutes. A rerun resumes only the same measurement.
            assertThat(earlier.path("model"))
                    .as("%s holds another model's run; use another file", checkedFile)
                    .isEqualTo(model);
            assertThat(earlier.path("requestsSha256").asString())
                    .as("%s was composed from other requests; use another file", checkedFile)
                    .isEqualTo(requests);
            for (JsonNode entry : earlier.path("cases")) {
                cases.add(entry);
                done.add(entry.path("id").asString());
            }
        }
        for (var scenario : scenarios) {
            if (only != null && !List.of(only.split(",", -1)).contains(scenario.id())) continue;
            if (done.contains(scenario.id())) continue;
            cases.add(compose(scenario, transport, settings, system));
            CompositionEvalCases.write(checkedFile, file);
        }
        assertThat(cases.size()).isPositive();
    }

    private static ObjectNode compose(
            CompositionEvalCases.Scenario scenario,
            ModelSession.Transport transport,
            ModelSession.Settings settings,
            String system) {
        var tool = new ModelSession.Tool(
                "report_review", ReviewOnWork.TOOL_DESCRIPTION, scenario.turn().toolParameters());
        var session = new ModelSession(transport, MAPPER, settings, system, List.of(tool));
        long notMet = scenario.turn().restable().values().stream()
                .filter(Evidence.Result::notMet)
                .count();
        int bound = PRACTICE_MODEL_CALLS * (int) Math.min(Math.max(1, notMet), PRACTICES_PER_TURN);
        long started = System.nanoTime();
        Boolean firstAccepted = null;
        JsonNode last = null;
        CompositionEvalCases.Checked checked = null;
        String failure = null;
        boolean askedOnceMore = false;
        boolean nudged = false;
        var reply = session.send(scenario.turn().text());
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
                reply = session.send(ReviewOnWork.finishText(
                        scenario.turn().restable().keySet().stream().toList()));
                continue;
            }
            var results = new ArrayList<ModelSession.ToolResult>();
            boolean stored = false;
            for (var call : calls) {
                if (!call.name().equals("report_review")) {
                    results.add(new ModelSession.ToolResult(
                            call.id(),
                            "There is no tool named " + call.name() + "; store the review with report_review."));
                    continue;
                }
                if (stored) {
                    results.add(new ModelSession.ToolResult(call.id(), "The review is already stored."));
                    continue;
                }
                last = call.arguments();
                checked = CompositionEvalCases.check(scenario, last);
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
                then = ReviewOnWork.NUDGE;
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
        if (last != null) entry.set("review", scenario.fromModel(ReviewOnWork.unwrapArguments(last)));
        else entry.putNull("review");
        return entry;
    }

    /** One digest over every rendered request, so a resumed file belongs to the same prompts, criteria and cases. */
    private static String requestsDigest(String system, List<CompositionEvalCases.Scenario> scenarios)
            throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(system.getBytes(StandardCharsets.UTF_8));
        for (var scenario : scenarios)
            digest.update(CompositionEvalCases.request(scenario).toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Sends main's request bytes to the endpoint, without the proxy that fences and bills them in production. */
    private static final class Transport implements ModelSession.Transport {
        private final HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        private final LiveLlmCredentials creds;
        private @Nullable String path;

        Transport(LiveLlmCredentials creds) {
            this.creds = creds;
        }

        @Override
        public ModelSession.Answer call(byte[] body) {
            String base = creds.baseUrl().replaceAll("/+$", "");
            // Endpoints differ in whether their base URL already ends with the API version.
            for (String candidate :
                    path == null ? List.of("/chat/completions", "/v1/chat/completions") : List.of(path)) {
                var answer = send(base + candidate, body);
                if (answer.status() != 404) {
                    path = candidate;
                    return answer;
                }
            }
            return new ModelSession.Answer(404, new byte[0], true, null);
        }

        private ModelSession.Answer send(String url, byte[] body) {
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(10))
                    .header("authorization", "Bearer " + creds.apiKey())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            try {
                var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                return new ModelSession.Answer(response.statusCode(), response.body(), true, null);
            } catch (IOException failed) {
                return new ModelSession.Answer(503, new byte[0], true, null);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new ModelSession.Answer(503, new byte[0], true, null);
            }
        }
    }
}
