package de.tum.cit.aet.hephaestus.agent.practice;

import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Sandbox configuration produced by {@link PracticePiAdapter#buildSandboxSpec}.
 * The executor combines it with job-level concerns into a {@link
 * SandboxSpec}. {@code inputFiles} paths are
 * workspace-relative.
 */
public record PracticeSandboxSpec(
        String image,
        List<String> command,
        Map<String, String> environment,
        Map<String, byte[]> inputFiles,
        String gatewayToken,
        @Nullable String promptDigest) {
    public PracticeSandboxSpec {
        Objects.requireNonNull(image, "image must not be null");
        if (image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        Objects.requireNonNull(gatewayToken, "gatewayToken must not be null");
        command = command != null ? List.copyOf(command) : List.of();
        environment = environment != null ? Map.copyOf(environment) : Map.of();
        inputFiles = inputFiles != null ? Map.copyOf(inputFiles) : Map.of();
    }
}
