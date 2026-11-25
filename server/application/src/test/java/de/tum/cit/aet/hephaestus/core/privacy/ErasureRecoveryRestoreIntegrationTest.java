package de.tum.cit.aet.hephaestus.core.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryDraft;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredCollectionErasures;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredDisconnects;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredMessageDeletions;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackChannelErasures;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredSlackMessageChanges;
import de.tum.cit.aet.hephaestus.core.privacy.spi.RestoredWorkspacePurges;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnBackgroundWork;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.InstallationCredential;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocument;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.slack.SlackThreadTestSupport;
import de.tum.cit.aet.hephaestus.integration.slack.events.SlackChannelErasure;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceLifecycleService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * An erasure after the recovery point, a restore to that point, then the recovery profile: the restored data is erased
 * again. The restore puts back every row of the workspace as the backup held it, as a physical restore does: no
 * trigger or foreign key runs while it writes.
 */
class ErasureRecoveryRestoreIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @TempDir
    Path directory;

    @Autowired
    private PersonDataService service;

    @Autowired
    private PersonIdentityResolver resolver;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private ApplicationContext beans;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private CredentialBundleConverter credentials;

    @Autowired
    private List<ConnectionStrategy> strategies;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private WorkspaceLifecycleService lifecycle;

    @Autowired
    private SyncJobRepository syncJobs;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService turns;

    @Autowired
    private ConversationEntryService entries;

    @Autowired
    private SlackChannelErasure channelErasure;

    @Autowired
    private OutlineCollectionRepository outlineCollections;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private OutlineDocumentRepository outlineDocuments;

    private Workspace workspace;
    private Connection connection;
    private long ownerAccount;

    @BeforeEach
    void setUp() {
        String suffix = Long.toString(System.nanoTime());
        var owner = persistUser("restore-erase-owner-" + suffix);
        workspace = createWorkspace(
                "restore-erase-" + suffix, "Restore Erase", "restore-org-" + suffix, AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        ownerAccount = accountId(owner);
        long installationId = System.nanoTime();
        var github = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installationId),
                new ConnectionConfig.GitHubAppConfig(installationId, workspace.getAccountLogin(), null, Set.of()));
        github.setCredentials(new InstallationCredential(installationId, "app"), credentials);
        ConnectionFixtures.reachState(github, IntegrationState.ACTIVE);
        connection = connections.save(github);
        var monitor = new RepositoryToMonitor();
        monitor.setNativeId(1L);
        monitor.setNameWithOwner(workspace.getAccountLogin() + "/restore-erase");
        monitor.setWorkspace(workspace);
        monitors.save(monitor);
    }

    @Test
    void shouldDisconnectAgainAConnectionThatARestoreBroughtBack() throws Exception {
        var backup = Backup.of(jdbc, workspace.getId());
        connectionService.disconnect(
                connection,
                ConnectionService.TransitionRequest.byAccount(
                        IntegrationState.UNINSTALLED,
                        "DISCONNECT",
                        "ADMIN",
                        persistAccount("restore administrator " + UUID.randomUUID())
                                .getId(),
                        UUID.randomUUID().toString(),
                        null),
                strategy(IntegrationKind.GITHUB));
        assertThat(monitors.findByWorkspaceId(workspace.getId())).isEmpty();

        backup.restore();
        assertThat(state()).as("the restore brought the connection back").isEqualTo(IntegrationState.ACTIVE);
        assertThat(monitors.findByWorkspaceId(workspace.getId())).hasSize(1);

        String manifest = """
                {"version":4,"slackMessageChanges":[],"slackChannelErasures":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[{"workspaceId":%d,"connectionId":%d}]}
                """.formatted(workspace.getId(), connection.getId());
        recover(manifest);

        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(connections.findById(connection.getId()).orElseThrow().getCredentialsEncrypted())
                .as("erasure releases the credential at once")
                .isNull();
        assertThat(monitors.findByWorkspaceId(workspace.getId())).isEmpty();
        // The same manifest is safe after a restart.
        recover(manifest);
        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);

        // A later backup holds the connection reconnected on the same row, beside this recovery's audit history.
        var history = jdbc.queryForList("SELECT * FROM connection_audit WHERE workspace_id = ?", workspace.getId());
        backup.with("connection_audit", history).restore();
        assertThat(state()).isEqualTo(IntegrationState.ACTIVE);
        recover(manifest);
        assertThat(state())
                .as("an earlier recovery's audit row does not make the disconnect look recorded")
                .isEqualTo(IntegrationState.UNINSTALLED);
    }

    @Test
    void shouldPurgeAgainAWorkspaceThatARestoreBroughtBack() throws Exception {
        var backup = Backup.of(jdbc, workspace.getId());
        lifecycle.purgeWorkspace(workspace.getWorkspaceSlug());
        assertThat(status()).isEqualTo("PURGED");

        backup.restore();
        assertThat(status()).as("the restore brought the workspace back").isEqualTo("ACTIVE");
        assertThat(monitors.findByWorkspaceId(workspace.getId())).hasSize(1);
        assertThat(memberships()).isPositive();
        // Work in flight at the recovery point stays in the restore; the guards that would wait for it do not run.
        syncJobs.saveAndFlush(new SyncJob(
                workspace,
                connection,
                IntegrationKind.GITHUB,
                SyncJobType.RECONCILIATION,
                SyncJobTrigger.MANUAL,
                null));

        String manifest = """
                {"version":4,"slackMessageChanges":[],"slackChannelErasures":[],"collectionErasures":[],"messageDeletions":[],"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[{"workspaceId":%d}],"disconnects":[]}
                """.formatted(workspace.getId());
        recover(manifest);

        assertThat(status()).isEqualTo("PURGED");
        assertThat(monitors.findByWorkspaceId(workspace.getId())).isEmpty();
        assertThat(memberships()).isZero();
        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);
        recover(manifest);
        assertThat(status()).isEqualTo("PURGED");
    }

    @Test
    void shouldDeleteAgainAMessageThatARestoreBroughtBack() throws Exception {
        UUID message = question("Words to take back");
        var backup = Backup.of(jdbc, workspace.getId());
        entries.deleteMessage(workspace.getId(), message);
        assertThat(words(message)).doesNotContain("Words to take back");

        backup.restore();
        assertThat(words(message)).as("the restore brought the words back").contains("Words to take back");

        String manifest = """
                {"version":4,"slackMessageChanges":[],"slackChannelErasures":[],"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[],"collectionErasures":[],
                 "messageDeletions":[{"workspaceId":%d,"entryId":"%s"}]}
                """.formatted(workspace.getId(), message);
        recover(manifest);

        assertThat(words(message)).doesNotContain("Words to take back");
        assertThat(entries.find(workspace.getId(), message).orElseThrow().isRedacted())
                .isTrue();
        // The same manifest is safe after a restart.
        recover(manifest);
        assertThat(entries.find(workspace.getId(), message).orElseThrow().isRedacted())
                .isTrue();
    }

    @Test
    void shouldEraseAgainTheOldWordsOfSlackMessagesThatTheirAuthorsChangedAfterTheRestorePoint() throws Exception {
        long edited = slackMessage("1700000001.000100", "Words before the edit");
        long deleted = slackMessage("1700000002.000200", "Words before the delete");
        var backup = Backup.of(jdbc, workspace.getId());
        // The authors' changes in Slack: an edit replaces the words, a delete tombstones the message.
        jdbc.update(
                "UPDATE slack_message SET text = 'Words after the edit', edited_at = now() WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                edited);
        jdbc.update(
                "UPDATE slack_message SET text = NULL, deleted_at = now() WHERE workspace_id = ? AND id = ?",
                workspace.getId(),
                deleted);

        backup.restore();
        assertThat(slackText(edited)).as("the restore brought the words back").isEqualTo("Words before the edit");
        assertThat(slackText(deleted)).isEqualTo("Words before the delete");

        String manifest = """
                {"version":4,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],
                 "slackChannelErasures":[],
                 "slackMessageChanges":[{"workspaceId":%d,"messageId":%d,"change":"EDITED"},
                                        {"workspaceId":%d,"messageId":%d,"change":"DELETED"}]}
                """.formatted(workspace.getId(), edited, workspace.getId(), deleted);
        recover(manifest);

        assertThat(slackText(edited)).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT edited_at IS NULL AND deleted_at IS NULL FROM slack_message WHERE id = ?",
                        Boolean.class,
                        edited))
                .as("the edited message stays, and the next history read stores Slack's words")
                .isTrue();
        assertThat(slackText(deleted)).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT deleted_at IS NOT NULL FROM slack_message WHERE id = ?", Boolean.class, deleted))
                .isTrue();
        // The same manifest is safe after a restart.
        recover(manifest);
        assertThat(slackText(edited)).isNull();
        assertThat(slackText(deleted)).isNull();
    }

    @Test
    void shouldEraseAgainASlackChannelThatARestoreBroughtBack() throws Exception {
        jdbc.update(
                "INSERT INTO slack_monitored_channel (workspace_id, slack_team_id, slack_channel_id, consent_state, created_at)"
                        + " VALUES (?, 'T-restore', 'C-erased', 'ACTIVE', '2025-12-31T00:00:00Z')",
                workspace.getId());
        long kept = slackMessage("1700000003.000300", "Words in a channel that is later erased");
        jdbc.update("UPDATE slack_message SET slack_channel_id = 'C-erased' WHERE id = ?", kept);
        new SlackThreadTestSupport(jdbc)
                .seedThread(workspace.getId(), "C-erased", "1700000003.000300", "1700000003.000300", 1, "{}");
        var backup = Backup.of(jdbc, workspace.getId());
        // The admin's revoke: the same erasure that Slack's channel_deleted runs.
        channelErasure.erase(workspace.getId(), "C-erased", Instant.now());
        assertThat(slackRows("C-erased")).isZero();

        backup.restore();
        assertThat(slackRows("C-erased"))
                .as("the restore brought the channel's messages back")
                .isOne();
        assertThat(slackThreads("C-erased")).isOne();
        assertThat(consent("C-erased")).isEqualTo("ACTIVE");

        String manifest = """
                {"version":4,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],
                 "slackMessageChanges":[],
                 "slackChannelErasures":[{"workspaceId":%d,"channelId":"C-erased"}]}
                """.formatted(workspace.getId());
        recover(manifest);

        assertThat(slackRows("C-erased")).isZero();
        assertThat(slackThreads("C-erased")).isZero();
        assertThat(consent("C-erased")).isEqualTo("REVOKED");
        // The same manifest is safe after a restart.
        recover(manifest);
        assertThat(slackRows("C-erased")).isZero();
    }

    @Test
    void shouldPreserveTheNewConsentPeriodWhenTheSameChannelErasureReplays() throws Exception {
        jdbc.update("""
                INSERT INTO slack_monitored_channel
                    (workspace_id, slack_team_id, slack_channel_id, consent_state, created_at)
                VALUES (?, 'T-restore', 'C-erased', 'ACTIVE', '2025-12-31T00:00:00Z')
                """, workspace.getId());
        long earlier = slackMessage("1700000003.000300", "Earlier consent");
        jdbc.update("UPDATE slack_message SET slack_channel_id = 'C-erased' WHERE id = ?", earlier);
        String manifest = """
                {"version":4,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],
                 "slackMessageChanges":[],
                 "slackChannelErasures":[{"workspaceId":%d,"channelId":"C-erased","erasedAt":"2026-01-02T00:00:00Z"}]}
                """.formatted(workspace.getId());

        recover(manifest);
        assertThat(consent("C-erased")).isEqualTo("REVOKED");
        assertThat(slackRows("C-erased")).isZero();

        // Re-adding a channel reuses its row and records a new PENDING transition.
        jdbc.update("""
                INSERT INTO slack_channel_consent_event
                    (workspace_id, slack_channel_id, from_state, to_state, created_at)
                VALUES (?, 'C-erased', 'REVOKED', 'PENDING', '2026-01-03T00:00:00Z')
                """, workspace.getId());
        jdbc.update("""
                UPDATE slack_monitored_channel SET consent_state = 'ACTIVE'
                WHERE workspace_id = ? AND slack_channel_id = 'C-erased'
                """, workspace.getId());
        long later = slackMessage("1767484800.000001", "New consent");
        jdbc.update("UPDATE slack_message SET slack_channel_id = 'C-erased' WHERE id = ?", later);
        new SlackThreadTestSupport(jdbc)
                .seedThread(workspace.getId(), "C-erased", "1767484800.000001", "1767484800.000001", 1, "{}");

        recover(manifest);

        assertThat(consent("C-erased")).isEqualTo("ACTIVE");
        assertThat(slackText(later)).isEqualTo("New consent");
        assertThat(slackThreads("C-erased")).isOne();
    }

    @Test
    void shouldRedactCopyingToolResultsOnlyThroughTheChannelErasureOnReplay() throws Exception {
        UUID before = copyingToolResult("2026-01-01T00:00:00Z", "Earlier channel words");
        UUID after = copyingToolResult("2026-01-03T00:00:00Z", "Later work");
        String manifest = """
                {"version":4,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[],"collectionErasures":[],"messageDeletions":[],
                 "slackMessageChanges":[],
                 "slackChannelErasures":[{"workspaceId":%d,"channelId":"C-erased"%s}]}
                """;

        // Old manifests have only the recovery point as a safe cutoff.
        recover(manifest.formatted(workspace.getId(), ""));
        assertThat(words(before)).isBlank();
        assertThat(words(after)).contains("Later work");

        UUID atErasure = copyingToolResult("2026-01-02T00:00:00Z", "Words copied by the time of erasure");
        String dated = manifest.formatted(workspace.getId(), ",\"erasedAt\":\"2026-01-02T00:00:00Z\"");
        recover(dated);
        assertThat(words(atErasure)).isBlank();
        assertThat(words(after)).contains("Later work");

        UUID afterFirstReplay = copyingToolResult("2026-01-04T00:00:00Z", "Work before the repeated replay");
        recover(dated);
        assertThat(words(after)).contains("Later work");
        assertThat(words(afterFirstReplay)).contains("Work before the repeated replay");
    }

    private UUID copyingToolResult(String time, String text) {
        var main = conversations.findOrCreateMain(workspace.getId(), ownerAccount);
        String call = ConversationLedgerFixtures.piId();
        var entry = entries.append(
                workspace.getId(),
                main.getId(),
                new ConversationEntryDraft(
                        ConversationEntry.Kind.TOOL_RESULT,
                        ConversationEntry.Surface.APP,
                        text,
                        ConversationLedgerFixtures.toolResult(call, call, "read", text, false),
                        null,
                        null,
                        null));
        jdbc.update(
                "UPDATE conversation_entry SET created_at = CAST(? AS timestamptz) WHERE id = ?", time, entry.getId());
        return entry.getId();
    }

    private long slackThreads(String channelId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM slack_thread WHERE workspace_id = ? AND slack_channel_id = ?",
                Long.class,
                workspace.getId(),
                channelId));
    }

    private @Nullable String consent(String channelId) {
        return jdbc.queryForObject(
                "SELECT consent_state FROM slack_monitored_channel WHERE workspace_id = ? AND slack_channel_id = ?",
                String.class,
                workspace.getId(),
                channelId);
    }

    private long slackRows(String channelId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM slack_message WHERE workspace_id = ? AND slack_channel_id = ?",
                Long.class,
                workspace.getId(),
                channelId));
    }

    private long slackMessage(String ts, String text) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO slack_message (workspace_id, slack_team_id, slack_channel_id, slack_ts, text, ingested_at)"
                        + " VALUES (?, 'T-restore', 'C-restore', ?, ?, now()) RETURNING id",
                Long.class,
                workspace.getId(),
                ts,
                text));
    }

    private @Nullable String slackText(long messageId) {
        return jdbc.queryForObject("SELECT text FROM slack_message WHERE id = ?", String.class, messageId);
    }

    @Test
    void shouldEraseAgainAnOutlineCollectionThatARestoreBroughtBack() throws Exception {
        long outlineId = outlineConnection();
        String collectionId = UUID.randomUUID().toString();
        // A sibling collection of the same connection, which the erase leaves alone.
        String siblingId = UUID.randomUUID().toString();
        mirror(outlineId, collectionId, "decision-record");
        mirror(outlineId, siblingId, "runbook");
        var backup = Backup.of(jdbc, workspace.getId());
        // The admin's erase: the registration and every mirrored document go.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            outlineDocuments.deleteByWorkspaceIdAndConnectionIdAndCollectionId(
                    workspace.getId(), outlineId, collectionId);
            outlineCollections
                    .findByWorkspaceIdAndConnectionIdAndCollectionId(workspace.getId(), outlineId, collectionId)
                    .ifPresent(outlineCollections::delete);
        });

        backup.restore();
        assertThat(mirrored(collectionId))
                .as("the restore brought the documents back")
                .isOne();

        String manifest = """
                {"version":4,"slackMessageChanges":[],"slackChannelErasures":[],"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[],"messageDeletions":[],
                 "collectionErasures":[{"workspaceId":%d,"connectionId":%d,"collectionId":"%s"}]}
                """.formatted(workspace.getId(), outlineId, collectionId);
        recover(manifest);

        assertThat(mirrored(collectionId)).isZero();
        assertThat(mirrored(siblingId))
                .as("the sibling collection keeps its documents")
                .isOne();
        assertThat(outlineCollections.findByWorkspaceIdAndConnectionIdAndCollectionId(
                        workspace.getId(), outlineId, siblingId))
                .isPresent();
        assertThat(outlineCollections.findByWorkspaceIdAndConnectionIdAndCollectionId(
                        workspace.getId(), outlineId, collectionId))
                .as("the collection is no longer registered, so no sync mirrors it again")
                .isEmpty();
        // The same manifest is safe after a restart.
        recover(manifest);
        assertThat(mirrored(collectionId)).isZero();
    }

    @Test
    void shouldLeaveTheSameRowsWhenTheSameManifestIsAppliedTwice() throws Exception {
        // The rows that a restore brought back, of every kind but a workspace purge, which would erase the rest.
        UUID message = question("Words to take back");
        long edited = slackMessage("1700000001.000100", "Words before the edit");
        long deleted = slackMessage("1700000002.000200", "Words before the delete");
        jdbc.update(
                "INSERT INTO slack_monitored_channel (workspace_id, slack_team_id, slack_channel_id, consent_state, created_at)"
                        + " VALUES (?, 'T-restore', 'C-erased', 'ACTIVE', '2025-12-31T00:00:00Z')",
                workspace.getId());
        long inChannel = slackMessage("1700000003.000300", "Words in a channel that is later erased");
        jdbc.update("UPDATE slack_message SET slack_channel_id = 'C-erased' WHERE id = ?", inChannel);
        new SlackThreadTestSupport(jdbc)
                .seedThread(workspace.getId(), "C-erased", "1700000003.000300", "1700000003.000300", 1, "{}");
        long outlineId = outlineConnection();
        String collectionId = UUID.randomUUID().toString();
        mirror(outlineId, collectionId, "decision-record");
        // A fact whose erasure finds nothing: the channel was never stored.
        String manifest =
                """
                {"version":4,"restorePoint":"2026-01-01T00:00:00Z","noLaterErasures":false,"people":[],
                 "workspacePurges":[],"disconnects":[{"workspaceId":%1$d,"connectionId":%2$d}],
                 "collectionErasures":[{"workspaceId":%1$d,"connectionId":%3$d,"collectionId":"%4$s"}],
                 "messageDeletions":[{"workspaceId":%1$d,"entryId":"%5$s"}],
                 "slackMessageChanges":[{"workspaceId":%1$d,"messageId":%6$d,"change":"EDITED"},
                                        {"workspaceId":%1$d,"messageId":%7$d,"change":"DELETED"}],
                 "slackChannelErasures":[{"workspaceId":%1$d,"channelId":"C-erased"},
                                         {"workspaceId":%1$d,"channelId":"C-never-stored"}]}
                """.formatted(workspace.getId(), connection.getId(), outlineId, collectionId, message, edited, deleted);

        recover(manifest);
        var once = erasedRows(message);
        assertThat(state()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(words(message)).doesNotContain("Words to take back");
        assertThat(slackText(edited)).isNull();
        assertThat(slackRows("C-erased")).isZero();
        assertThat(mirrored(collectionId)).isZero();

        // A duplicate fact replays the same manifest: each step finds its target erased and changes nothing.
        recover(manifest);
        assertThat(erasedRows(message)).isEqualTo(once);
    }

    /** The rows of the workspace that the manifest's erasures touch, each as JSON text. */
    private List<@Nullable String> erasedRows(UUID message) {
        var rows = new ArrayList<@Nullable String>();
        for (String table : List.of(
                "connection",
                "outline_collection",
                "outline_document",
                "slack_message",
                "slack_thread",
                "slack_monitored_channel")) {
            String query = "SELECT to_jsonb(t)::text FROM " + table + " t WHERE t.workspace_id = ? ORDER BY 1";
            rows.addAll(jdbc.queryForList(query, String.class, workspace.getId()));
        }
        String entry = "SELECT to_jsonb(t)::text FROM conversation_entry t WHERE t.id = ?";
        rows.addAll(jdbc.queryForList(entry, String.class, message));
        return rows;
    }

    private UUID question(String words) {
        var main = conversations.findOrCreateMain(workspace.getId(), ownerAccount);
        return turns.submit(
                        workspace.getId(),
                        main.getId(),
                        new ConversationTurnService.Question(
                                ConversationTurn.Surface.APP,
                                words,
                                UUID.randomUUID().toString(),
                                null,
                                null))
                .question()
                .getId();
    }

    private long outlineConnection() {
        var outline = new Connection(
                workspace,
                IntegrationKind.OUTLINE,
                "team-" + workspace.getId(),
                new ConnectionConfig.OutlineConfig("https://outline.example.test", null, null, Set.of()));
        outline.setCredentials(new BearerToken("outline-token", null), credentials);
        ConnectionFixtures.reachState(outline, IntegrationState.ACTIVE);
        return Objects.requireNonNull(connections.save(outline).getId());
    }

    /** A registered collection of the Outline connection with one mirrored document. */
    private void mirror(long outlineId, String collectionId, String documentId) {
        var collection = new OutlineCollection();
        collection.setWorkspaceId(workspace.getId());
        collection.setConnectionId(outlineId);
        collection.setCollectionId(collectionId);
        collection.setName(documentId);
        collection.setState(OutlineCollection.MirrorState.ENABLED);
        collection.setSyncStatus(OutlineCollection.SyncStatus.COMPLETE);
        outlineCollections.save(collection);
        var document = new OutlineDocument();
        document.setWorkspaceId(workspace.getId());
        document.setConnectionId(outlineId);
        document.setCollectionId(collectionId);
        document.setDocumentId(documentId);
        document.setTitle(documentId);
        document.setBodyMarkdown("# Words of " + documentId);
        outlineDocuments.save(document);
    }

    private int mirrored(String collectionId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM outline_document WHERE workspace_id = ? AND collection_id = ?",
                Integer.class,
                workspace.getId(),
                collectionId);
        return count == null ? 0 : count;
    }

    /** The message's stored words: its text and the text that search and the personal-data export read. */
    private String words(UUID entryId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT text || ' ' || search_text FROM conversation_entry WHERE id = ?", String.class, entryId));
    }

    private void recover(String content) throws Exception {
        Path manifest = Files.writeString(directory.resolve("recovery-" + UUID.randomUUID() + ".json"), content);
        var environment = new MockEnvironment()
                .withProperty(RuntimeRole.SERVER_PROPERTY, "true")
                .withProperty(ConditionalOnBackgroundWork.PROPERTY, "false");
        try (var context = new GenericApplicationContext()) {
            context.registerBean(
                    PersonDataRecovery.class,
                    () -> new PersonDataRecovery(
                            service,
                            resolver,
                            mapper,
                            environment,
                            beans.getBean(PersonDataRequestRepository.class),
                            beans.getBean(RestoredWorkspacePurges.class),
                            beans.getBean(RestoredDisconnects.class),
                            beans.getBean(RestoredMessageDeletions.class),
                            beans.getBean(RestoredCollectionErasures.class),
                            beans.getBean(RestoredSlackMessageChanges.class),
                            beans.getBean(RestoredSlackChannelErasures.class),
                            new PersonDataRecoveryProperties(manifest.toString())));
            context.refresh();
            assertThat(context.getBean(PersonDataRecovery.class).isRunning()).isTrue();
        }
    }

    private IntegrationState state() {
        return connections.findById(connection.getId()).orElseThrow().getState();
    }

    private String status() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT status FROM workspace WHERE id = ?", String.class, workspace.getId()));
    }

    private int memberships() {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM membership WHERE workspace_id = ?", Integer.class, workspace.getId());
        return count == null ? 0 : count;
    }

    private ConnectionStrategy strategy(IntegrationKind kind) {
        return strategies.stream()
                .filter(candidate -> candidate.kind() == kind)
                .findFirst()
                .orElseThrow();
    }

    /**
     * Every row of one workspace as a backup holds it: the workspace row and each row that carries its workspace, as
     * every workspace-owned table does. A provider removal that the erasure recorded is global, so the restore also
     * deletes the repairs that the workspace's links name and the backup did not hold.
     */
    private record Backup(
            JdbcTemplate jdbc,
            long workspaceId,
            Map<String, List<Map<String, @Nullable Object>>> rows,
            Set<String> generated) {

        static Backup of(JdbcTemplate jdbc, long workspaceId) {
            var generated = jdbc.queryForList("""
                    SELECT table_name || '.' || column_name FROM information_schema.columns
                     WHERE table_schema = current_schema() AND is_generated = 'ALWAYS'
                    """, String.class).stream()
                    .map(Objects::requireNonNull)
                    .collect(Collectors.toUnmodifiableSet());
            var rows = new LinkedHashMap<String, List<Map<String, @Nullable Object>>>();
            rows.put("workspace", jdbc.queryForList("SELECT * FROM workspace WHERE id = ?", workspaceId));
            for (String table : workspaceTables(jdbc)) {
                rows.put(table, jdbc.queryForList("SELECT * FROM " + table + " WHERE workspace_id = ?", workspaceId));
            }
            return new Backup(jdbc, workspaceId, rows, generated);
        }

        private static List<String> workspaceTables(JdbcTemplate jdbc) {
            return jdbc.queryForList("""
                    SELECT c.table_name FROM information_schema.columns c
                      JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                     WHERE c.table_schema = current_schema() AND c.column_name = 'workspace_id'
                       AND t.table_type = 'BASE TABLE'
                     ORDER BY c.table_name
                    """, String.class).stream()
                    .map(Objects::requireNonNull)
                    .toList();
        }

        /** The same backup, with the rows of one table as a later backup holds them. */
        Backup with(String table, List<Map<String, @Nullable Object>> tableRows) {
            var changed = new LinkedHashMap<>(rows);
            changed.put(table, tableRows);
            return new Backup(jdbc, workspaceId, changed, generated);
        }

        void restore() throws SQLException {
            var held = Objects.requireNonNull(rows.get("connection_provider_repair")).stream()
                    .map(row -> row.get("repair_id"))
                    .toList();
            var administrator = PostgreSQLTestContainer.administrator();
            try (var session = DriverManager.getConnection(
                    administrator.jdbcUrl(), administrator.username(), administrator.password())) {
                session.setAutoCommit(false);
                var restore = new JdbcTemplate(new SingleConnectionDataSource(session, true));
                restore.execute("SET LOCAL session_replication_role = replica");
                restore.execute(
                        "SET LOCAL search_path TO " + jdbc.queryForObject("SELECT current_schema()", String.class));
                for (UUID recorded : restore
                        .queryForList(
                                "SELECT repair_id FROM connection_provider_repair WHERE workspace_id = ?",
                                UUID.class,
                                workspaceId)
                        .stream()
                        .map(Objects::requireNonNull)
                        .toList()) {
                    if (!held.contains(recorded))
                        restore.update("DELETE FROM provider_resource_repair WHERE id = ?", recorded);
                }
                for (var table : rows.entrySet()) {
                    String key = table.getKey().equals("workspace") ? "id" : "workspace_id";
                    restore.update("DELETE FROM " + table.getKey() + " WHERE " + key + " = ?", workspaceId);
                    for (var row : table.getValue()) insert(restore, table.getKey(), row, generated);
                }
                session.commit();
            }
        }

        /** A generated column is computed again from the restored row, as a restore does. */
        private static void insert(
                JdbcTemplate restore, String table, Map<String, @Nullable Object> row, Set<String> generated) {
            var stored = row.entrySet().stream()
                    .filter(column -> !generated.contains(table + "." + column.getKey()))
                    .toList();
            var columns =
                    String.join(", ", stored.stream().map(Map.Entry::getKey).toList());
            var values = String.join(", ", stored.stream().map(column -> "?").toList());
            restore.update(
                    "INSERT INTO " + table + " (" + columns + ") OVERRIDING SYSTEM VALUE VALUES (" + values + ")",
                    stored.stream().map(Map.Entry::getValue).toArray());
        }
    }
}
