package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class SandboxGatewaySessionsTest {
    @TempDir
    Path temporary;

    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();

    @Test
    void shouldBindDownloadsToTheRegisteredCredentialAndEnforceCumulativeBudget() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "exact bytes");
        try (var session = sessions.register("attempt-credential", archive, "out")) {
            assertThatThrownBy(() -> sessions.require(session.id(), "Bearer different-attempt"))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(session.inputBytes()).isEqualTo(Files.size(archive));
            try (var downloaded =
                    sessions.require(session.id(), "Bearer attempt-credential").download()) {
                assertThat(downloaded.readAllBytes()).isEqualTo(Files.readAllBytes(archive));
            }
            assertThatThrownBy(session::download).isInstanceOf(ResponseStatusException.class);
        }
        assertThat(archive).doesNotExist();
    }

    @Test
    void shouldRevokeAnAttemptWhenItsRuntimeIsRemoved() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        var session = sessions.register("token", archive, "out");

        session.close();

        assertThatThrownBy(() -> sessions.require(session.id(), "Bearer token"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(session::download).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldKeepTheFirstCompleteResultAndRejectDuplicateUploads() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            session.upload(new ByteArrayInputStream(resultTar()));

            assertThat(session.result().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> session.upload(new ByteArrayInputStream(resultTar())))
                    .isInstanceOf(ResponseStatusException.class);
            assertThat(session.result().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldNotAcceptAnInvalidArchiveAsACompletedResult() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            assertThatThrownBy(() -> session.upload(new ByteArrayInputStream(new byte[0])))
                    .isInstanceOf(java.io.IOException.class);
            assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);

            session.upload(new ByteArrayInputStream(resultTar()));

            assertThat(session.result()).containsOnlyKeys("observations.json");
        }
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
