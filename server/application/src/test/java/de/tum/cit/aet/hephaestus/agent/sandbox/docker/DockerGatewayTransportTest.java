package de.tum.cit.aet.hephaestus.agent.sandbox.docker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewaySessions;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxAttemptRunner;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxTestLimits;
import de.tum.cit.aet.hephaestus.agent.sandbox.SandboxWorkspaceManager;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxException;
import de.tum.cit.aet.hephaestus.agent.sandbox.spi.SandboxSpec;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;

@Tag("unit")
class DockerGatewayTransportTest extends BaseUnitTest {
    @Mock
    private SandboxNetworkManager network;

    @Mock
    private SandboxContainerManager containers;

    @Mock
    private ContainerSecurityPolicy security;

    @Mock
    private DockerVolumeOperations volumes;

    @TempDir
    Path temporary;

    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final List<DockerOperations.ContainerSpec> created = new ArrayList<>();
    private final List<DockerOperations.VolumeInfo> volumesCreated = new ArrayList<>();
    private final UUID jobId = UUID.randomUUID();

    @Mock
    private SandboxCreator creator;

    private SandboxAttemptRunner adapter;

    @BeforeEach
    void setUp() {
        var meters = new SimpleMeterRegistry();
        adapter = new SandboxAttemptRunner(
                new DockerSandboxBackend(network, containers, security, volumes, creator, "default", () -> 8081),
                new SandboxWorkspaceManager(),
                sessions,
                meters);
    }

    private void execution(int initializerExit) throws Exception {
        Map<String, String> labels = Map.of(SandboxLabels.OWNER, "default", SandboxLabels.JOB_ID, jobId.toString());
        when(network.createJobNetwork(jobId, false, labels)).thenReturn("network");
        when(network.connectAppServer("network")).thenReturn("172.18.0.2");
        when(security.buildHostConfig(any(), any(), anyBoolean()))
                .thenReturn(new DockerOperations.HostConfigSpec(
                        1024,
                        1024,
                        1,
                        100,
                        true,
                        false,
                        List.of("ALL"),
                        List.of("no-new-privileges"),
                        Map.of(),
                        List.of("0.0.0.0"),
                        "private",
                        "none",
                        null,
                        Map.of(),
                        List.of()));
        when(security.buildLabels(jobId)).thenReturn(labels);
        // The removal finds the sandbox's volumes and containers by their labels, as the daemon lists them.
        doAnswer(invocation -> {
                    volumesCreated.add(
                            new DockerOperations.VolumeInfo(invocation.getArgument(0), invocation.getArgument(1)));
                    return null;
                })
                .when(volumes)
                .createVolume(anyString(), anyMap());
        when(volumes.listVolumes(anyMap())).thenAnswer(invocation -> List.copyOf(volumesCreated));
        when(containers.listManagedContainers())
                .thenAnswer(invocation -> created.size() < 2
                        ? List.of()
                        : List.of(new DockerOperations.ContainerInfo("runtime", "runtime", labels, "exited", null)));
        when(containers.createContainer(any())).thenAnswer(invocation -> {
            created.add(invocation.getArgument(0));
            return created.size() == 1 ? "initializer" : "runtime";
        });
        when(containers.getLogs(anyString())).thenReturn("");
        when(containers.waitForCompletion(anyString(), any())).thenAnswer(invocation -> {
            var spec = created.getLast();
            String url = spec.environment().get("SANDBOX_RUNTIME_URL");
            assertThat(url).isNotNull();
            UUID sessionId = UUID.fromString(url.substring(url.lastIndexOf('/') + 1));
            assertThat(sessionId).isEqualTo(jobId);
            var session = sessions.require(sessionId, "Bearer token");
            if (invocation.getArgument(0).equals("initializer")) {
                if (initializerExit == 0) {
                    try (var input = session.download()) {
                        input.transferTo(OutputStream.nullOutputStream());
                    }
                }
                return new SandboxContainerManager.WaitOutcome(initializerExit, false);
            }
            byte[] result = resultTar();
            session.upload(
                    new ByteArrayInputStream(result),
                    "sha-256=:"
                            + Base64.getEncoder()
                                    .encodeToString(
                                            MessageDigest.getInstance("SHA-256").digest(result)) + ":");
            return new SandboxContainerManager.WaitOutcome(0, false);
        });
    }

    private SandboxSpec spec(Map<String, Path> onDisk) {
        return new SandboxSpec(
                jobId,
                null,
                "pi:locked",
                List.of("sh", "-c", "run-review"),
                Map.of(),
                "token",
                SandboxTestLimits.ATTEMPT,
                null,
                Map.of("task.json", "{}".getBytes(StandardCharsets.UTF_8)),
                onDisk,
                List.of());
    }

    @Test
    void shouldUseGatewayTransportAndReadOnlyInputMounts() throws Exception {
        execution(0);
        Path source = Files.writeString(temporary.resolve("source.java"), "class Source {}\n");

        var result = adapter.execute(spec(Map.of("inputs/sources/scm/repo/Source.java", source)));

        assertThat(result.outputFiles().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        assertThat(created).hasSize(2);
        var initializer = created.getFirst();
        var runtime = created.getLast();
        assertThat(initializer.hostConfig().volumeMounts()).allMatch(mount -> !mount.readOnly());
        assertThat(runtime.hostConfig().readonlyRootfs()).isTrue();
        assertThat(runtime.hostConfig().volumeMounts())
                .anyMatch(mount -> mount.target().equals("/workspace") && mount.readOnly());
        assertThat(runtime.hostConfig().volumeMounts())
                .filteredOn(mount -> !mount.target().equals("/workspace"))
                .allMatch(mount -> !mount.readOnly());
        assertThat(runtime.command())
                .containsExactly(
                        "node",
                        "--disable-sigusr1",
                        "--enable-source-maps",
                        "/opt/pi-sdk/gateway-run.js",
                        "sh",
                        "-c",
                        "run-review");
        for (var mount : runtime.hostConfig().volumeMounts()) {
            verify(volumes).removeVolume(mount.name());
        }
        verify(containers).forceRemove("initializer");
        verify(containers).forceRemove("runtime");
    }

    @Test
    void shouldRemoveVolumesWithoutLaunchingRuntimeWhenInitializationFails() throws Exception {
        execution(1);

        assertThatThrownBy(() -> adapter.execute(spec(Map.of()))).isInstanceOf(SandboxException.class);

        assertThat(created).hasSize(1);
        for (var mount : created.getFirst().hostConfig().volumeMounts()) {
            verify(volumes).removeVolume(mount.name());
        }
        verify(containers).forceRemove("initializer");
    }

    private static byte[] resultTar() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            var entry = new TarArchiveEntry("out/observations.json");
            entry.setSize(2);
            tar.putArchiveEntry(entry);
            tar.write("{}".getBytes(StandardCharsets.UTF_8));
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }
}
