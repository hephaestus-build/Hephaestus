package de.tum.cit.aet.hephaestus.agent.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    void shouldReceiveAColdReplicasResultWithoutAnInputArchive() throws Exception {
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        byte[] result = resultTar();
        var upload =
                sessions.receiveAttempt(job, "agent:test", new ByteArrayInputStream(result), contentDigest(result));
        assertThat(upload.files()).containsOnlyKeys("observations.json");
        assertThat(upload.etag()).isEqualTo(etag(result));
        assertThat(sessions.jobIds()).isEmpty();
    }

    @Test
    void shouldVerifyTheResultDigestBeforeKeepingAnyListenerCopy() throws Exception {
        var told = new ArrayList<Map<String, byte[]>>();
        var receiver = new SandboxGatewaySessions(
                SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET, List.of((jobId, attempt, image) -> told::add));
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        byte[] result = resultTar();
        String wrongDigest = contentDigest(new byte[0]);
        assertThatThrownBy(
                        () -> receiver.receiveAttempt(job, "agent:test", new ByteArrayInputStream(result), wrongDigest))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(told).isEmpty();
        receiver.receiveAttempt(job, "agent:test", new ByteArrayInputStream(result), contentDigest(result));
        assertThat(told).singleElement().satisfies(files -> assertThat(files).containsOnlyKeys("observations.json"));
    }

    @Test
    void shouldRetryTheSameFrozenArchiveAndRemoveItWhenCustodyEnds() throws Exception {
        UUID id = UUID.randomUUID();
        Path archive = Files.writeString(temporary.resolve("input.tar"), "frozen workspace");
        var session = sessions.register(id, archive);
        try (session) {
            assertThat(session.inputBytes()).isEqualTo(Files.size(archive));
            try (var first = session.download();
                    var second = session.download()) {
                assertThat(first.readAllBytes()).isEqualTo(second.readAllBytes());
            }
            assertThat(sessions.jobIds()).containsExactly(id);
            sessions.closeJob(id);
            assertThat(archive).doesNotExist();
            assertThat(sessions.jobIds()).isEmpty();
            assertThatThrownBy(session::download)
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            ended -> assertThat(ended.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Test
    void shouldRefuseAnArchiveAboveTheWorkspaceBudget() throws Exception {
        Path archive = Files.writeString(temporary.resolve("input.tar"), "too large");
        var bounded = new SandboxGatewaySessions(4);
        assertThatThrownBy(() -> bounded.register(UUID.randomUUID(), archive))
                .isInstanceOf(WorkspaceBudgetExceededException.class);
        assertThat(bounded.jobIds()).isEmpty();
    }

    @Test
    void shouldBindTheCurrentReceiptAndKeepAdmissionIndependentOfListenerFailure() throws Exception {
        var bound = new ArrayList<String>();
        var told = new ArrayList<Map<String, byte[]>>();
        var receiver = new SandboxGatewaySessions(
                SandboxGatewaySessions.WORKSPACE_BYTE_BUDGET,
                List.of(
                        (job, attempt, image) -> {
                            throw new IllegalStateException("Receipt unavailable");
                        },
                        (job, attempt, image) -> files -> {
                            throw new IllegalStateException("Transcript unavailable");
                        },
                        (job, attempt, image) -> {
                            bound.add(job + "#" + attempt + "@" + image);
                            return told::add;
                        }));
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        byte[] result = resultTar();
        var received =
                receiver.receiveAttempt(job, "agent:test", new ByteArrayInputStream(result), contentDigest(result));
        assertThat(received.files()).containsOnlyKeys("observations.json");
        assertThat(bound).containsExactly(job.getId() + "#0@agent:test");
        assertThat(told).hasSize(1);
    }

    @Test
    void shouldRejectAnInvalidArchiveBeforeReceivingACompleteResult() throws Exception {
        var job = new ReviewJob();
        job.setId(UUID.randomUUID());
        assertThatThrownBy(() -> sessions.receiveAttempt(
                        job, "agent:test", new ByteArrayInputStream(new byte[0]), contentDigest(new byte[0])))
                .isInstanceOf(IOException.class);
        byte[] result = resultTar();
        assertThat(sessions.receiveAttempt(job, "agent:test", new ByteArrayInputStream(result), contentDigest(result))
                        .files())
                .containsOnlyKeys("observations.json");
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
