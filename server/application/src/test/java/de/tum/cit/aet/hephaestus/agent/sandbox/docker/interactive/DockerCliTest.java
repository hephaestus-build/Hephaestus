package de.tum.cit.aet.hephaestus.agent.sandbox.docker.interactive;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.sandbox.docker.DockerSandboxProperties;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class DockerCliTest {

    @Test
    void shouldUseConfiguredSocketWithoutInheritingDockerConnectionSettings() {
        var cli = new DockerCli(new DockerSandboxProperties(
                "unix:///run/user/1000/docker.sock", false, null, null, null, "/usr/bin/docker"));

        var process = new ProcessBuilder("exec", "-i", "container-id", "node", "runner.js");
        process.environment()
                .putAll(Map.of(
                        "DOCKER_CONTEXT", "wrong-context",
                        "DOCKER_HOST", "tcp://wrong:2375",
                        "DOCKER_TLS", "1",
                        "DOCKER_TLS_VERIFY", "1",
                        "DOCKER_CERT_PATH", "/wrong",
                        "PRESERVED_VARIABLE", "untouched"));
        cli.configure(process);

        assertThat(process.command())
                .containsExactly(
                        "/usr/bin/docker",
                        "--host=unix:///run/user/1000/docker.sock",
                        "--tls=false",
                        "exec",
                        "-i",
                        "container-id",
                        "node",
                        "runner.js");
        assertThat(process.environment())
                .doesNotContainKeys(
                        "DOCKER_CONTEXT", "DOCKER_HOST", "DOCKER_TLS", "DOCKER_TLS_VERIFY", "DOCKER_CERT_PATH")
                .containsEntry("PRESERVED_VARIABLE", "untouched");
    }

    @Test
    void shouldUseConfiguredMutualTlsCertificatesForTcpDaemon() {
        var directory = Path.of("docker client certificates");
        var cli = new DockerCli(
                new DockerSandboxProperties("tcp://docker:2376", true, directory.toString(), null, null, "docker"));

        var process = cli.configure(new ProcessBuilder("exec", "container-id", "sh", "-c", "mkdir -p /workspace"));

        assertThat(process.command())
                .containsExactly(
                        "docker",
                        "--host=tcp://docker:2376",
                        "--tls=true",
                        "--tlsverify=true",
                        "--tlscacert=" + directory.resolve("ca.pem"),
                        "--tlscert=" + directory.resolve("cert.pem"),
                        "--tlskey=" + directory.resolve("key.pem"),
                        "exec",
                        "container-id",
                        "sh",
                        "-c",
                        "mkdir -p /workspace");
    }
}
