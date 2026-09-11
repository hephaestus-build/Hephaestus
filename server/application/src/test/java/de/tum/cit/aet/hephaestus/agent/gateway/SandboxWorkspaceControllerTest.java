package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.runtime.SandboxOutputArchive;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class SandboxWorkspaceControllerTest {
    @TempDir
    Path temporary;

    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();
    private final SandboxWorkspaceController controller = new SandboxWorkspaceController(sessions);

    @Test
    void shouldAdvertiseAndTransferOnlyTheAuthenticatedRuntimeWorkspace() throws Exception {
        var archive = Files.writeString(temporary.resolve("input.tar"), "trusted input");
        try (var session = sessions.register("token", archive, "out")) {
            var response = new MockHttpServletResponse();
            assertThat(controller.capabilities(session.id(), "Bearer token").protocolVersion())
                    .isEqualTo(3);
            assertThat(controller.capabilities(session.id(), "Bearer token").workspaceByteBudget())
                    .isEqualTo(Files.size(archive));
            assertThatThrownBy(() -> controller.workspace(session.id(), "Bearer other", response))
                    .isInstanceOf(ResponseStatusException.class);
            controller.workspace(session.id(), "Bearer token", response);
            assertThat(response.getContentAsByteArray()).isEqualTo(Files.readAllBytes(archive));
            assertThat(response.getContentType()).isEqualTo("application/x-tar");
        }
    }

    @Test
    void shouldRejectMissingLengthOversizedAndMalformedResults() throws Exception {
        var archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> controller.result(session.id(), "Bearer token", request, response))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode().value())
                                    .isEqualTo(411));
            var oversized = mock(HttpServletRequest.class);
            when(oversized.getContentLengthLong()).thenReturn(SandboxOutputArchive.MAX_OUTPUT_BYTES + 1);
            assertThatThrownBy(() -> controller.result(session.id(), "Bearer token", oversized, response))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode().value())
                                    .isEqualTo(413));
            request.setContent(new byte[] {1});
            assertThatThrownBy(() -> controller.result(session.id(), "Bearer token", request, response))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            exception -> assertThat(exception.getStatusCode().value())
                                    .isEqualTo(400));
            assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);
        }
    }
}
