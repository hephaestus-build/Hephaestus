package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxProperties;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Keeps interactive exec on the same Docker daemon and TLS configuration as the Java clients. */
final class DockerCli {

    private static final Set<String> CONNECTION_ENVIRONMENT =
            Set.of("DOCKER_CONTEXT", "DOCKER_HOST", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH");

    private final List<String> commandPrefix;

    DockerCli(DockerSandboxProperties properties) {
        var command = new ArrayList<>(
                List.of(properties.cli(), "--host=" + properties.host(), "--tls=" + properties.tlsVerify()));
        if (properties.tlsVerify()) {
            // Docker enables TLS whenever --tlsverify is present, even with a false value.
            command.add("--tlsverify=true");
            var directory = Path.of(Objects.requireNonNull(properties.certPath(), "Validated TLS cert-path"));
            command.add("--tlscacert=" + directory.resolve("ca.pem"));
            command.add("--tlscert=" + directory.resolve("cert.pem"));
            command.add("--tlskey=" + directory.resolve("key.pem"));
        }
        commandPrefix = List.copyOf(command);
    }

    ProcessBuilder configure(ProcessBuilder builder) {
        var command = new ArrayList<>(commandPrefix);
        command.addAll(builder.command());
        builder.command(command);
        // Connection settings belong to the worker configuration, not its invoking shell.
        builder.environment().keySet().removeAll(CONNECTION_ENVIRONMENT);
        return builder;
    }
}
