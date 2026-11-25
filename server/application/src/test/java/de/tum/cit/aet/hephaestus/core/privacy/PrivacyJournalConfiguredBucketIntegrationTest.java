package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * An installation on an external database names its journal bucket with the documented settings, and the server's own
 * store writes each synchronous fact there before the reply. The gate holds that write at the S3 boundary.
 */
@Tag("integration")
// The context's store points at this class's bucket, which closes after the class.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PrivacyJournalConfiguredBucketIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final JournalMinio MINIO = start(JournalMinio::new);
    private static final JournalPutGate GATE = start(() -> new JournalPutGate(MINIO.endpoint()));

    @AfterAll
    static void closeTheBucket() throws Exception {
        GATE.close();
        MINIO.close();
    }

    @DynamicPropertySource
    static void journalBucket(DynamicPropertyRegistry registry) {
        registry.add("hephaestus.privacy.journal.s3.bucket", () -> JournalMinio.BUCKET);
        registry.add("hephaestus.privacy.journal.s3.prefix", () -> JournalMinio.PREFIX);
        registry.add(
                "hephaestus.privacy.journal.s3.endpoint", () -> GATE.endpoint().toString());
        registry.add("hephaestus.privacy.journal.s3.path-style", () -> "true");
        registry.add("hephaestus.privacy.journal.s3.access-key-id", () -> JournalMinio.JOURNAL_USER);
        registry.add("hephaestus.privacy.journal.s3.secret-access-key", () -> JournalMinio.JOURNAL_PASSWORD);
    }

    @Autowired
    private WebTestClient client;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void shouldHoldTheReplyUntilTheConfiguredBucketStoresTheFact() throws Exception {
        var user = persistUser("journal-configured");
        var workspace = createWorkspace(
                "journal-configured", "Journal configured", "journal-configured", AccountType.ORG, user);
        ensureMembership(workspace, user, WorkspaceRole.OWNER);
        var account = accountId(user);
        var thread = conversations.findOrCreateMain(workspace.getId(), account);
        var entry = entries.append(
                workspace.getId(),
                thread.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.USER,
                        ConversationEntry.Surface.APP,
                        "Delete this question",
                        null,
                        null,
                        null,
                        null));

        CompletableFuture<HttpStatusCode> reply = CompletableFuture.supplyAsync(() -> client.delete()
                .uri("/workspaces/{slug}/conversation/entries/{entryId}", workspace.getWorkspaceSlug(), entry.getId())
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + account))
                .exchange()
                .returnResult(Void.class)
                .getStatus());
        try {
            var put = GATE.awaitHeld(Duration.ofSeconds(30));
            var factId = Objects.requireNonNull(jdbc.queryForObject(
                    "SELECT entry_id FROM privacy_journal WHERE kind = 'ENTRY_DELETED' AND refs->>'entryId' = ?",
                    UUID.class,
                    entry.getId().toString()));
            assertThat(put).contains(factId + ".json");

            // The deletion and its fact are committed, and the reply waits for the copy.
            assertThatThrownBy(() -> reply.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(entries.find(workspace.getId(), entry.getId())
                            .orElseThrow()
                            .isRedacted())
                    .isTrue();
            assertThat(shipped(factId)).isFalse();

            GATE.release();
            assertThat(reply.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(shipped(factId)).isTrue();
            // A restore replays the fact from its recovery references.
            var key = put.substring(put.indexOf(JournalMinio.PREFIX), put.indexOf(".json") + ".json".length());
            var stored = mapper.readTree(MINIO.object(key));
            assertThat(stored.path("entryId").asString()).isEqualTo(factId.toString());
            assertThat(stored.path("kind").asString()).isEqualTo("ENTRY_DELETED");
            assertThat(stored.path("refs").path("entryId").asString())
                    .isEqualTo(entry.getId().toString());
            assertThat(stored.path("refs").path("workspaceId").asLong()).isEqualTo(workspace.getId());
        } finally {
            // A failed assertion must not leave the request waiting on the gate.
            GATE.release();
            reply.handle((status, failure) -> status).get(30, TimeUnit.SECONDS);
        }
    }

    private boolean shipped(UUID factId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT shipped_at IS NOT NULL FROM privacy_journal WHERE entry_id = ?", Boolean.class, factId));
    }

    private interface Start<T> {
        T get() throws Exception;
    }

    /** The bucket must answer before the context starts, because the store probes it when it is created. */
    private static <T> T start(Start<T> start) {
        try {
            return start.get();
        } catch (Exception failure) {
            throw new IllegalStateException("The journal bucket did not start", failure);
        }
    }
}
