package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.EncryptionContext;
import de.tum.cit.aet.hephaestus.integration.core.oauth.OAuthCallbackService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionStrategy.ConnectFinalization;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.OAuthExchangeRepair;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackConnectionStrategy;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthException;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.io.Serial;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

class ProviderOAuthExchangeRepairIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private OAuthExchangeRepair exchanges;

    @Autowired
    private ProviderResourceRepairs coordination;

    @Autowired
    private ProviderResourceRepairRepository rows;

    @Autowired
    private ConnectionProviderRepairRepository references;

    @Autowired
    private SlackConnectionStrategy strategy;

    @Autowired
    private SlackOAuthClient client;

    @Autowired
    private OAuthCallbackService callbacks;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private ProviderResourceRepairDispatcher dispatcher;

    @Autowired
    private Clock clock;

    private Workspace workspace;
    private Connection owner;
    private String team;
    private String token;
    private Long actorAccountId;

    @BeforeEach
    void ownAttempt() {
        String suffix = UUID.randomUUID().toString();
        actorAccountId = Objects.requireNonNull(
                persistAccount("exchange-actor-" + suffix).getId());
        team = "T" + suffix.replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        token = "test-bot-token-" + suffix;
        workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("exchange-" + suffix));
        owner = connections.saveAndFlush(new Connection(
                workspace, IntegrationKind.SLACK, null, new ConnectionConfig.SlackConfig(null, null, null, Set.of())));
        when(client.exchangeCode(anyString(), anyString()))
                .thenReturn(new SlackOAuthClient.OAuthV2Access(
                        true, null, token, new SlackOAuthClient.OAuthV2Access.Team(team, "Team"), null, null, "U-BOT"));
        when(client.botIdentity(token)).thenReturn(new SlackOAuthClient.BotIdentity(true, team, "U-BOT", null));
    }

    @AfterEach
    void removeOnlyOwnAttempts() {
        reset(client);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            for (var reference : references.findByWorkspaceIdAndConnection_Id(workspace.getId(), owner.getId())) {
                UUID id = reference.getRepair().getId();
                references.delete(reference);
                rows.deleteById(id);
            }
        });
    }

    private IntegrationRef ref() {
        return new IntegrationRef(IntegrationKind.SLACK, workspace.getId(), owner.getInstanceKey(), owner.getId());
    }

    private UUID attemptId() {
        return references
                .findByWorkspaceIdAndConnection_Id(workspace.getId(), owner.getId())
                .getFirst()
                .getRepair()
                .getId();
    }

    @Test
    void shouldRetainCommittedIntentWhenTheProcessCrashesAtExchangeDispatch() {
        when(client.exchangeCode(anyString(), anyString())).thenAnswer(call -> {
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM connection_provider_repair WHERE workspace_id = ? AND connection_id = ?",
                            Long.class,
                            workspace.getId(),
                            owner.getId()))
                    .isEqualTo(1);
            throw new SimulatedCrash();
        });
        assertThatThrownBy(() -> strategy.finalizeConnect(ref(), Map.of("code", "code")))
                .isInstanceOf(SimulatedCrash.class);
        assertThat(rows.findById(attemptId()).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.REGISTERING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transport_failure", "internal_error", "fatal_error"})
    void shouldKeepExchangeIntentForErrorsThatCanFollowAPartialSuccess(String reason) {
        when(client.exchangeCode(anyString(), anyString())).thenThrow(new SlackOAuthException(reason));
        assertThatThrownBy(() -> strategy.finalizeConnect(ref(), Map.of("code", "code")))
                .isInstanceOf(SlackOAuthException.class);
        assertThat(rows.findById(attemptId()).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_code", "access_denied", "bad_client_secret"})
    void shouldRetireAnExchangeThatSlackDefinitivelyRefused(String reason) {
        when(client.exchangeCode(anyString(), anyString())).thenThrow(new SlackOAuthException(reason));
        assertThat(strategy.finalizeConnect(ref(), Map.of("code", "code")))
                .isInstanceOf(ConnectFinalization.Failed.class);
        assertThat(references.findByWorkspaceIdAndConnection_Id(workspace.getId(), owner.getId()))
                .isEmpty();
    }

    @Test
    void shouldPersistTheReturnedIdentityBeforeAuthTestCanFailAndFenceTheUncertainOutcome() {
        String fingerprint = ProviderResourceAdapter.slackTokenFingerprint(token);
        when(client.botIdentity(token)).thenAnswer(call -> {
            assertThat(jdbc.queryForObject(
                            "SELECT observed_token_fingerprint FROM provider_resource_repair WHERE id = ?",
                            String.class,
                            attemptId()))
                    .isEqualTo(fingerprint);
            throw new SlackOAuthException("transport_failure");
        });
        assertThatThrownBy(() -> strategy.finalizeConnect(ref(), Map.of("code", "code")))
                .isInstanceOf(SlackOAuthException.class);
        assertThat(rows.findById(attemptId()).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
        assertThat(rows.hasUnresolvedSlackExchange(ResourceType.OAUTH_EXCHANGE, team, "U-BOT", fingerprint, null))
                .isTrue();
        assertThatThrownBy(() -> new TransactionTemplate(manager)
                        .executeWithoutResult(status -> coordination.requireAdoptable(
                                new IntegrationRef(IntegrationKind.SLACK, workspace.getId() + 1, team),
                                new ConnectionConfig.SlackConfig(team, "U-BOT", fingerprint, "Team", null, Set.of()),
                                new BearerToken(token, null))))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldDiscardAnExchangeAfterADeterministicDifferentTeamRefusalWithoutFencingThatTeam() {
        owner.bindInstanceKey("T-EXISTING-" + workspace.getId());
        owner.setConfig(new ConnectionConfig.SlackConfig(
                owner.getInstanceKey(), "U-EXISTING", null, "Existing", null, Set.of()));
        ConnectionFixtures.reachState(owner, IntegrationState.ACTIVE);
        owner = connections.saveAndFlush(owner);
        var completed = strategy.finalizeConnect(ref(), Map.of("code", "code"));
        assertThat(completed).isInstanceOf(ConnectFinalization.Completed.class);
        assertThatThrownBy(() ->
                        callbacks.completeConnection(owner, (ConnectFinalization.Completed) completed, actorAccountId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(references.findByWorkspaceIdAndConnection_Id(workspace.getId(), owner.getId()))
                .isEmpty();
        assertThat(rows.hasUnresolvedSlackExchange(
                        ResourceType.OAUTH_EXCHANGE,
                        team,
                        "U-BOT",
                        ProviderResourceAdapter.slackTokenFingerprint(token),
                        null))
                .isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_resource_repair WHERE kind = 'SLACK' AND resource_type = 'BOT_USER' AND parent_id = ?",
                        Long.class,
                        team))
                .isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "true,true,ACTIVE",
        "true,false,ACTIVE",
        "false,true,ACTIVE",
        "false,false,ACTIVE",
        "true,true,SUSPENDED",
        "true,false,SUSPENDED",
        "false,true,SUSPENDED",
        "false,false,SUSPENDED"
    })
    void shouldLetTheLiveTeamHolderReauthorizeAfterAnExchangeWasSuperseded(
            boolean otherWorkspace, boolean legacyMetadata, IntegrationState heldState) {
        var heldConfig = new ConnectionConfig.SlackConfig(
                team,
                legacyMetadata ? null : "U-BOT",
                legacyMetadata ? null : ProviderResourceAdapter.slackTokenFingerprint(token),
                "Team",
                null,
                Set.of());
        Workspace heldWorkspace = otherWorkspace
                ? workspaces.save(WorkspaceTestFixtures.activeWorkspace("held-" + UUID.randomUUID()))
                : workspace;
        Connection holder;
        if (otherWorkspace) {
            holder = new Connection(heldWorkspace, IntegrationKind.SLACK, team, heldConfig);
        } else {
            owner.bindInstanceKey(team);
            holder = owner;
            holder.setConfig(heldConfig);
        }
        ConnectionFixtures.reachState(holder, heldState);
        holder.setCredentials(new BearerToken(token, null), converter);
        holder = connections.saveAndFlush(holder);
        if (!otherWorkspace) owner = holder;
        var first = (ConnectFinalization.Completed) strategy.finalizeConnect(ref(), Map.of("code", "old"));
        UUID firstId = Objects.requireNonNull(first.exchangeAttemptId());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var edited = connections.findById(owner.getId()).orElseThrow();
            edited.setDisplayName("Changed while the provider answered");
            connections.saveAndFlush(edited);
        });
        assertThatThrownBy(() -> callbacks.completeConnection(owner, first, actorAccountId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("superseded");
        assertThat(rows.findById(firstId).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
        String replacement = "replacement-" + token;
        when(client.exchangeCode(anyString(), anyString()))
                .thenReturn(new SlackOAuthClient.OAuthV2Access(
                        true,
                        null,
                        replacement,
                        new SlackOAuthClient.OAuthV2Access.Team(team, "Team"),
                        null,
                        null,
                        "U-BOT"));
        when(client.botIdentity(replacement)).thenReturn(new SlackOAuthClient.BotIdentity(true, team, "U-BOT", null));
        Connection freshHolder = connections.findById(holder.getId()).orElseThrow();
        var holderRef = new IntegrationRef(IntegrationKind.SLACK, heldWorkspace.getId(), team, freshHolder.getId());
        var next = (ConnectFinalization.Completed) strategy.finalizeConnect(holderRef, Map.of("code", "new"));
        Connection active = callbacks.completeConnection(freshHolder, next, actorAccountId);
        assertThat(active.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(((ConnectionConfig.SlackConfig) active.getConfig()).botTokenFingerprint())
                .isEqualTo(ProviderResourceAdapter.slackTokenFingerprint(replacement));
        assertThat(rows.existsById(firstId)).isTrue();
        assertThat(rows.existsById(Objects.requireNonNull(next.exchangeAttemptId())))
                .isFalse();
        verify(client, never()).revokeStrict(anyString());
    }

    @Test
    void shouldMarkAnUnrelatedDatabaseRefusalAsAnUncertainSlackExchange() {
        var completed = (ConnectFinalization.Completed) strategy.finalizeConnect(ref(), Map.of("code", "code"));
        UUID attempt = Objects.requireNonNull(completed.exchangeAttemptId());
        var invalid = new ConnectFinalization.Completed(
                completed.instanceKey(), completed.credentials(), "x".repeat(300), completed.config(), attempt);
        assertThatThrownBy(() -> callbacks.completeConnection(owner, invalid, actorAccountId))
                .hasStackTraceContaining("value too long for type character varying(256)");
        assertThat(rows.findById(attempt).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
        assertThat(connections.findById(owner.getId()).orElseThrow().getState()).isEqualTo(IntegrationState.PENDING);
    }

    @Test
    void shouldRetryTheSameGitHubInstallationAfterALocalAdoptionRollback() {
        long installation = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), Long.MAX_VALUE - 1) + 1;
        var config = new ConnectionConfig.GitHubAppConfig(installation, 77L, "org", "https://github.com", Set.of());
        Connection github = connections.saveAndFlush(new Connection(workspace, IntegrationKind.GITHUB, null, config));
        String key = Long.toString(installation);
        assertThatThrownBy(() -> callbacks.completeConnection(
                        github, new ConnectFinalization.Completed(key, null, "x".repeat(300), config), actorAccountId))
                .hasStackTraceContaining("value too long for type character varying(256)");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM provider_resource_repair WHERE kind = 'GITHUB' AND resource_id = ?",
                        Long.class,
                        key))
                .isZero();
        Connection active = callbacks.completeConnection(
                connections.findById(github.getId()).orElseThrow(),
                new ConnectFinalization.Completed(key, null, "GitHub", config),
                actorAccountId);
        assertThat(active.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(active.getInstanceKey()).isEqualTo(key);
    }

    @Test
    void shouldRemoveExchangeEvidenceAtomicallyWithSuccessfulLocalAdoption() {
        var completed = (ConnectFinalization.Completed) strategy.finalizeConnect(ref(), Map.of("code", "code"));
        UUID attempt = Objects.requireNonNull(completed.exchangeAttemptId());
        Connection active = callbacks.completeConnection(owner, completed, actorAccountId);
        assertThat(active.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(rows.existsById(attempt)).isFalse();
        assertThat(references.findByWorkspaceIdAndConnection_Id(workspace.getId(), active.getId()))
                .isEmpty();
    }

    @Test
    void shouldKeepAnObservedExchangeWhenItsConnectionIsUninstalledBeforeLocalCompletion() {
        var completed = (ConnectFinalization.Completed) strategy.finalizeConnect(ref(), Map.of("code", "code"));
        UUID attempt = Objects.requireNonNull(completed.exchangeAttemptId());
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            Connection current = connections.findById(owner.getId()).orElseThrow();
            ConnectionFixtures.reachState(current, IntegrationState.UNINSTALLED);
            connections.saveAndFlush(current);
        });
        assertThatThrownBy(() -> callbacks.completeConnection(owner, completed, actorAccountId))
                .isInstanceOf(IllegalStateException.class);
        assertThat(rows.findById(attempt).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
        assertThat(rows.hasUnresolvedSlackExchange(
                        ResourceType.OAUTH_EXCHANGE,
                        team,
                        "U-BOT",
                        ProviderResourceAdapter.slackTokenFingerprint(token),
                        null))
                .isTrue();
        verify(client, never()).revokeStrict(anyString());
        assertThat(connections.findById(owner.getId()).orElseThrow().getState())
                .isEqualTo(IntegrationState.UNINSTALLED);
    }

    @Test
    void shouldKeepThePersistedFingerprintAcrossRestartAndCredentialKeyRotation() {
        String fingerprint = ProviderResourceAdapter.slackTokenFingerprint(token);
        UUID attempt = exchanges.begin(ref());
        exchanges.observed(attempt, team, "U-BOT", fingerprint);
        var firstProcess = new CredentialBundleConverter("a".repeat(32), false);
        owner.setCredentials(new BearerToken(token, null), firstProcess);
        owner = connections.saveAndFlush(owner);
        var restarted = new CredentialBundleConverter("b".repeat(32), 2, "a".repeat(32), 1, false);
        Connection persisted = connections.findById(owner.getId()).orElseThrow();
        var context = new EncryptionContext(
                workspace.getId(), IntegrationKind.SLACK, null, "connection.credentials_encrypted");
        var decrypted = restarted.decrypt(
                Objects.requireNonNull(persisted.getCredentialsEncrypted()),
                context,
                persisted.getCredentialsKeyVersion());
        assertThat(decrypted)
                .isInstanceOfSatisfying(
                        BearerToken.class,
                        bearer -> assertThat(ProviderResourceAdapter.slackTokenFingerprint(bearer.token()))
                                .isEqualTo(fingerprint));
        persisted.setCredentials(decrypted, restarted);
        connections.saveAndFlush(persisted);
        var secondRestart = new CredentialBundleConverter("b".repeat(32), 2, null, null, false);
        Connection rotated = connections.findById(owner.getId()).orElseThrow();
        var afterRotation = secondRestart.decrypt(Objects.requireNonNull(rotated.getCredentialsEncrypted()), context);
        assertThat(afterRotation)
                .isInstanceOfSatisfying(
                        BearerToken.class,
                        bearer -> assertThat(ProviderResourceAdapter.slackTokenFingerprint(bearer.token()))
                                .isEqualTo(fingerprint));
        assertThat(jdbc.queryForObject(
                        "SELECT observed_token_fingerprint FROM provider_resource_repair WHERE id = ?",
                        String.class,
                        attempt))
                .isEqualTo(fingerprint);
        assertThat(rotated.getCredentialsKeyVersion()).isEqualTo(2);
    }

    @Test
    void shouldUseTheMatchingTokenWhenTheFirstSurvivingOwnerHasAlreadyRotatedItsCredential() {
        String fingerprint = ProviderResourceAdapter.slackTokenFingerprint(token);
        UUID repairId = Objects.requireNonNull(new TransactionTemplate(manager).execute(status -> {
            owner.bindInstanceKey(team);
            ConnectionFixtures.reachState(owner, IntegrationState.UNINSTALLED);
            owner.setConfig(new ConnectionConfig.SlackConfig(team, "U-BOT", fingerprint, "Team", null, Set.of()));
            owner = connections.saveAndFlush(owner);
            Workspace otherWorkspace =
                    workspaces.save(WorkspaceTestFixtures.activeWorkspace("second-owner-" + UUID.randomUUID()));
            Connection other = new Connection(otherWorkspace, IntegrationKind.SLACK, team, owner.getConfig());
            ConnectionFixtures.reachState(other, IntegrationState.UNINSTALLED);
            other = connections.saveAndFlush(other);
            var target = new ProviderResourceAdapter.Resource(
                    IntegrationKind.SLACK,
                    "https://slack.com",
                    ResourceType.BOT_TOKEN,
                    team + "/U-BOT",
                    fingerprint,
                    null);
            var row =
                    rows.save(new ProviderResourceRepair(target, clock.instant().minusSeconds(1)));
            references.saveAndFlush(new ConnectionProviderRepair(owner, row));
            references.saveAndFlush(new ConnectionProviderRepair(other, row));
            var ordered = references.findByRepair_Id(row.getId());
            for (int index = 0; index < ordered.size(); index++) {
                Connection connection = ordered.get(index).getConnection();
                String value = index == 0 ? token + "-rotated" : token;
                connection.setConfig(new ConnectionConfig.SlackConfig(
                        team, "U-BOT", ProviderResourceAdapter.slackTokenFingerprint(value), "Team", null, Set.of()));
                connection.setCredentials(new BearerToken(value, null), converter);
            }
            return row.getId();
        }));
        dispatcher.dispatch(repairId);
        verify(client).revokeStrict(token);
        verify(client, never()).botIdentity(token + "-rotated");
        assertThat(rows.existsById(repairId)).isFalse();
    }

    private static final class SimulatedCrash extends Error {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
