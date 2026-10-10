package de.tum.cit.aet.hephaestus.agent.practice;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
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
import java.util.LinkedHashMap;
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

    static final String PRACTICE_OUTPUT_TOKENS_ENV = "PI_PRACTICE_OUTPUT_TOKENS";

    static final String PRECOMPUTE_TOKEN_ENV = "PRECOMPUTE_PROXY_TOKEN";

    /** The longest the precompute stage takes, whatever the job's timeout. */
    public static final int MAX_PRECOMPUTE_SECONDS = 90;

    /**
     * The shortest job timeout. It leaves the runner more than its shutdown buffer even after a precompute
     * stage of {@link #MAX_PRECOMPUTE_SECONDS}.
     */
    public static final int MIN_TIMEOUT_SECONDS = MAX_PRECOMPUTE_SECONDS + PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1;

    public PracticeSandboxSpec buildSandboxSpec(PracticeAgentRequest request) {
        int precomputeSeconds = precomputeBudgetSeconds(request.timeoutSeconds());
        String precomputeToken = request.precomputeToken();
        Map<String, BoundModel> precomputeModels = boundModels(request.precomputeModels());
        Map<String, byte[]> extraInputs = precomputeModels.isEmpty()
                ? Map.of()
                : Map.of(SandboxLayout.PRECOMPUTE_MODELS_FILE, toJson(precomputeModels));
        PiRuntimeFactory.PiPlan plan = runtimeFactory.build(new PiPlanSpec(
                request.apiProtocol(),
                request.upstreamModelId(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.reasoningEffort(),
                request.jobToken(),
                // Native shell access must not provide a route to push or fetch uncaptured evidence.
                false,
                // The runner starts after the precompute stage, so that stage's budget is not the runner's.
                request.timeoutSeconds() - precomputeSeconds,
                PROFILE,
                extraInputs,
                buildPrecomputeStep(precomputeSeconds, reviewProperties.precomputeMaxTokensPerAttempt())));
        Map<String, String> environment = new LinkedHashMap<>(plan.environment());
        environment.put(PRACTICE_MODEL_CALLS_ENV, Integer.toString(reviewProperties.practiceModelCalls()));
        environment.put(PRACTICE_OUTPUT_TOKENS_ENV, Integer.toString(reviewProperties.practiceOutputTokens()));
        Double temperature = reviewProperties.samplingTemperature();
        if (temperature != null) {
            environment.put(SAMPLING_TEMPERATURE_ENV, temperature.toString());
        }
        if (precomputeToken != null) {
            environment.put(PRECOMPUTE_TOKEN_ENV, precomputeToken);
        }
        return new PracticeSandboxSpec(
                imageProperties.reference(),
                plan.command(),
                Map.copyOf(environment),
                plan.inputFiles(),
                SandboxLayout.OUTPUT_PATH,
                null,
                plan.networkPolicy(),
                plan.promptDigest());
    }

    public AgentResult parseResult(SandboxResult sandboxResult) {
        return resultParser.parse(sandboxResult);
    }

    /** One entry of {@link SandboxLayout#PRECOMPUTE_MODELS_FILE}: the precompute runner's {@code boundModelSchema}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record BoundModel(
            String protocol, String modelId, @Nullable ReasoningEffort reasoningEffort) {}

    /**
     * The precompute models file, by slot. The runner rejects the whole file for one unknown protocol,
     * and a pre-catalog snapshot can hold one, so a model on such a protocol has no entry.
     */
    private static Map<String, BoundModel> boundModels(Map<ModelKind, FrozenModel> models) {
        Map<String, BoundModel> bound = new LinkedHashMap<>();
        models.forEach((kind, model) -> {
            if (LlmApiProtocol.parse(model.apiProtocol()).isPresent()) {
                bound.put(
                        kind.slot(),
                        new BoundModel(model.apiProtocol(), model.upstreamModelId(), model.reasoningEffort()));
            }
        });
        return bound;
    }

    private byte[] toJson(Map<String, BoundModel> models) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(models);
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize " + SandboxLayout.PRECOMPUTE_MODELS_FILE, e);
        }
    }

    /** One tenth of the job's timeout, from 1 second to {@link #MAX_PRECOMPUTE_SECONDS}. */
    static int precomputeBudgetSeconds(int timeoutSeconds) {
        return Math.min(MAX_PRECOMPUTE_SECONDS, Math.max(1, timeoutSeconds / 10));
    }

    /**
     * What runs before the model: the change view is derived from the checkout (a failure there is the
     * review's failure, since nothing could be reviewed), then the practices' precompute scripts, which
     * only add hints and may fail quietly. Only the precompute stage may call the precompute models, so
     * the review runner starts without their token. The precompute runner holds its scripts to the
     * proxy's token cap for the attempt.
     */
    private static String buildPrecomputeStep(int budgetSeconds, long maxTokens) {
        return "node /workspace/pi-change.ts && sh /workspace/pi-precompute.sh " + budgetSeconds + " " + maxTokens
                + " && unset " + PRECOMPUTE_TOKEN_ENV + " && ";
    }
}
