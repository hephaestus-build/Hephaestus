package de.tum.cit.aet.hephaestus.agent.sandbox.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Specification for an interactive sandbox. {@code (userId, workspaceId)} is the registry key;
 * concurrent {@link InteractiveSandboxService#attach} calls with the same key share the handle.
 */
public record InteractiveSandboxSpec(
        UUID sessionId,
        String userId,
        String workspaceId,
        String image,
        List<String> command,
        Map<String, String> environment,
        @Nullable NetworkPolicy networkPolicy,
        ResourceLimits resourceLimits,
        SecurityProfile securityProfile,
        Map<String, byte[]> inputFiles) {
    /** POSIX-shell env-var name shape: leading letter/underscore, then letters/digits/underscores. */
    private static final Pattern ENV_KEY = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    public InteractiveSandboxSpec {
        sessionId = Objects.requireNonNull(sessionId, "sessionId must not be null");
        userId = Objects.requireNonNull(userId, "userId must not be null");
        workspaceId = Objects.requireNonNull(workspaceId, "workspaceId must not be null");
        image = Objects.requireNonNull(image, "image must not be null");
        resourceLimits = Objects.requireNonNull(resourceLimits, "resourceLimits must not be null");
        if (userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (workspaceId.isBlank()) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        if (image.isBlank()) {
            throw new IllegalArgumentException("image must not be blank");
        }
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty — runner needs an entry point");
        }
        environment = environment != null ? environment : Map.of();
        inputFiles = inputFiles != null ? inputFiles : Map.of();
        for (var entry : environment.entrySet()) {
            String key = entry.getKey();
            if (key == null || !ENV_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException(
                        "Invalid env var name (must match " + ENV_KEY.pattern() + "): " + key);
            }
            String value = entry.getValue();
            if (value != null && (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("Env var value contains NUL/LF/CR: " + key);
            }
        }
        // Defensive copies: callers must not mutate after validation (otherwise blocklist + key
        // validation are bypassable post-construction, since adapters iterate at attach() time).
        command = List.copyOf(command);
        environment = Map.copyOf(environment);
        inputFiles = Map.copyOf(inputFiles);
    }
}
