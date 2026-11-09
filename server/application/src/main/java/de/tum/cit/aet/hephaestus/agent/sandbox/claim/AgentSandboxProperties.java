package de.tum.cit.aet.hephaestus.agent.sandbox.claim;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the server's sandbox claims point. The chart sets each value from the objects it installs. The capacity
 * numbers of sandboxes are in {@code hephaestus.agent.capacity}.
 *
 * @param warmPools the {@code SandboxWarmPool} of each sandbox kind, in the sandbox namespace. Each holds no sandboxes,
 *     and a claim names it because the agent-sandbox API requires one
 * @param gatewayUrl the gateway address that a claim passes to its sandbox, such as {@code http://10.43.0.12:8081}. The
 *     server resolves the host to an address when it creates a claim, so the sandbox needs no DNS. Empty where this
 *     process creates no claim
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.agent.sandbox")
public record AgentSandboxProperties(
        @Valid @DefaultValue WarmPools warmPools,

        @DefaultValue("") @Pattern(regexp = "|https?://[^/]+")
        String gatewayUrl) {

    /**
     * The warm pool of each kind: {@code <release>-sandbox-<kind>} in the chart.
     *
     * @param review the warm pool that a review's sandbox is claimed from
     * @param conversation the warm pool that a conversation's sandbox is claimed from
     * @param action the warm pool that an action task's sandbox is claimed from
     */
    public record WarmPools(
            @DefaultValue("hephaestus-sandbox-review") @NotBlank
            String review,

            @DefaultValue("hephaestus-sandbox-conversation") @NotBlank
            String conversation,

            @DefaultValue("hephaestus-sandbox-action") @NotBlank
            String action) {

        public String of(AgentSandboxKind kind) {
            return switch (kind) {
                case REVIEW -> review;
                case CONVERSATION -> conversation;
                case ACTION -> action;
            };
        }
    }
}
