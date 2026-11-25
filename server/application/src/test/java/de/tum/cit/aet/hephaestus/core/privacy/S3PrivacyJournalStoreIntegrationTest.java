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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

@Tag("integration")
class S3PrivacyJournalStoreIntegrationTest extends BaseIntegrationTest {

    private static final String PREFIX = JournalMinio.PREFIX;
    private static final String JOURNAL_KEY = "instance/2026-11-24/entry.json";
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
        try (var minio = new JournalMinio()) {
            try (var writer = minio.store(PREFIX);
                    var anotherWriter = minio.store(PREFIX);
                    var outsidePrefix = minio.store("postgres-backups/")) {
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
    void shouldConfirmTheStoredObjectWithoutRewritingItAfterTheJournalTableIsRestored() throws Exception {
        try (var minio = new JournalMinio();
                var writer = minio.store(PREFIX)) {
            // A rewrite of the same bytes keeps the ETag. A versioned bucket counts it as a second version.
            minio.keepVersions();
            writer.verifyBucket();
            when(bucket.store(any(), any()))
                    .thenAnswer(request -> writer.store(request.getArgument(0), request.getArgument(1)));
            var id = UUID.randomUUID();
            transactions.executeWithoutResult(status -> journal.record(
                    id, PrivacyJournalKind.MEMBERSHIP_ENDED, Map.of("workspaceId", 7L, "accountId", 1L)));
            // The backup holds the fact as it committed, before it shipped.
            var backup = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT to_jsonb(j)::text FROM privacy_journal j WHERE entry_id = ?", String.class, id));
            shipping.confirm(id);
            var key = PREFIX + jdbc.queryForObject("""
                            SELECT (recorded_at AT TIME ZONE 'UTC')::date::text || '/' || entry_id || '.json'
                            FROM privacy_journal WHERE entry_id = ?
                            """, String.class, id);
            var stored = minio.object(key);
            assertThat(minio.versions(key)).isOne();

            jdbc.update("DELETE FROM privacy_journal WHERE entry_id = ?", id);
            jdbc.update(
                    "INSERT INTO privacy_journal SELECT * FROM jsonb_populate_record(NULL::privacy_journal, CAST(? AS jsonb))",
                    backup);
            assertThat(shipped(id)).isFalse();

            shipping.confirm(id);

            assertThat(shipped(id)).isTrue();
            assertThat(minio.versions(key)).isOne();
            assertThat(minio.object(key)).isEqualTo(stored);
        }
    }

    private boolean shipped(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT shipped_at IS NOT NULL FROM privacy_journal WHERE entry_id = ?", Boolean.class, id));
    }

    @Test
    void refusesABucketThatIgnoresTheConditionalWrite() throws Exception {
        try (var s3 = new MockWebServer()) {
            s3.start();
            s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
            s3.enqueue(new MockResponse.Builder().code(200).add100Continue().build());
            try (var writer = JournalMinio.store(s3.url("/").uri(), PREFIX)) {
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
            try (var writer = JournalMinio.store(s3.url("/").uri(), PREFIX)) {
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
            try (var writer = JournalMinio.store(s3.url("/").uri(), PREFIX)) {
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
            try (var writer = JournalMinio.store(s3.url("/").uri(), PREFIX)) {
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
}
