package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class SandboxGatewaySessionsTest {
    @TempDir
    Path temporary;

    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();

    @Test
    void shouldBindDownloadsToTheRegisteredCredentialAndAllowWholeFileRetry() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "exact bytes");
        try (var session = sessions.register("attempt-credential", archive, "out")) {
            assertThatThrownBy(() -> sessions.require(session.id(), "Bearer different-attempt"))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            assertThat(session.inputBytes()).isEqualTo(Files.size(archive));
            try (var downloaded =
                    sessions.require(session.id(), "Bearer attempt-credential").download()) {
                assertThat(downloaded.readNBytes(3)).isEqualTo("exa".getBytes(StandardCharsets.UTF_8));
            }
            try (var retry =
                    sessions.require(session.id(), "Bearer attempt-credential").download()) {
                assertThat(retry.readAllBytes()).isEqualTo(Files.readAllBytes(archive));
            }
        }
        assertThat(archive).doesNotExist();
    }

    @Test
    void shouldRevokeAnAttemptWhenItsRuntimeIsRemoved() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        var session = sessions.register("token", archive, "out");

        session.close();

        assertThatThrownBy(() -> sessions.require(session.id(), "Bearer token"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThatThrownBy(session::download)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void shouldRefuseACredentialThatIsNotABearerToken() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            assertThatThrownBy(() -> sessions.require(session.id(), "Basic token"))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void shouldRefuseAnEmptyBearerToken() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            assertThatThrownBy(() -> sessions.require(session.id(), "Bearer "))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void shouldKeepTheFirstCompleteResultAndRejectDuplicateUploads() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            session.upload(new ByteArrayInputStream(resultTar()));

            assertThat(session.result().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
            assertThatThrownBy(() -> session.upload(new ByteArrayInputStream(resultTar())))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
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

    @Test
    void shouldKeepAnOverlappingRetryRetryableUntilAnUploadCompletes() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        var reading = new CountDownLatch(1);
        var disconnect = new CountDownLatch(1);
        try (var session = sessions.register("token", archive, "out");
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                session.upload(new InputStream() {
                    @Override
                    public int read() throws IOException {
                        reading.countDown();
                        try {
                            if (!disconnect.await(5, TimeUnit.SECONDS)) throw new IOException("Read timed out");
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IOException(exception);
                        }
                        throw new IOException("Connection lost");
                    }
                });
                return null;
            });
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> session.upload(new ByteArrayInputStream(resultTar())))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);
            } finally {
                disconnect.countDown();
            }
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("Connection lost");

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
