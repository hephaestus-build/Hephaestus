package de.tum.cit.aet.hephaestus.agent.composer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentCapacityProperties;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.context.PublicFeedbackSnapshot;
import de.tum.cit.aet.hephaestus.agent.handler.ResultAdmissionService;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.handler.composition.ComposedFeedback;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackPackage;
import de.tum.cit.aet.hephaestus.agent.job.CompositionToolTrace;
import de.tum.cit.aet.hephaestus.agent.job.ReviewCompositionContinuation;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobAssignments;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmissionResponseDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.AdmittedResultDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.ConversationEligibilityDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PracticePageSupportDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateFeedbackHistoryDTO;
import de.tum.cit.aet.hephaestus.agent.proxy.dto.PrivateProgressDTO;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCompositionTrace;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepair;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackRepairStatus;
import de.tum.cit.aet.hephaestus.practices.spi.PracticePageSupport;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedFeedback;
import de.tum.cit.aet.hephaestus.practices.spi.PreparedPackage;
import de.tum.cit.aet.hephaestus.practices.spi.PrivateFeedbackProgress;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.mockito.Answers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replays one composition case: a sealed review basis from the golden scenarios, and the model's answers, scripted or
 * recorded from a live model. The outcome is what the server admitted: every package that reached preparation, the
 * stops the review on the work recorded, the cause that handed the attempt on, and what each model call added to its
 * conversation.
 *
 * <p>The server side is the real composer sessions over an in-memory result admission. A case file holds no Java, so a
 * composer loop in another language can replay it against the same harness. A scripted
 * answer is {@code {"tool": name, "arguments": {...}}}, {@code {"text": "..."}} or {@code {"status": 503}}; in a tool's
 * arguments, {@code ${draftId}} stands for the draft ID that the last check returned. A recorded answer is
 * {@code {"body": <the provider's chat completion>}}. After the last answer the model says it is done.
 */
final class ComposerReplay {
    static final JsonMapper MAPPER = new JsonMapper();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    /** The source copies, from the module directory that Gradle runs tests in. */
    static final Path CASES = Path.of("src/test/resources/agent/composition-replay");

    private static final Path SCENARIOS = Path.of("src/test/resources/agent/golden-prompts/composition-turns");
    private static final UUID RUN = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");
    private static final Instant PREPARED_AT = Instant.parse("2026-11-09T12:00:00Z");
    /** A user turn longer than this is recorded by its digest: the golden scenarios hold the rendered text. */
    private static final int SHOWN_TEXT = 2_000;

    private ComposerReplay() {}

    /**
     * What a replay admitted.
     *
     * @param decisions what each tool call decided, as the server's tool trace keeps it; not part of the replayed outcome
     */
    record Outcome(
            JsonNode packages,
            JsonNode reviewStops,
            @Nullable String handedOn,
            JsonNode calls,
            List<CompositionToolTrace.Decision> decisions) {
        ObjectNode toJson() {
            var node = JSON.objectNode();
            node.set("packages", packages);
            node.set("reviewStops", reviewStops);
            if (handedOn == null) node.putNull("handedOn");
            else node.put("handedOn", handedOn);
            node.set("calls", calls);
            return node;
        }
    }

    static List<String> names() throws IOException {
        try (Stream<Path> files = Files.list(CASES)) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .sorted()
                    .toList();
        }
    }

    static ObjectNode load(String name) throws IOException {
        return (ObjectNode) MAPPER.readTree(Files.readAllBytes(CASES.resolve(name + ".json")));
    }

    static void write(String name, ObjectNode replay) throws IOException {
        Files.writeString(
                CASES.resolve(name + ".json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(replay) + "\n",
                StandardCharsets.UTF_8);
    }

    /**
     * A sealed review as the composer's server side reads it.
     *
     * @param inputs the retained inputs the sessions render from
     * @param admission the admission response, in its JSON shape
     * @param history the public feedback history of the work; null when the work has none
     * @param capabilities the copy forms the item supports, which open the destinations
     * @param reviewedEarlier whether an earlier attempt already decided the review on the work
     * @param earlierDecisions what an earlier attempt's prepared rows decide, by destination
     * @param reasoningEffort the model's reasoning effort, as the job froze it
     * @param maxOutputTokens the model's output bound, as the job froze it
     */
    record Basis(
            Map<String, byte[]> inputs,
            JsonNode admission,
            @Nullable JsonNode history,
            Set<CopyCapability> capabilities,
            boolean reviewedEarlier,
            Map<FeedbackDestination, Set<UUID>> earlierDecisions,
            @Nullable ReasoningEffort reasoningEffort,
            int maxOutputTokens) {}

    /** The basis that a case file names: a golden scenario, the item's copy forms and what was decided earlier. */
    static Basis basis(JsonNode replay) throws IOException {
        Path scenario = SCENARIOS.resolve(replay.path("scenario").asString());
        var capabilities = new HashSet<CopyCapability>();
        replay.path("copyCapabilities").forEach(name -> capabilities.add(CopyCapability.valueOf(name.asString())));
        var earlier = new EnumMap<FeedbackDestination, Set<UUID>>(FeedbackDestination.class);
        replay.path("earlierDecisions").properties().forEach(entry -> {
            var ids = new HashSet<UUID>();
            entry.getValue().forEach(id -> ids.add(UUID.fromString(id.asString())));
            earlier.put(FeedbackDestination.valueOf(entry.getKey()), Set.copyOf(ids));
        });
        Path history = scenario.resolve("public-history.json");
        return new Basis(
                workspace(scenario.resolve("workspace")),
                MAPPER.readTree(Files.readAllBytes(scenario.resolve("admission.json"))),
                Files.exists(history) ? MAPPER.readTree(Files.readAllBytes(history)) : null,
                Set.copyOf(capabilities),
                replay.path("reviewedEarlier").asBoolean(false),
                earlier,
                null,
                4_000);
    }

    /** The Node composer driver that replays a case against a loopback gateway, run from the module directory. */
    private static final Path NODE_DRIVER = Path.of("src/test/resources/agent/review-composer-replay.ts");

    /**
     * Runs the Node composer loop of a {@code review_compose} run on the case, in its own process, against the same
     * server side over a loopback gateway, with each model call answered by {@code model}.
     */
    static Outcome node(JsonNode replay, Function<byte[], ModelSession.Answer> model)
            throws IOException, InterruptedException {
        return node(basis(replay), model);
    }

    /** Runs the Node composer loop on a sealed basis, with each model call answered by {@code model}. */
    static Outcome node(Basis basis, Function<byte[], ModelSession.Answer> model)
            throws IOException, InterruptedException {
        var state = new Server(basis);
        var sessions = state.sessions();
        var calls = JSON.arrayNode();
        var previous = new ArrayList<JsonNode>();
        Function<byte[], ModelSession.Answer> recorded = body -> {
            calls.add(delta(MAPPER.readTree(body), previous));
            return model.apply(body);
        };
        var identity = new ResultAdmissionService.AdmissionIdentity(
                state.job.getId(), state.job.getWorkspaceId(), state.job.getAttempt(), RUN);
        try (var gateway = new ComposerReplayGateway(
                MAPPER,
                sessions,
                state.job,
                identity,
                sessions.deadline(Instant.now().plus(Duration.ofHours(1))),
                recorded)) {
            var process = new ProcessBuilder("node", NODE_DRIVER.toString(), gateway.url())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            byte[] printed = process.getInputStream().readAllBytes();
            if (!process.waitFor(2, TimeUnit.MINUTES) || process.exitValue() != 0)
                throw new IllegalStateException("The Node composer did not finish: exit " + process.exitValue());
            JsonNode outcome = MAPPER.readTree(printed);
            var packages = JSON.arrayNode();
            state.accepted.forEach(accepted -> packages.add(MAPPER.valueToTree(accepted)));
            var stops = JSON.arrayNode();
            state.reviewStops.forEach(stops::add);
            return new Outcome(
                    packages, stops, outcome.path("handedOn").asString(null), calls, state.trace.decisions(identity));
        }
    }

    /** Answers each call with the case's next answer. */
    static Function<byte[], ModelSession.Answer> replayed(JsonNode replay) {
        var answers = new ArrayList<JsonNode>();
        replay.path("responses").forEach(answers::add);
        var next = new int[] {0};
        return body -> {
            var request = MAPPER.readTree(body);
            if (next[0] >= answers.size()) return completion(text("Done."));
            return answer(answers.get(next[0]++), lastDraftId(request));
        };
    }

    private static ModelSession.Answer answer(JsonNode answer, @Nullable String draftId) {
        if (answer.has("status"))
            return new ModelSession.Answer(answer.path("status").asInt(), new byte[0], true, null);
        if (answer.has("body")) return completion(answer.path("body"));
        if (answer.has("text")) return completion(text(answer.path("text").asString()));
        String arguments = answer.path("arguments").toString();
        if (draftId != null) arguments = arguments.replace("${draftId}", draftId);
        var body = JSON.objectNode();
        var message = body.putArray("choices").addObject().putObject("message");
        message.put("role", "assistant").putNull("content");
        var call = message.putArray("tool_calls").addObject();
        call.put(
                "id",
                "call-" + answer.path("tool").asString() + "-"
                        + ProvenanceDigest.sha256Hex(arguments.getBytes(StandardCharsets.UTF_8))
                                .substring(0, 8));
        call.put("type", "function");
        call.putObject("function").put("name", answer.path("tool").asString()).put("arguments", arguments);
        return completion(body);
    }

    private static ObjectNode text(String content) {
        var body = JSON.objectNode();
        body.putArray("choices")
                .addObject()
                .putObject("message")
                .put("role", "assistant")
                .put("content", content);
        return body;
    }

    private static ModelSession.Answer completion(JsonNode body) {
        return new ModelSession.Answer(200, body.toString().getBytes(StandardCharsets.UTF_8), true, null);
    }

    /** The draft ID the latest tool result in the request returned, if any. */
    private static @Nullable String lastDraftId(JsonNode request) {
        var messages = request.path("messages");
        for (int index = messages.size() - 1; index >= 0; index--) {
            var message = messages.get(index);
            if (!"tool".equals(message.path("role").asString())) continue;
            String content = message.path("content").asString("");
            if (!content.startsWith("{")) continue;
            var receipt = MAPPER.readTree(content);
            if (receipt.hasNonNull("draftId") && receipt.path("checked").asBoolean())
                return receipt.path("draftId").asString();
        }
        return null;
    }

    /**
     * What one model call added to its conversation, protocol-neutral: a call that starts a session names the digests
     * of its system prompt and tools, and every message it did not send before.
     */
    private static ObjectNode delta(JsonNode request, List<JsonNode> previous) {
        var messages = new ArrayList<JsonNode>();
        request.path("messages").forEach(messages::add);
        var call = JSON.objectNode();
        boolean continues = previous.size() <= messages.size()
                && !previous.isEmpty()
                && previous.equals(messages.subList(0, previous.size()));
        int from = continues ? previous.size() : 0;
        if (!continues) {
            call.put("system", sha256(messages.getFirst().path("content").asString()));
            call.put("tools", sha256(request.path("tools").toString()));
            from = 1;
        }
        var added = call.putArray("messages");
        for (var message : messages.subList(from, messages.size())) added.add(neutral(message));
        previous.clear();
        previous.addAll(messages);
        return call;
    }

    private static ObjectNode neutral(JsonNode message) {
        var shown = JSON.objectNode();
        String role = message.path("role").asString();
        shown.put("role", role);
        if (message.path("tool_calls").isArray()) {
            var calls = shown.putArray("toolCalls");
            for (var call : message.path("tool_calls"))
                calls.addObject()
                        .put("name", call.path("function").path("name").asString())
                        .set(
                                "arguments",
                                MAPPER.readTree(
                                        call.path("function").path("arguments").asString()));
        }
        JsonNode content = message.path("content");
        if (content.isString()) {
            String text = content.asString();
            if (text.length() > SHOWN_TEXT)
                shown.putObject("text").put("sha256", sha256(text)).put("length", text.length());
            else shown.put("text", text);
        }
        return shown;
    }

    private static String sha256(String text) {
        return ProvenanceDigest.sha256Hex(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The server side of one case: the sealed basis, and what preparation accepted. */
    private static final class Server {
        final ResultAdmissionService admission = mock(ResultAdmissionService.class);
        final ReviewCompositionContinuation continuation = mock(ReviewCompositionContinuation.class);
        final ReviewJobAssignments assignments = mock(ReviewJobAssignments.class);
        final ItemDescriptorRegistry items = mock(ItemDescriptorRegistry.class);
        final FeedbackRepair repairs = mock(FeedbackRepair.class, call -> {
            if (call.getMethod().getReturnType() == FeedbackRepairStatus.class)
                return new FeedbackRepairStatus(null, FeedbackRepairStatus.State.STOPPED, 0, List.of());
            return Answers.RETURNS_DEFAULTS.answer(call);
        });
        final AgentCapacityProperties capacity = new AgentCapacityProperties(
                Duration.ofMinutes(5),
                Duration.ofSeconds(60),
                null,
                Duration.ofSeconds(25),
                5,
                Duration.ofMinutes(10),
                Duration.ofSeconds(30),
                Duration.ofMinutes(2),
                Duration.ofMinutes(1),
                Duration.ofMinutes(15));
        final PracticeReviewProperties properties =
                new PracticeReviewProperties(false, 15, 5, null, 12, 16000, 200_000, 1000, Duration.ofMinutes(10));
        final ReviewJob job = RuntimeReviewTestData.job();
        final List<FeedbackPackage> accepted = new ArrayList<>();
        final List<String> reviewStops = new ArrayList<>();
        final CompositionToolTrace trace = CompositionTraces.inMemory();
        private final Basis basis;

        Server(Basis basis) {
            this.basis = basis;
            RuntimeJobTestData.spent(RuntimeJobTestData.attempt(job, 0), 0);
            job.setAdmissionAttempt(0);
            job.setAdmissionDigest("a".repeat(64));
            job.setRunId(RUN);
            job.setConfigSnapshot(new ConfigSnapshot(
                            ConfigSnapshot.SCHEMA_VERSION,
                            new FrozenModel(
                                    LlmApiProtocol.OPENAI_COMPLETIONS,
                                    "https://model.example",
                                    "model",
                                    null,
                                    null,
                                    null,
                                    null,
                                    DataHandlingTier.UNDECLARED,
                                    basis.reasoningEffort(),
                                    null,
                                    false),
                            200_000,
                            basis.maxOutputTokens(),
                            600,
                            null)
                    .toJson(MAPPER));
            when(assignments.review(job))
                    .thenReturn(
                            RuntimeReviewTestData.review(ReviewKind.ITEM, RuntimeReviewTestData.assignment(5L, null)));
            var descriptor = mock(ItemDescriptor.class);
            when(descriptor.copyCapabilities()).thenReturn(basis.capabilities());
            when(items.descriptorFor(any())).thenReturn(Optional.of(descriptor));
            when(admission.privateProgress(job)).thenAnswer(invocation -> progress());
            when(admission.admittedResponse(job)).thenAnswer(invocation -> admissionResponse());
            JsonNode history = basis.history();
            if (history != null)
                when(admission.publicFeedbackHistory(any()))
                        .thenReturn(MAPPER.treeToValue(history, PublicFeedbackSnapshot.class));
            when(admission.publishPackage(any(), any())).thenAnswer(invocation -> accept(invocation.getArgument(1)));
            when(admission.preparedReview(job)).thenAnswer(invocation -> preparedReview());
            doAnswer(invocation -> {
                        reviewStops.add(invocation.getArgument(2));
                        return null;
                    })
                    .when(repairs)
                    .stop(anyLong(), any(), any());
            var inputs = new TreeMap<>(basis.inputs());
            inputs.remove(SandboxLayout.FEEDBACK_COMPOSITION_PATH);
            when(continuation.retainedInputs(job)).thenReturn(inputs);
        }

        CompositionSessions sessions() {
            return new CompositionSessions(
                    admission,
                    continuation,
                    assignments,
                    items,
                    properties,
                    capacity,
                    new SimpleMeterRegistry(),
                    MAPPER,
                    repairs,
                    trace,
                    Duration.ZERO);
        }

        private PreparedPackage accept(FeedbackPackage request) {
            accepted.add(request);
            int number = accepted.size();
            var id = UUID.nameUUIDFromBytes(("feedback-" + number).getBytes(StandardCharsets.UTF_8));
            return new PreparedPackage(
                    "digest-" + number,
                    PREPARED_AT,
                    List.of(new PreparedFeedback(id, "digest-" + number, PREPARED_AT)));
        }

        /** The review on the work as its prepared rows read back: the latest public package preparation accepted. */
        private Optional<ResultAdmissionService.PreparedReview> preparedReview() {
            var review = accepted.stream()
                    .filter(FeedbackPackage::publicComposition)
                    .reduce((earlier, later) -> later)
                    .map(accepted -> accepted.composition().getFirst())
                    .orElse(null);
            if (review == null) return Optional.empty();
            var copies = review.trace().copies();
            Function<Integer, List<FeedbackCompositionTrace.Support>> basis = position -> copies.stream()
                    .filter(copy -> copy.position() == position)
                    .findFirst()
                    .map(FeedbackCompositionTrace.CopySupport::basedOn)
                    .orElse(List.of());
            int first = review.summary() == null ? 0 : 1;
            var notes = new ArrayList<ResultAdmissionService.PreparedNote>();
            for (int index = 0; index < review.notes().size(); index++) {
                var note = review.notes().get(index);
                notes.add(new ResultAdmissionService.PreparedNote(
                        note.body(),
                        basis.apply(first + index),
                        new ComposedFeedback.Anchor(note.problemId(), note.citationIndex())));
            }
            var withheld = review.trace().withheld().stream()
                    .filter(decision -> !decision.reason().equals(FeedbackCompositionTrace.QuietDecision.NOT_COMPOSED))
                    .toList();
            if (review.summary() == null && notes.isEmpty() && withheld.isEmpty()) return Optional.empty();
            return Optional.of(new ResultAdmissionService.PreparedReview(
                    review.summary(), review.summary() == null ? List.of() : basis.apply(0), notes, withheld));
        }

        private PrivateFeedbackProgress progress() {
            var decided = new EnumMap<FeedbackDestination, Set<UUID>>(FeedbackDestination.class);
            decided.putAll(basis.earlierDecisions());
            for (var request : accepted)
                for (var piece : request.composition())
                    if (piece.destination() != FeedbackDestination.ON_WORK)
                        decided.merge(piece.destination(), Set.copyOf(piece.resultIds()), (left, right) -> {
                            var both = new HashSet<>(left);
                            both.addAll(right);
                            return both;
                        });
            var all = new HashSet<UUID>();
            decided.values().forEach(all::addAll);
            return new PrivateFeedbackProgress(all, 0, 0, decided);
        }

        private AdmissionResponseDTO admissionResponse() {
            JsonNode response = basis.admission();
            var admitted = new ArrayList<AdmittedResultDTO>();
            for (JsonNode result : response.path("admitted"))
                admitted.add(MAPPER.treeToValue(result, AdmittedResultDTO.class));
            JsonNode support = response.path("practicePageSupport");
            var practices = new ArrayList<PracticePageSupportDTO.PracticeSupportDTO>();
            for (JsonNode practice : support.path("practices"))
                practices.add(MAPPER.treeToValue(practice, PracticePageSupportDTO.PracticeSupportDTO.class));
            boolean publicReviewed =
                    basis.reviewedEarlier() || accepted.stream().anyMatch(FeedbackPackage::publicComposition);
            return new AdmissionResponseDTO(
                    AdmissionResponseDTO.CONTRACT_VERSION,
                    job.getReviewId(),
                    job.getId(),
                    0,
                    response.path("admissionDigest").asString(),
                    admitted,
                    List.of(),
                    support(support, practices),
                    MAPPER.treeToValue(response.path("conversationEligibility"), ConversationEligibilityDTO.class),
                    MAPPER.treeToValue(response.path("privateHistory"), PrivateFeedbackHistoryDTO.class),
                    publicReviewed,
                    new PrivateProgressDTO(List.of(), 0, 0));
        }
    }

    /** The scenario's practice page support in the state it declares. */
    private static @Nullable PracticePageSupportDTO support(
            JsonNode support, List<PracticePageSupportDTO.PracticeSupportDTO> practices) {
        if (support.isMissingNode()) return null;
        var state = PracticePageSupport.State.valueOf(support.path("state").asString());
        var readAt = Instant.parse(support.path("readAt").asString());
        return state == PracticePageSupport.State.COMPLETE
                ? new PracticePageSupportDTO.Complete(state, readAt, practices)
                : new PracticePageSupportDTO.Closed(
                        state, readAt, support.path("refusal").asString(null));
    }

    private static Map<String, byte[]> workspace(Path root) throws IOException {
        var files = new TreeMap<String, byte[]>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList())
                files.put(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
        }
        return files;
    }

    /**
     * Every answer the live model gave in a run, as a case records them: a failed call by its status, and of an answer
     * only what the composer reads, its message's role, text and tool calls. The endpoint's own fields, such as its model
     * name and metrics, stay out, and so do its tool call IDs, which the case numbers in order.
     */
    static ArrayNode recorded(List<ModelSession.Answer> sent) {
        var answers = JSON.arrayNode();
        int callNumber = 0;
        for (var answer : sent) {
            if (answer.status() != 200) {
                answers.addObject().put("status", answer.status());
                continue;
            }
            JsonNode message =
                    MAPPER.readTree(answer.body()).path("choices").path(0).path("message");
            var kept = JSON.objectNode().put("role", "assistant");
            kept.set("content", message.path("content").isString() ? message.path("content") : JSON.nullNode());
            var calls = JSON.arrayNode();
            for (JsonNode call : message.path("tool_calls"))
                calls.addObject()
                        .put("id", "call-" + ++callNumber)
                        .put("type", "function")
                        .putObject("function")
                        .put("name", call.path("function").path("name").asString())
                        .put(
                                "arguments",
                                call.path("function").path("arguments").asString());
            if (!calls.isEmpty()) kept.set("tool_calls", calls);
            answers.addObject()
                    .putObject("body")
                    .putArray("choices")
                    .addObject()
                    .set("message", kept);
        }
        return answers;
    }
}
