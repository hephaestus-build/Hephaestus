package de.tum.cit.aet.hephaestus.agent.runtime;

import de.tum.cit.aet.hephaestus.agent.catalog.ReasoningEffort;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Inputs for {@link PiRuntimeFactory#build(PiPlanSpec)}.
 *
 * @param apiProtocol Pi's own {@code api} token (e.g. {@code openai-completions}), passed verbatim
 *     into the {@code hephaestus} provider registration
 * @param jobToken the job-scoped bearer credential the sandbox authenticates to the LLM proxy with;
 *     required, because that proxy is the only path a sandbox has to a model
 */
public record PiPlanSpec(
        String apiProtocol,
        String upstreamModelId,
        @Nullable Integer contextWindow,
        @Nullable Integer maxOutputTokens,
        @Nullable ReasoningEffort reasoningEffort,
        @Nullable String jobToken,
        boolean allowInternet,
        int timeoutSeconds,
        PiRunnerProfile runnerProfile,
        Map<String, byte[]> extraInputs,
        @Nullable String precomputeStep) {
    public PiPlanSpec {
        if (apiProtocol == null || apiProtocol.isBlank()) {
            throw new IllegalArgumentException("apiProtocol must not be blank");
        }
        if (upstreamModelId == null || upstreamModelId.isBlank()) {
            throw new IllegalArgumentException("upstreamModelId must not be blank");
        }
        Objects.requireNonNull(runnerProfile, "runnerProfile");
        if (runnerProfile.runnerScript() == null || runnerProfile.runnerScript().isBlank()) {
            throw new IllegalArgumentException("runnerProfile.runnerScript() must not be blank");
        }
        if (timeoutSeconds <= PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS) {
            throw new IllegalArgumentException(
                    "timeoutSeconds must exceed TIMEOUT_BUFFER_SECONDS=" + PiRuntimeFactory.TIMEOUT_BUFFER_SECONDS
                            + ", got "
                            + timeoutSeconds);
        }
        if (jobToken == null || jobToken.isBlank()) {
            throw new IllegalArgumentException("jobToken is required — every sandbox talks to the LLM proxy");
        }
        // Cloned, not just Map.copyOf'd: a frozen map still shares its byte[] values with the caller,
        // who could then mutate file contents after the allowlist check below has passed.
        extraInputs = extraInputs != null
                ? extraInputs.entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, e -> e.getValue().clone()))
                : Map.of();
        for (String path : extraInputs.keySet()) {
            boolean ok = SandboxLayout.allowedExtraInputPaths().contains(path)
                    || SandboxLayout.allowedExtraInputPrefixes().stream().anyMatch(path::startsWith);
            if (!ok) {
                throw new IllegalArgumentException("extraInputs path '" + path
                        + "' is not a recognised workspace path: must appear in "
                        + "SandboxLayout.allowedExtraInputPaths() or be prefixed by one of "
                        + SandboxLayout.allowedExtraInputPrefixes());
            }
        }
        precomputeStep = precomputeStep != null ? precomputeStep : "";
    }
}
