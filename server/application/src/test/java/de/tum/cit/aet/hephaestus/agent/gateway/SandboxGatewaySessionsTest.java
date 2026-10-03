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
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Tag("unit")
class SandboxGatewaySessionsTest {
    @TempDir
    Path temporary;

    private final SandboxGatewaySessions sessions = new SandboxGatewaySessions();

    @Test
    void shouldServeOnlyFrozenSelectedBytesAndRetryWithoutSpendingAgain() throws Exception {
        Path archive = workspaceArchive();
        long bytes = Files.size(archive);
        var bounded = new SandboxGatewaySessions(bytes * 2);
        Path selection;
        try (var session = bounded.register("token", archive, "out")) {
            byte[] first;
            try (var download = session.download("area", "chat")) {
                first = download.input().readAllBytes();
                assertThat(first.length).isEqualTo(download.bytes());
                try (var tar = new TarArchiveInputStream(new ByteArrayInputStream(first))) {
                    assertThat(tar.getNextEntry().getName()).isEqualTo("context/chat/channel/2026-09.jsonl");
                    assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8))
                            .isEqualTo("message");
                    assertThat(tar.getNextEntry()).isNull();
                }
            }
            try (var retry = session.download("area", "chat")) {
                assertThat(retry.input().readAllBytes()).isEqualTo(first);
            }
            assertThatThrownBy(() -> session.download("repo", "7"))
                    .isInstanceOf(WorkspaceBudgetExceededException.class);
            try (var paths = Files.list(temporary)) {
                selection = paths.filter(path -> path.getFileName().toString().startsWith("workspace-selection-"))
                        .findFirst()
                        .orElseThrow();
            }
        }
        assertThat(selection).doesNotExist();
        assertThat(archive).doesNotExist();
    }

    @Test
    void shouldNotRegisterAnOverBudgetWorkspace() throws Exception {
        Path archive = Files.writeString(temporary.resolve("large.tar"), "12345");
        var bounded = new SandboxGatewaySessions(4);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> bounded.register(id, "token", archive, "out"))
                .isInstanceOfSatisfying(WorkspaceBudgetExceededException.class, refusal -> {
                    assertThat(refusal.getBody().getProperties())
                            .containsEntry("reasonCode", "WORKSPACE_BUDGET_EXCEEDED");
                    assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
                });
        assertThatThrownBy(() -> bounded.require(id, "Bearer token")).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldRefuseAbsentAndUnsafeSelectionsWithoutLeavingFiles() throws Exception {
        Path archive = workspaceArchive();
        try (var session = sessions.register("token", archive, "out")) {
            for (String repository : List.of("8", "../7", "7/../8", "7%2fsecret")) {
                assertThatThrownBy(() -> session.download("repo", repository))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                refusal -> assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
            }
            assertThatThrownBy(() -> session.download("area", "docs")).isInstanceOf(ResponseStatusException.class);
            try (var paths = Files.list(temporary)) {
                assertThat(paths.toList()).containsExactly(archive);
            }
            try (var download = session.download("repo", "7");
                    var tar = new TarArchiveInputStream(download.input())) {
                assertThat(tar.getNextEntry().getName()).isEqualTo("repos/7/README.md");
                assertThat(new String(tar.readAllBytes(), StandardCharsets.UTF_8))
                        .isEqualTo("repository");
                assertThat(tar.getNextEntry()).isNull();
            }
        }
    }

    private Path workspaceArchive() throws IOException {
        Path archive = temporary.resolve("workspace.tar");
        try (var tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
            for (var file : Map.of("context/chat/channel/2026-09.jsonl", "message", "repos/7/README.md", "repository")
                    .entrySet()) {
                byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
                var entry = new TarArchiveEntry(file.getKey());
                entry.setSize(content.length);
                tar.putArchiveEntry(entry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        }
        return archive;
    }

    @Test
    void shouldNotReplaceAnActiveJobSession() throws Exception {
        UUID jobId = UUID.randomUUID();
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register(jobId, "first-attempt", archive, "out")) {
            assertThatThrownBy(() -> sessions.register(jobId, "second-attempt", archive, "out"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(sessions.require(jobId, "Bearer first-attempt")).isSameAs(session);
        }
    }

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

    @ParameterizedTest
    @ValueSource(strings = {"Basic token", "Bearer "})
    void shouldRefuseACredentialWhenItIsNotANonEmptyBearerToken(String authorization) throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            assertThatThrownBy(() -> sessions.require(session.id(), authorization))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void shouldKeepTheFirstCompleteResultAndRejectDuplicateUploads() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            byte[] result = resultTar();
            String expectedEtag = etag(result);
            upload(session, result);

            assertThat(session.result().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
            var duplicate = upload(session, result);
            assertThat(duplicate.admitted()).isFalse();
            assertThat(duplicate.etag()).isEqualTo(expectedEtag);
            assertThat(session.result().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void shouldNotAcceptAnInvalidArchiveAsACompletedResult() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            assertThatThrownBy(() -> upload(session, new byte[0])).isInstanceOf(IOException.class);
            assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);

            upload(session, resultTar());

            assertThat(session.result()).containsOnlyKeys("observations.json");
        }
    }

    @Test
    void shouldRejectADifferentSecondResultAndAFalseContentDigest() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "input");
        try (var session = sessions.register("token", archive, "out")) {
            byte[] first = resultTar();
            assertThatThrownBy(() -> session.upload(new ByteArrayInputStream(first), contentDigest(new byte[0])))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
            String admittedEtag = upload(session, first).etag();

            byte[] second = resultTar("{ }".getBytes(StandardCharsets.UTF_8));
            var conflict = upload(session, second);
            assertThat(conflict.admitted()).isFalse();
            assertThat(conflict.etag()).isEqualTo(admittedEtag).isNotEqualTo(etag(second));
            assertThat(session.result().get("observations.json")).isEqualTo("{}".getBytes(StandardCharsets.UTF_8));
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
                session.upload(
                        new InputStream() {
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
                        },
                        contentDigest(resultTar()));
                return null;
            });
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> upload(session, resultTar()))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
                assertThatThrownBy(session::result).isInstanceOf(IllegalStateException.class);
            } finally {
                disconnect.countDown();
            }
            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS)).hasRootCauseMessage("Connection lost");

            upload(session, resultTar());

            assertThat(session.result()).containsOnlyKeys("observations.json");
        }
    }

    private static byte[] resultTar() throws Exception {
        return resultTar("{}".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] resultTar(byte[] content) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tar = new TarArchiveOutputStream(bytes)) {
            var entry = new TarArchiveEntry("out/observations.json");
            entry.setSize(content.length);
            tar.putArchiveEntry(entry);
            tar.write(content);
            tar.closeArchiveEntry();
        }
        return bytes.toByteArray();
    }

    private static SandboxGatewaySessions.Session.UploadResult upload(
            SandboxGatewaySessions.Session session, byte[] bytes) throws Exception {
        return session.upload(new ByteArrayInputStream(bytes), contentDigest(bytes));
    }

    private static String contentDigest(byte[] bytes) throws Exception {
        return "sha-256=:"
                + Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes)) + ":";
    }

    private static String etag(byte[] bytes) throws Exception {
        return '"'
                + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                + '"';
    }
}
