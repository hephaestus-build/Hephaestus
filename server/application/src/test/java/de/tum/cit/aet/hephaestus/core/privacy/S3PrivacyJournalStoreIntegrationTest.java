package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournal;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PrivacyJournalKind;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.SocketEffect;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

@Tag("integration")
class S3PrivacyJournalStoreIntegrationTest extends BaseIntegrationTest {

    private static final String BUCKET = "test-archive";
    private static final String PREFIX = "privacy-journal/";
    private static final String JOURNAL_KEY = "instance/2026-11-24/entry.json";
    private static final String JOURNAL_USER = "journal-user";
    private static final String JOURNAL_PASSWORD = "journal-password-fixture";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String CHECKSUM = "RBNvo1WzZ4oRRq0W9+hknpT7T8If536DEMBg9hyq/4o=";

    @Autowired
    private PrivacyJournal journal;

    @Autowired
    private PrivacyJournalShipping shipping;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @MockitoBean
    private PrivacyJournalObjectStore bucket;

    @Test
    void acceptsMinioConditionalWritesAndKeepsTheCredentialWithinTheJournalPrefix() throws Exception {
        // The legacy Bitnami distribution ships the MinIO server and its administration client in one image.
        try (var minio = new GenericContainer<>(
                        "bitnamilegacy/minio:2025.7.23-debian-12-r5@sha256:6dabb4a2088c9a79908de3bc05f4586c23ad2182c8908e7e3acbf61c1467fb20")
                // The image setup opens the port on a temporary server before the final server starts.
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("minio"))
                .withCommand("server", "--address", ":9000", "/tmp/journal-fixture")
                .withEnv("MINIO_ROOT_USER", "fixture-admin")
                .withEnv("MINIO_ROOT_PASSWORD", "fixture-admin-password")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000))) {
            minio.start();
            mc(minio, "alias", "set", "test", "http://127.0.0.1:9000", "fixture-admin", "fixture-admin-password");
            mc(minio, "mb", "test/" + BUCKET);
            minio.copyFileToContainer(Transferable.of("""
                            {
                              "Version": "2012-10-17",
                              "Statement": [{
                                "Effect": "Allow",
                                "Action": ["s3:PutObject", "s3:GetObject"],
                                "Resource": "arn:aws:s3:::test-archive/privacy-journal/*"
                              }]
                            }
                            """), "/tmp/journal-policy.json");
            mc(minio, "admin", "user", "add", "test", JOURNAL_USER, JOURNAL_PASSWORD);
            mc(minio, "admin", "policy", "create", "test", "journal-prefix", "/tmp/journal-policy.json");
            mc(minio, "admin", "policy", "attach", "test", "journal-prefix", "--user", JOURNAL_USER);
            var endpoint = URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000));
            try (var writer = store(endpoint, PREFIX);
                    var anotherWriter = store(endpoint, PREFIX);
                    var outsidePrefix = store(endpoint, "postgres-backups/")) {
                writer.verifyBucket();
                assertThat(writer.store(JOURNAL_KEY, BODY)).isTrue();
                anotherWriter.verifyBucket();
                assertThat(anotherWriter.store(JOURNAL_KEY, BODY)).isTrue();
                assertThat(anotherWriter.store(JOURNAL_KEY, "{\"other\":1}".getBytes(StandardCharsets.UTF_8)))
                        .isFalse();
                assertThat(outsidePrefix.store(JOURNAL_KEY, BODY)).isFalse();
                assertThat(writer.store(JOURNAL_KEY, BODY)).isTrue();

                var id = UUID.randomUUID();
                when(bucket.store(any(), any()))
                        .thenAnswer(request -> writer.store(request.getArgument(0), request.getArgument(1)));
                transactions.executeWithoutResult(status -> journal.record(
                        id, PrivacyJournalKind.MEMBERSHIP_ENDED, Map.of("workspaceId", 7L, "accountId", 1L)));
                shipping.confirm(id);
                assertThat(jdbc.queryForList(
                                "SELECT shipped_at IS NOT NULL FROM privacy_journal WHERE entry_id = ?",
                                Boolean.class,
                                id))
                        .containsExactly(true);
                var key = Objects.requireNonNull(jdbc.queryForObject("""
                        SELECT (recorded_at AT TIME ZONE 'UTC')::date::text || '/' || entry_id || '.json'
                        FROM privacy_journal WHERE entry_id = ?
                        """, String.class, id));
                var body = ArgumentCaptor.forClass(byte[].class);
                verify(bucket).store(eq(key), body.capture());
                var bytes = body.getValue();
                assertThat(new String(bytes, StandardCharsets.UTF_8)).contains(id.toString(), "MEMBERSHIP_ENDED");
                var checksum = Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));

                jdbc.update("DELETE FROM privacy_journal WHERE entry_id = ?", id);

                assertThat(writer.matches(PREFIX + key, checksum)).isTrue();
            }
        }
    }

    @Test
    void refusesABucketThatIgnoresTheConditionalWrite() throws Exception {
        try (var s3 = new MockWebServer()) {
            s3.start();
            s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
            s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
            try (var writer = store(s3.url("/").uri(), PREFIX)) {
                assertThatThrownBy(writer::verifyBucket)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("conditional PutObject and SHA-256");
            }
        }
    }

    @Test
    void refusesABucketThatCannotReturnTheRecordedChecksum() throws Exception {
        try (var s3 = new MockWebServer()) {
            s3.start();
            s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
            s3.enqueue(new MockResponse.Builder().code(412).add100Continue().build());
            s3.enqueue(new MockResponse.Builder().code(200).build());
            try (var writer = store(s3.url("/").uri(), PREFIX)) {
                assertThatThrownBy(writer::verifyBucket)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageEndingWith("the stored object did not return the expected SHA-256 checksum");
            }
        }
    }

    @Test
    void shouldKeepStartingAndVerifyLaterWhenTheBucketIsTemporarilyUnavailable() throws Exception {
        try (var s3 = new MockWebServer()) {
            s3.start();
            s3.enqueue(new MockResponse.Builder().code(503).add100Continue().build());
            try (var writer = store(s3.url("/").uri(), PREFIX)) {
                writer.verifyBucket();
                assertThat(writer.verified()).isFalse();

                s3.enqueue(new MockResponse.Builder().code(503).add100Continue().build());
                assertThat(writer.store(JOURNAL_KEY, BODY)).isFalse();
                assertThat(writer.verified()).isFalse();

                enqueueSuccessfulProbe(s3);
                s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
                s3.enqueue(new MockResponse.Builder()
                        .addHeader("x-amz-checksum-sha256", CHECKSUM)
                        .build());
                assertThat(writer.store(JOURNAL_KEY, BODY)).isTrue();
                assertThat(writer.verified()).isTrue();
            }
        }
    }

    @Test
    void confirmsTheStoredChecksumWhenThePutResponseIsLost() throws Exception {
        try (var s3 = new MockWebServer()) {
            s3.start();
            enqueueSuccessfulProbe(s3);
            s3.enqueue(new MockResponse.Builder()
                    .add100Continue()
                    .onResponseStart(SocketEffect.ShutdownConnection.INSTANCE)
                    .build());
            s3.enqueue(new MockResponse.Builder()
                    .addHeader("x-amz-checksum-sha256", CHECKSUM)
                    .build());
            try (var writer = store(s3.url("/").uri(), PREFIX)) {
                writer.verifyBucket();
                for (int request = 0; request < 3; request++) {
                    assertThat(s3.takeRequest(3, TimeUnit.SECONDS)).isNotNull();
                }
                assertThat(writer.store(JOURNAL_KEY, BODY)).isTrue();
                var put = Objects.requireNonNull(s3.takeRequest(3, TimeUnit.SECONDS));
                assertThat(put.getMethod()).isEqualTo("PUT");
                assertThat(put.getHeaders().get("If-None-Match")).isEqualTo("*");
                assertThat(put.getHeaders().get("x-amz-checksum-sha256")).isEqualTo(CHECKSUM);
                var head = Objects.requireNonNull(s3.takeRequest(3, TimeUnit.SECONDS));
                assertThat(head.getMethod()).isEqualTo("HEAD");
                assertThat(head.getHeaders().get("x-amz-checksum-mode")).isEqualTo("ENABLED");
            }
        }
    }

    private static void enqueueSuccessfulProbe(MockWebServer s3) throws Exception {
        var probe = "{\"privacyJournalProbe\":1}".getBytes(StandardCharsets.UTF_8);
        var checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(probe));
        s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
        s3.enqueue(new MockResponse.Builder().code(412).add100Continue().build());
        s3.enqueue(new MockResponse.Builder()
                .addHeader("x-amz-checksum-sha256", checksum)
                .build());
    }

    private static S3PrivacyJournalStore store(URI endpoint, String prefix) {
        return new S3PrivacyJournalStore(
                new PrivacyJournalProperties.S3(
                        BUCKET, prefix, endpoint, "us-east-1", true, null, JOURNAL_USER, JOURNAL_PASSWORD, 0),
                Clock.systemUTC());
    }

    private static void mc(GenericContainer<?> minio, String... args) throws Exception {
        var command = new String[args.length + 1];
        command[0] = "mc";
        System.arraycopy(args, 0, command, 1, args.length);
        var result = minio.execInContainer(command);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
}
