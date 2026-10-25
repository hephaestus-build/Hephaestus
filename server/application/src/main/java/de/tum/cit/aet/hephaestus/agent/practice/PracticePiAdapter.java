package de.tum.cit.aet.hephaestus.agent.practice;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import de.tum.cit.aet.hephaestus.agent.config.FrozenModel;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class PracticePiAdapter {

    private static final PracticeRunnerProfile PROFILE = new PracticeRunnerProfile();

    private final PiRuntimeFactory runtimeFactory;
    private final PiResultParser resultParser;
    private final AgentImageProperties imageProperties;
    private final PracticeReviewProperties reviewProperties;
    private final ObjectMapper objectMapper;

    /** The runner reads it and registers it as the model's sampling temperature. */
    static final String SAMPLING_TEMPERATURE_ENV = "LLM_SAMPLING_TEMPERATURE";

    /** The runner bounds each turn by these, per practice it carries: pi-runner.ts PER_PRACTICE_WORK. */
    static final String PRACTICE_MODEL_CALLS_ENV = "PI_PRACTICE_MODEL_CALLS";

    static final String PRACTICE_CONCURRENCY_ENV = "PI_PRACTICE_CONCURRENCY";

    static final String PRACTICE_OUTPUT_TOKENS_ENV = "PI_PRACTICE_OUTPUT_TOKENS";

    /**
     * The tokens the direct model calls of the attempt's practice programs may spend together: the runner holds its
     * programs to it, and the proxy refuses further direct calls past {@code direct-model-tokens-per-attempt}.
     */
    static final String DIRECT_MODEL_TOKENS_ENV = "PI_DIRECT_MODEL_TOKENS";

    /**
     * The feedback composition phases one launch runs. The review launch composes private feedback only; the review on
     * the work comes from a launch of its own whose sessions have no shell ({@link #publicReviewEnvironment}).
     */
    static final String COMPOSITION_PHASES_ENV = "PI_COMPOSITION_PHASES";

    /**
     * The shortest job timeout: more than the runner's shutdown buffer, so the runner has work time at all. No
     * program stage is reserved; the programs run inside the runner's own budget.
     */
    public static final int MIN_TIMEOUT_SECONDS = PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1;

    public PracticeSandboxSpec buildSandboxSpec(PracticeAgentRequest request) {
        Map<String, BoundModel> practiceScriptModels = boundModels(request.practiceScriptModels());
        Map<String, byte[]> extraInputs = practiceScriptModels.isEmpty()
                ? Map.of()
                : Map.of(SandboxLayout.PRACTICE_SCRIPT_MODELS_FILE, toJson(practiceScriptModels));
        PiRuntimeFactory.PiPlan plan = runtimeFactory.build(new PiPlanSpec(
                request.apiProtocol(),
                request.upstreamModelId(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.reasoningEffort(),
                request.jobToken(),
                request.timeoutSeconds(),
                PROFILE,
                extraInputs));
        Map<String, String> environment = new LinkedHashMap<>(plan.environment());
        environment.put(PRACTICE_MODEL_CALLS_ENV, Integer.toString(reviewProperties.practiceModelCalls()));
        environment.put(PRACTICE_OUTPUT_TOKENS_ENV, Integer.toString(reviewProperties.practiceOutputTokens()));
        environment.put(PRACTICE_CONCURRENCY_ENV, Integer.toString(reviewProperties.practiceConcurrency()));
        environment.put(DIRECT_MODEL_TOKENS_ENV, Long.toString(reviewProperties.directModelTokensPerAttempt()));
        environment.put(COMPOSITION_PHASES_ENV, "PRIVATE_FEEDBACK");
        Double temperature = reviewProperties.samplingTemperature();
        if (temperature != null) {
            environment.put(SAMPLING_TEMPERATURE_ENV, temperature.toString());
        }
        return new PracticeSandboxSpec(
                imageProperties.reference(),
                plan.command(),
                Map.copyOf(environment),
                plan.inputFiles(),
                plan.jobToken(),
                plan.promptDigest());
    }

    /**
     * A review launch's environment for the launch that composes only the review on the work, within the time the
     * attempt has left. With none left, its runner records that review as stopped, so a later attempt composes it.
     */
    public static Map<String, String> publicReviewEnvironment(Map<String, String> review, Duration left) {
        Map<String, String> environment = new LinkedHashMap<>(withTimeLeft(review, left));
        environment.put(COMPOSITION_PHASES_ENV, "PUBLIC_REVIEW");
        return Map.copyOf(environment);
    }

    /** A launch's environment whose agent budget is the time the attempt has left, less the runner's buffer. */
    public static Map<String, String> withTimeLeft(Map<String, String> launch, Duration left) {
        Map<String, String> environment = new LinkedHashMap<>(launch);
        long budget = left.minusSeconds(PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS).toMillis();
        environment.put(PiRuntimeFactory.AGENT_BUDGET_ENV, Long.toString(Math.max(1, budget)));
        return Map.copyOf(environment);
    }

    public AgentResult parseResult(SandboxResult sandboxResult) {
        return resultParser.parse(sandboxResult);
    }

    /** One entry of {@link SandboxLayout#PRACTICE_SCRIPT_MODELS_FILE}: the toolkit's {@code boundModelSchema}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BoundModel(
            String protocol, String modelId, @Nullable ReasoningEffort reasoningEffort) {}

    private static Map<String, BoundModel> boundModels(Map<ModelKind, FrozenModel> models) {
        Map<String, BoundModel> bound = new LinkedHashMap<>();
        models.forEach((kind, model) -> bound.put(
                kind.name().toLowerCase(Locale.ROOT),
                new BoundModel(model.apiProtocol().wire(), model.upstreamModelId(), model.reasoningEffort())));
        return Map.copyOf(bound);
    }

    private byte[] toJson(Map<String, BoundModel> models) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(models);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + SandboxLayout.PRACTICE_SCRIPT_MODELS_FILE, e);
        }
    }
}
