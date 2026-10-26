package de.tum.cit.aet.hephaestus.integration.slack.events;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.chat.item.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.slack.SlackConversationTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.conversation.SlackConversationProjector;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    private long newWorkspace() {
        var workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("projector-" + UUID.randomUUID()));
        connect(workspace);
        return workspace.getId();
    }

    private void connect(Workspace workspace) {
        var connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T" + workspace.getId(),
                new ConnectionConfig.SlackConfig("T" + workspace.getId(), null, null, Set.of()));
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
    }

    private SlackConversationTestSupport support;

    @BeforeEach
    void setUpSourceFixtures() {
        support = new SlackConversationTestSupport(jdbc);
    }

    private void seedChannel(long workspaceId, String channelId, String consentState) {
        support.seedChannel(workspaceId, "T" + workspaceId, channelId, consentState);
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
        support.seedMessage(workspaceId, "T" + workspaceId, channelId, ts, threadTs, text);
    }

    private ArrayNode conversations(ObjectNode payload) {
        return (ArrayNode) payload.get("conversations");
    }

    @Test
    void shouldExcludeAnErasedNativeAuthorFromEveryProjectionWithoutAliasingAnotherSlackTeam() {
        long workspace = newWorkspace();
        long otherWorkspace = newWorkspace();
        seedChannel(workspace, "C1", "ACTIVE");
        seedChannel(otherWorkspace, "C1", "ACTIVE");
        seedThread(workspace, "C1", "100.0", "100.1", "{100}");
        seedMessage(workspace, "C1", "100.0", null, "suppressed root");
        seedMessage(workspace, "C1", "100.1", "100.0", "other participant reply");
        seedMessage(otherWorkspace, "C1", "200.0", null, "same native user in another team");
        jdbc.update(
                "UPDATE slack_message SET author_slack_user_id='UPRIVACYTARGET' WHERE workspace_id=? AND"
                        + " slack_ts='100.0'",
                workspace);
        jdbc.update(
                "UPDATE slack_message SET author_slack_user_id='UPRIVACYTARGET' WHERE" + " workspace_id=?",
                otherWorkspace);
        Long providerId = jdbc.queryForObject(
                "INSERT INTO identity_provider(type,server_url,created_at) VALUES"
                        + " ('SLACK','https://slack.com',CURRENT_TIMESTAMP) ON CONFLICT(type,server_url) DO UPDATE SET"
                        + " server_url=EXCLUDED.server_url RETURNING id",
                Long.class);
        databaseTestUtils.erase(Objects.requireNonNull(providerId), "UPRIVACYTARGET", "T" + workspace);
        var captured = new ArrayList<ObjectNode>();
        projector.forEachWorkspaceMessage(workspace, captured::add);
        assertThat(captured)
                .singleElement()
                .satisfies(record -> assertThat(record.path("text").asString()).isEqualTo("other participant reply"));
        assertThat(projector.isMessageReadable(workspace, "C1", "100.0")).isFalse();
        assertThat(projector.isMessageReadable(otherWorkspace, "C1", "200.0")).isTrue();
        var audience = conversations(projector.buildPayload(workspace, 100L));
        assertThat(audience).singleElement().satisfies(thread -> {
            assertThat(thread.path("messageCount").asInt()).isZero();
            assertThat(thread.path("messages")).isEmpty();
            assertThat(thread.path("truncated").asBoolean()).isTrue();
        });
        var withheldThread = projector.threadPayload(workspace, "C1", "100.0");
        assertThat(withheldThread.path("messages")).isEmpty();
        assertThat(projector.threadReadability(workspace, "C1", "100.0"))
                .isEqualTo(ConversationThreadProjection.ThreadReadability.CONSENT_NOT_ACTIVE);
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
        messageRepository.tombstone(workspace, "T" + workspace, "C1", "1704067201.0", Instant.now());
        var captured = new ArrayList<ObjectNode>();
        projector.forEachWorkspaceMessage(workspace, captured::add);
        assertThat(captured).singleElement().satisfies(record -> {
            assertThat(record.path("text").asString()).isEqualTo("permitted");
            assertThat(record.path("month").asString()).isEqualTo("2024-01");
            assertThat(record.path("synced_at").asString()).isNotBlank();
        });
        assertThat(projector.isMessageReadable(workspace, "C1", "1704067200.0")).isTrue();
        jdbc.update(
                "UPDATE slack_monitored_channel SET consent_state='REVOKED' WHERE workspace_id=? AND"
                        + " slack_channel_id='C1'",
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
        assertThat(messageRepository.applyEdit(ws, "C1", "100.0", "root EDITED", Instant.now()))
                .isEqualTo(1);
        assertThat(messageRepository.tombstone(ws, "T" + ws, "C1", "100.5", Instant.now()))
                .isEqualTo(1);

        ObjectNode payload = projector.buildPayload(ws, 100L);
        ArrayNode messages = (ArrayNode) conversations(payload).get(0).get("messages");

        // Only the surviving (edited) root remains; the tombstoned reply is gone.
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("text").asString()).isEqualTo("root EDITED");
        assertThat(messages.get(0).get("edited").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("ordered edit: an older or redelivered edit never replaces a later one")
    void olderEdit_keepsTheLaterText() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");
        seedMessage(ws, "C1", "100.0", null, "original");
        Instant first = Instant.parse("2026-10-26T10:00:00Z");
        Instant second = first.plusSeconds(60);

        assertThat(messageRepository.applyEdit(ws, "C1", "100.0", "first edit", first))
                .isEqualTo(1);
        assertThat(messageRepository.applyEdit(ws, "C1", "100.0", "second edit", second))
                .isEqualTo(1);
        assertThat(messageRepository.applyEdit(ws, "C1", "100.0", "first edit", first))
                .isZero();
        assertThat(messageRepository.applyEdit(ws, "C1", "100.0", "second edit", second))
                .isZero();

        assertThat(jdbc.queryForObject(
                        "SELECT text FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ? AND slack_ts = ?",
                        String.class,
                        ws,
                        "C1",
                        "100.0"))
                .isEqualTo("second edit");
    }

    @Test
    @DisplayName("durable tombstone: a delete arriving before its base insert cannot be resurrected by the reorder")
    void tombstoneBeforeInsert_isNotResurrected() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");

        // JetStream reorder: the message_deleted for ts 100.9 is processed BEFORE its base insert (e.g. the insert
        // was NAK'd and redelivered later). The durable upsert writes a contentless tombstone for that ts.
        assertThat(messageRepository.tombstone(ws, "T" + ws, "C1", "100.9", Instant.now()))
                .isEqualTo(1);

        // The reordered base insert now arrives — ON CONFLICT DO NOTHING must NOT bring the deleted content back.
        assertThat(messageRepository.insertIfAbsent(ws, "T" + ws, "C1", "100.9", "100.0", "U1", null, "resurrected?"))
                .isZero();

        // The row stays a contentless tombstone: text NULL, deleted_at set.
        Map<String, @Nullable Object> row = jdbc.queryForMap(
                "SELECT text, deleted_at FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ? AND"
                        + " slack_ts = ?",
                ws,
                "C1",
                "100.9");
        assertThat(row.get("text")).isNull();
        assertThat(row.get("deleted_at")).isNotNull();
    }

    @Test
    @DisplayName("durable edit: an edit arriving before its base insert re-ingests the EDITED text; the reordered base"
            + " insert cannot clobber it")
    void editBeforeInsert_isDurable() {
        long ws = newWorkspace();
        seedChannel(ws, "C1", "ACTIVE");
        seedThread(ws, "C1", "100.0", "100.9", "{100}");

        // JetStream reorder: message_changed for ts 100.9 is processed BEFORE its base insert. The scoped UPDATE finds
        // no row (returns 0) and the row is genuinely absent — the durability primitive the service branches on.
        assertThat(messageRepository.applyEdit(ws, "C1", "100.9", "EDITED body", Instant.now()))
                .isZero();
        assertThat(messageRepository.existsByWorkspaceIdAndSlackChannelIdAndSlackTs(ws, "C1", "100.9"))
                .isFalse();

        // The service's durable branch re-ingests the EDITED body (through the full consent stack) and stamps
        // edited_at.
        assertThat(messageRepository.insertIfAbsent(ws, "T" + ws, "C1", "100.9", "100.0", "U1", null, "EDITED body"))
                .isEqualTo(1);
        assertThat(messageRepository.applyEdit(ws, "C1", "100.9", "EDITED body", Instant.now()))
                .isEqualTo(1);

        // The reordered base insert now arrives carrying the ORIGINAL text — ON CONFLICT DO NOTHING must NOT clobber
        // the durably-stored edited body (this is the invariant that fails if edits regress to lossy).
        assertThat(messageRepository.insertIfAbsent(
                        ws, "T" + ws, "C1", "100.9", "100.0", "U1", null, "original base text"))
                .isZero();

        // The projector shows the edited text with the edited flag set — the edit survived the reorder.
        ObjectNode payload = projector.buildPayload(ws, 100L);
        ArrayNode messages = (ArrayNode) conversations(payload).get(0).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).get("text").asString()).isEqualTo("EDITED body");
        assertThat(messages.get(0).get("edited").asBoolean()).isTrue();
    }
}
