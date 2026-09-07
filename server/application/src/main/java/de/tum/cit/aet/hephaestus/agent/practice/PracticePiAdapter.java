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
                request.allowInternet(),
                request.timeoutSeconds(),
                PROFILE,
                Map.of(),
                buildPrecomputeStep()));
        return new PracticeSandboxSpec(
                imageProperties.reference(),
                plan.command(),
                plan.environment(),
                plan.inputFiles(),
                SandboxLayout.OUTPUT_PATH,
                null,
                plan.networkPolicy(),
                Map.of(),
                plan.promptDigest());
    }

    public AgentResult parseResult(SandboxResult sandboxResult) {
        return resultParser.parse(sandboxResult);
    }

    /** Precompute is advisory; failure must not prevent the review. */
    static String buildPrecomputeStep() {
        return "(mkdir -p /workspace/work/precompute-stage /workspace/work/precompute-out"
                // Node's permission model requires unrestricted filesystem access to create symlinks.
                + " && ln -sf /opt/precompute/lib /workspace/work/precompute-stage/lib"
                + " && env -i HOME=/home/agent PATH=/usr/local/bin:/usr/bin:/bin TMPDIR=/tmp node"
                + " --permission --allow-fs-read=/workspace --allow-fs-read=/opt/precompute"
                + " '--allow-fs-write=/workspace/work/precompute-stage*'"
                + " '--allow-fs-write=/workspace/work/precompute-out*' --allow-child-process"
                + " /workspace/pi-precompute.ts /workspace > /tmp/precompute-runner.log 2>&1"
                + " || { echo '[precompute] failed, continuing without hints';"
                + " cp /tmp/precompute-runner.log /workspace/work/precompute-out/precompute-runner.log 2>/dev/null;"
                + " tail -200 /tmp/precompute-runner.log 2>/dev/null; true; }) && ";
    }
}
