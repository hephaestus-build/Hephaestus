package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Docker adapter configuration.
 *
 * @param host Docker daemon endpoint; the single-host deployment uses the local Unix socket
 * @param tlsVerify enable mutual TLS for TCP connections
 * @param certPath directory containing Docker client TLS certificates
 * @param containerRuntime Docker runtime name (unset uses the daemon default; runsc selects gVisor)
 * @param appServerContainerId container attached to job networks (unset falls back to HOSTNAME)
 * @param owner stable installation identifier, shared by roles using the same database
 * @param cli Docker executable used for interactive sandbox attachment
 */
@Validated
@ConditionalOnProperty(name = RuntimeRole.WORKER_PROPERTY, havingValue = "true", matchIfMissing = true)
@ConfigurationProperties(prefix = "hephaestus.sandbox.docker", ignoreUnknownFields = false)
public record DockerSandboxProperties(
        @DefaultValue("unix:///var/run/docker.sock") @NotBlank
        String host,

        @DefaultValue("false") boolean tlsVerify,
        @Nullable String certPath,
        @Nullable String containerRuntime,
        @Nullable String appServerContainerId,
        @DefaultValue("docker") @NotBlank String cli,

        @DefaultValue("default") @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{0,62}")
        String owner) {

    // Both Java and CLI clients must use explicit certificates, not different ambient defaults.
    @AssertTrue(message = "cert-path must be set when tls-verify is enabled")
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    private boolean isTlsCertificatePathConfigured() {
        return !tlsVerify || (certPath != null && !certPath.isBlank());
    }

    public @Nullable String resolvedAppServerContainerId() {
        return appServerContainerId == null || appServerContainerId.isBlank() ? null : appServerContainerId;
    }
}
