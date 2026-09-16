package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.agent.runtime.AgentResult;
import de.tum.cit.aet.hephaestus.agent.runtime.PiPlanSpec;
import de.tum.cit.aet.hephaestus.agent.runtime.PiResultParser;
import de.tum.cit.aet.hephaestus.agent.runtime.PiRuntimeFactory;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxResult;
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

    public PracticeSandboxSpec buildSandboxSpec(PracticeAgentRequest request) {
        PiRuntimeFactory.PiPlan plan = runtimeFactory.build(new PiPlanSpec(
                request.apiProtocol(),
                request.upstreamModelId(),
                request.contextWindow(),
                request.maxOutputTokens(),
                request.supportsReasoning(),
                request.jobToken(),
                // Native shell access must not provide a route to push or fetch uncaptured evidence.
                false,
                request.timeoutSeconds(),
                PROFILE,
                Map.of(),
                buildPrecomputeStep(request.timeoutSeconds())));
        return new PracticeSandboxSpec(
                imageProperties.reference(),
                plan.command(),
                plan.environment(),
                plan.inputFiles(),
                SandboxLayout.OUTPUT_PATH,
                null,
                plan.networkPolicy(),
                plan.promptDigest());
    }

    public AgentResult parseResult(SandboxResult sandboxResult) {
        return resultParser.parse(sandboxResult);
    }

    /**
     * What runs before the model: the change view is derived from the checkout (a failure there is the
     * review's failure, since nothing could be reviewed), then the practices' precompute scripts, which
     * only add hints and may fail quietly.
     */
    static String buildPrecomputeStep(int timeoutSeconds) {
        int budgetSeconds = Math.min(30, Math.max(1, timeoutSeconds / 10));
        return "node /workspace/pi-change.ts && sh /workspace/pi-precompute.sh " + budgetSeconds + " && ";
    }
}
