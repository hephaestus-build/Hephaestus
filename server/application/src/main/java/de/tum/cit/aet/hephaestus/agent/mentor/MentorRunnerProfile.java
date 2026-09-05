package de.tum.cit.aet.hephaestus.agent.mentor;

import de.tum.cit.aet.hephaestus.agent.runtime.PiRunnerProfile;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.util.List;
import java.util.Map;

public final class MentorRunnerProfile implements PiRunnerProfile {

    public static final String SCRIPT = "pi-mentor-runner.ts";

    /**
     * {@code pi-provider.ts} is shared with the practice runner; {@code pi-mentor-protocol.ts}
     * declares the JSON-RPC contract this runner speaks with {@code MentorRunnerClient}. Both are
     * imported with relative specifiers, so both must be staged beside pi-mentor-runner.ts.
     */
    private static final List<String> SIDECARS = List.of(
            "pi-agent-sandbox.ts", "pi-error-text.ts", SandboxLayout.PROVIDER_HELPER_FILENAME, "pi-mentor-protocol.ts");

    @Override
    public String runnerScript() {
        return SCRIPT;
    }

    @Override
    public List<String> sidecarScripts() {
        return SIDECARS;
    }

    @Override
    public List<String> runtimeFlags() {
        return java.util.stream.Stream.concat(
                        PiRunnerProfile.super.runtimeFlags().stream(), java.util.stream.Stream.of("--expose-gc"))
                .toList();
    }

    @Override
    public Map<String, String> additionalEnv() {
        return Map.of();
    }
}
