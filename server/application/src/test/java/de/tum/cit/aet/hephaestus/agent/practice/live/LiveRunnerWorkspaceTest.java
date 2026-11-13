package de.tum.cit.aet.hephaestus.agent.practice.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("unit")
class LiveRunnerWorkspaceTest {

    @TempDir
    Path workspace;

    @Test
    void shouldStageInputsWithImageSdkAndToolkitPathsInsteadOfHostInstallations() throws Exception {
        byte[] task = "captured fixture".getBytes(StandardCharsets.UTF_8);

        LiveRunnerWorkspace.stageSpec(workspace, Map.of("task.json", task));

        assertThat(Files.readAllBytes(workspace.resolve("task.json"))).isEqualTo(task);
        assertThat(Files.readSymbolicLink(workspace.resolve("node_modules")))
                .isEqualTo(Path.of("/opt/pi-sdk/node_modules"));
        assertThat(Files.readSymbolicLink(workspace.resolve(SandboxLayout.PRACTICE_SCRIPT_LIBRARY_LINK)))
                .isEqualTo(Path.of("/opt/practice-scripts/lib"));
        assertThat(Files.readSymbolicLink(workspace.resolve(SandboxLayout.PRACTICE_SCRIPT_KITS_LINK)))
                .isEqualTo(Path.of("/opt/practice-scripts/kits"));
        assertThat(workspace.resolve(".home")).doesNotExist();
    }

    @Test
    void shouldPreserveTheProxyPortAndRawPathWhileUsingTheScopedContainerTunnel() {
        assertThat(LiveRunnerWorkspace.proxyInContainer("http://127.0.0.1:18181/internal/llm?scope=run%2F1"))
                .hasToString("http://host.testcontainers.internal:18181/internal/llm?scope=run%2F1");
        assertThat(LiveRunnerWorkspace.proxyInContainer("http://localhost:18081"))
                .hasToString("http://host.testcontainers.internal:18081");
        assertThat(LiveRunnerWorkspace.proxyInContainer("http://[::1]:18081/internal/llm"))
                .hasToString("http://host.testcontainers.internal:18081/internal/llm");
    }

    @Test
    void shouldRefuseAnUnscopedProviderOrCredentialUrlBeforeStartingAContainer() {
        for (String url : new String[] {
            "https://provider.example:443/v1",
            "http://127.0.0.1/internal/llm",
            "http://127.0.0.1:0/internal/llm",
            "http://credential@127.0.0.1:18181/internal/llm",
            "http:relative",
            "http://127.0.0.1:18181/internal/llm#fragment"
        }) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> LiveRunnerWorkspace.proxyInContainer(url))
                    .withMessage("The live runner requires a loopback proxy with an explicit port");
        }
    }
}
