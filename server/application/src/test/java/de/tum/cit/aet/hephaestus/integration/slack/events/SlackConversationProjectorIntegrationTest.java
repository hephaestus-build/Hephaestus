package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Conversation-projector firewall + tombstone/edit integration tests (Testcontainers — the participant
 * union rides a real Postgres {@code bigint[]}/GIN column and {@code = ANY(...)}). Deterministic: rows are seeded
 * with distinct workspace ids per test, so no shared-container bleed. Proves the single privacy invariant — a
 * non-participant never sees a thread — and the consent gate + tombstone/edit projection.
 */
class SlackConversationProjectorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SlackConversationProjector projector;

    @Autowired
    private SlackMessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // Distinct workspace ids per test → isolation without a clean-between step (no FK to a workspace row).
    private static final AtomicLong WS_SEQ = new AtomicLong(9_000_000L);

    private long newWorkspace() {
        return WS_SEQ.incrementAndGet();
    }

    private SlackConversationTestSupport support;

    /** Add the raw-JDBC-only {@code slack_thread} columns to the entity-derived test schema — see {@link SlackConversationTestSupport}. */
    @BeforeEach
    void ensureUnmappedSlackThreadColumns() {
        support = new SlackConversationTestSupport(jdbc);
        support.ensureUnmappedSlackThreadColumns();
    }

    private void seedChannel(long workspaceId, String channelId, String consentState) {
        support.seedChannel(workspaceId, channelId, consentState);
    }

    /** Seed a thread aggregate with an explicit participant member-id set (text array literal → bigint[]). */
    private void seedThread(
            long workspaceId,
            String channelId,
            @Nullable String threadTs,
            String lastTs,
            String participantArrayLiteral) {
        support.seedThread(workspaceId, channelId, threadTs, lastTs, 1, participantArrayLiteral);
    }

    private void seedMessage(long workspaceId, String channelId, String ts, @Nullable String threadTs, String text) {
        support.seedMessage(workspaceId, channelId, ts, threadTs, text);
    }

    private ArrayNode conversations(ObjectNode payload) {
        return (ArrayNode) payload.get("conversations");
    }

    @Test
    void shouldStreamOnlyCurrentPermittedWorkspaceMessages() {
        long workspace = newWorkspace();
        long otherWorkspace = newWorkspace();
        seedChannel(workspace, "C1", "ACTIVE");
        seedChannel(workspace, "C2", "REVOKED");
        seedChannel(otherWorkspace, "C1", "ACTIVE");
        seedMessage(workspace, "C1", "1704067200.0", null, "permitted");
        seedMessage(workspace, "C1", "1704067201.0", null, "deleted");
        seedMessage(workspace, "C2", "1704067202.0", null, "revoked");
        seedMessage(otherWorkspace, "C1", "1704067203.0", null, "foreign");
        messageRepository.tombstone(workspace, "T1", "C1", "1704067201.0", java.time.Instant.now());
        var captured = new java.util.ArrayList<ObjectNode>();
        projector.forEachWorkspaceMessage(workspace, captured::add);
        assertThat(captured).singleElement().satisfies(record -> {
            assertThat(record.path("text").asString()).isEqualTo("permitted");
            assertThat(record.path("month").asString()).isEqualTo("2024-01");
            assertThat(record.path("synced_at").asString()).isNotBlank();
        });
        assertThat(projector.isMessageReadable(workspace, "C1", "1704067200.0")).isTrue();
        jdbc.update(
                "UPDATE slack_monitored_channel SET consent_state='REVOKED' WHERE workspace_id=? AND slack_channel_id='C1'",
                workspace);
        assertThat(projector.isMessageReadable(workspace, "C1", "1704067200.0")).isFalse();
        captured.clear();
        projector.forEachWorkspaceMessage(workspace, captured::add);
        assertThat(captured).isEmpty();
    }

    @Test
    @DisplayName("participant firewall: a non-participant never sees the thread; a participant does")
    void participantFirewall() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");
        // Thread's participants are members 100 and 101 only.
        seedThread(ws, "C1", "100.0", "100.5", "{100,101}");
        seedMessage(ws, "C1", "100.0", null, "root");
        seedMessage(ws, "C1", "100.5", "100.0", "reply");

        // A participant sees the whole thread…
        ObjectNode forMember = projector.buildPayload(ws, 100L);
        assertThat(conversations(forMember)).hasSize(1);
        assertThat(conversations(forMember).get(0).get("messages")).hasSize(2);

        // …a workspace peer who never took part sees NOTHING.
        ObjectNode forOutsider = projector.buildPayload(ws, 999L);
        assertThat(conversations(forOutsider)).isEmpty();
        assertThat(forOutsider.get("totalThreads").asInt()).isZero();
    }

    @Test
    @DisplayName("consent gate: a participant of a non-ACTIVE channel's thread sees nothing")
    void consentGateHidesNonActiveChannels() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "PENDING");
        seedThread(ws, "C1", "100.0", "100.0", "{100}");
        seedMessage(ws, "C1", "100.0", null, "root");

        assertThat(conversations(projector.buildPayload(ws, 100L))).isEmpty();

        // Revoking a formerly-active channel also drops it out immediately.
        long ws2 = newWorkspace();
        seedChannel(ws2, "C2", "REVOKED");
        seedThread(ws2, "C2", "200.0", "200.0", "{100}");
        seedMessage(ws2, "C2", "200.0", null, "root");
        assertThat(conversations(projector.buildPayload(ws2, 100L))).isEmpty();
    }

    @Test
    @DisplayName("tenant isolation: a participant id shared across workspaces sees only its own workspace's thread")
    void workspaceIsolation() {
        long wsA = newWorkspace();
        long wsB = newWorkspace();
        seedChannel(wsA, "C1", "ACTIVE");
        seedThread(wsA, "C1", "100.0", "100.0", "{100}");
        seedMessage(wsA, "C1", "100.0", null, "A-only");
        seedChannel(wsB, "C1", "ACTIVE");
        seedThread(wsB, "C1", "100.0", "100.0", "{100}");
        seedMessage(wsB, "C1", "100.0", null, "B-only");

        ObjectNode forA = projector.buildPayload(wsA, 100L);
        assertThat(conversations(forA)).hasSize(1);
        assertThat(conversations(forA).get(0).get("messages").get(0).get("text").asString())
                .isEqualTo("A-only");
    }

    @Test
    @DisplayName("tombstone + edit: a deleted message drops out; an edited message shows its new text and edited flag")
    void tombstoneAndEditProjection() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", "100.0", "100.5", "{100}");
        seedMessage(ws, "C1", "100.0", null, "root will be edited");
        seedMessage(ws, "C1", "100.5", "100.0", "reply will be deleted");

        // Edit the root, tombstone the reply (both via the scoped repository writes the ingest path drives).
        assertThat(messageRepository.applyEdit(ws, "C1", "100.0", "root EDITED", java.time.Instant.now()))
                .isEqualTo(1);
        assertThat(messageRepository.tombstone(ws, "T1", "C1", "100.5", java.time.Instant.now()))
                .isEqualTo(1);

        ObjectNode payload = projector.buildPayload(ws, 100L);
        ArrayNode messages = (ArrayNode) conversations(payload).get(0).get("messages");

        // Only the surviving (edited) root remains; the tombstoned reply is gone.
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("text").asString()).isEqualTo("root EDITED");
        assertThat(messages.get(0).get("edited").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("durable tombstone: a delete arriving before its base insert cannot be resurrected by the reorder")
    void tombstoneBeforeInsert_isNotResurrected() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");

        // JetStream reorder: the message_deleted for ts 100.9 is processed BEFORE its base insert (e.g. the insert
        // was NAK'd and redelivered later). The durable upsert writes a contentless tombstone for that ts.
        assertThat(messageRepository.tombstone(ws, "T1", "C1", "100.9", java.time.Instant.now()))
                .isEqualTo(1);

        // The reordered base insert now arrives — ON CONFLICT DO NOTHING must NOT bring the deleted content back.
        assertThat(messageRepository.insertIfAbsent(ws, "T1", "C1", "100.9", "100.0", "U1", 100L, "resurrected?"))
                .isZero();

        // The row stays a contentless tombstone: text NULL, deleted_at set.
        Map<String, @Nullable Object> row = jdbc.queryForMap(
                "SELECT text, deleted_at FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ? AND slack_ts = ?",
                ws,
                "C1",
                "100.9");
        assertThat(row.get("text")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
    }

    @Test
    @DisplayName(
            "durable edit: an edit arriving before its base insert re-ingests the EDITED text; the reordered base insert cannot clobber it")
    void editBeforeInsert_isDurable() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", "100.0", "100.9", "{100}");

        // JetStream reorder: message_changed for ts 100.9 is processed BEFORE its base insert. The scoped UPDATE finds
        // no row (returns 0) and the row is genuinely absent — the durability primitive the service branches on.
        assertThat(messageRepository.applyEdit(ws, "C1", "100.9", "EDITED body", java.time.Instant.now()))
                .isZero();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(ws, "C1", "100.9"))
                .isFalse();

        // The service's durable branch re-ingests the EDITED body (through the full consent stack) and stamps
        // edited_at.
        assertThat(messageRepository.insertIfAbsent(ws, "T1", "C1", "100.9", "100.0", "U1", 100L, "EDITED body"))
                .isEqualTo(1);
        assertThat(messageRepository.applyEdit(ws, "C1", "100.9", "EDITED body", java.time.Instant.now()))
                .isEqualTo(1);

        // The reordered base insert now arrives carrying the ORIGINAL text — ON CONFLICT DO NOTHING must NOT clobber
        // the durably-stored edited body (this is the invariant that fails if edits regress to lossy).
        assertThat(messageRepository.insertIfAbsent(ws, "T1", "C1", "100.9", "100.0", "U1", 100L, "original base text"))
                .isZero();

        // The projector shows the edited text with the edited flag set — the edit survived the reorder.
        ObjectNode payload = projector.buildPayload(ws, 100L);
        ArrayNode messages = (ArrayNode) conversations(payload).get(0).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("text").asString()).isEqualTo("EDITED body");
        assertThat(messages.get(0).get("edited").asBoolean()).isTrue();
    }
}
