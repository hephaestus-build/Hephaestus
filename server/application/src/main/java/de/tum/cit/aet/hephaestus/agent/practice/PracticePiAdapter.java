package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PracticePiAdapter {

    private static final PracticeRunnerProfile PROFILE = new PracticeRunnerProfile();

    private final PiRuntimeFactory runtimeFactory;
    private final PiResultParser resultParser;
    private final AgentImageProperties imageProperties;
    private final PracticeReviewProperties reviewProperties;

    /** The runner reads it and registers it as the model's sampling temperature. */
    static final String SAMPLING_TEMPERATURE_ENV = "LLM_SAMPLING_TEMPERATURE";

    /** The runner bounds each turn by these, per practice it carries: pi-runner.ts PER_PRACTICE_WORK. */
    static final String PRACTICE_MODEL_CALLS_ENV = "PI_PRACTICE_MODEL_CALLS";

    static final String PRACTICE_OUTPUT_TOKENS_ENV = "PI_PRACTICE_OUTPUT_TOKENS";

    static final String PRACTICE_INPUT_TOKENS_ENV = "PI_PRACTICE_INPUT_TOKENS";

    /**
     * The shortest job timeout: more than the runner's shutdown buffer, so the runner has work time at all. No
     * program stage is reserved; the programs run inside the runner's own budget.
     */
    public static final int MIN_TIMEOUT_SECONDS = PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS + 1;

    public PracticeSandboxSpec buildSandboxSpec(PracticeAgentRequest request) {
        PiRuntimeFactory.PiPlan plan = runtimeFactory.build(new PiPlanSpec(
                request.apiProtocol(),
                request.upstreamModelId(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.reasoningEffort(),
                request.jobToken(),
                request.timeoutSeconds(),
                PROFILE,
                request.forcedToolChoice()));
        Map<String, String> environment = new LinkedHashMap<>(plan.environment());
        environment.put(PRACTICE_MODEL_CALLS_ENV, Integer.toString(reviewProperties.practiceModelCalls()));
        environment.put(PRACTICE_OUTPUT_TOKENS_ENV, Integer.toString(reviewProperties.practiceOutputTokens()));
        environment.put(PRACTICE_INPUT_TOKENS_ENV, Integer.toString(reviewProperties.practiceInputTokens()));
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

    public AgentResult parseResult(SandboxResult sandboxResult) {
        return resultParser.parse(sandboxResult);
    }
}
