package de.tum.cit.aet.hephaestus.integration.core.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService.TransitionRequest;
import de.tum.cit.aet.hephaestus.integration.core.events.ConnectionLifecycleEvent;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * State-machine contract for {@link ConnectionService#transition}. Exercises the legal
 * path (one audit row, state mutated), the rejection path (no audit row, state untouched),
 * the same-state no-op, and the UNINSTALLED credential purge. {@link IntegrationStateTest}
 * covers the pure {@code canTransitionTo} predicate; this test covers the service that
 * enforces it.
 */
@Tag("unit")
class ConnectionServiceTest extends BaseUnitTest {

    @Mock
    private ConnectionRepository connectionRepository;

    @Mock
    private ConnectionAuditRepository auditRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SyncJobService syncJobService;

    @Mock
    private PlatformTransactionManager transactionManager;

    private CredentialBundleConverter credentialConverter;
    private ConnectionService service;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Real converter so the credential-purge case operates on a genuine AES-GCM blob,
        // not a mock stand-in.
        credentialConverter = new CredentialBundleConverter("a".repeat(32), false);
        // The revoke callback runs through a TransactionTemplate over this manager; a stub status is
        // enough to let the template execute, and it lets us assert the propagation it asked for.
        Mockito.lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new ConnectionService(
                connectionRepository,
                auditRepository,
                credentialConverter,
                eventPublisher,
                syncJobService,
                transactionManager,
                new CredentialReader(
                        connectionRepository,
                        credentialConverter,
                        transactionManager,
                        new SyncTaskExecutor(),
                        Clock.systemUTC()));
        // Default: the connection is free. Only the disconnect fence ever asks.
        Mockito.lenient()
                .when(syncJobService.requestCancelForTeardown(anyLong()))
                .thenReturn(java.util.Optional.empty());
        workspace = new Workspace();
        workspace.setId(7L);
        // transition() returns the saved entity; echo it back so callers see the mutated row.
        Mockito.lenient().when(connectionRepository.save(any(Connection.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void transition_legalPending_toActive_writesOneAuditRowAndMutatesState() {
        Connection connection = pendingConnection();

        Connection result = service.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.ACTIVE, "INSTALL_BIND", "GITHUB_WEBHOOK", "actor-1", "corr-1", "linked"));

        assertThat(result.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(result.getStateReason()).isEqualTo("linked");

        ArgumentCaptor<ConnectionAudit> audit = ArgumentCaptor.forClass(ConnectionAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(IntegrationState.PENDING);
        assertThat(audit.getValue().getToState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(audit.getValue().getEventType()).isEqualTo("INSTALL_BIND");
        assertThat(audit.getValue().getCorrelationId()).isEqualTo("corr-1");
        verify(connectionRepository).save(connection);
    }

    @Test
    void transition_illegalUninstalledToActive_throwsAndWritesNoAuditRowAndKeepsState() {
        Connection connection = connectionInState(IntegrationState.UNINSTALLED);

        assertThatThrownBy(() -> service.transition(
                        connection,
                        new TransitionRequest(IntegrationState.ACTIVE, "REVIVE", "ADMIN", "actor-1", "corr-x", "nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Illegal transition")
                .hasMessageContaining("UNINSTALLED")
                .hasMessageContaining("ACTIVE");

        assertThat(connection.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        verify(auditRepository, never()).save(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void transition_slackOAuthReconnectFromUninstalled_writesAuditRowAndReactivates() {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "T1",
                new ConnectionConfig.SlackConfig("T1", "Acme", null, null, null, Set.of()));
        setId(connection, 55L);
        connection.setState(IntegrationState.UNINSTALLED);
        when(connectionRepository.findByIdAndWorkspaceId(connection.getId(), workspace.getId()))
                .thenReturn(java.util.Optional.of(connection));

        Connection result = service.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.ACTIVE, "OAUTH_COMPLETE", "USER", "actor-1", "corr-x", "reconnected"));

        assertThat(result.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(result.getStateReason()).isEqualTo("reconnected");

        ArgumentCaptor<ConnectionAudit> audit = ArgumentCaptor.forClass(ConnectionAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getFromState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(audit.getValue().getToState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(audit.getValue().getEventType()).isEqualTo("OAUTH_COMPLETE");
        verify(connectionRepository).save(connection);
    }

    @Test
    void transition_illegalSuspendedToPending_throwsAndWritesNoAuditRow() {
        Connection connection = connectionInState(IntegrationState.SUSPENDED);

        assertThatThrownBy(() -> service.transition(
                        connection,
                        new TransitionRequest(
                                IntegrationState.PENDING, "REWIND", "ADMIN", "actor-1", "corr-y", "nope")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUSPENDED")
                .hasMessageContaining("PENDING");

        assertThat(connection.getState()).isEqualTo(IntegrationState.SUSPENDED);
        verify(auditRepository, never()).save(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void transition_sameState_isNoOpReturnsSameInstanceNoAuditRow() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);

        Connection result = service.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.ACTIVE, "INSTALL_BIND", "GITHUB_WEBHOOK", "actor-1", "corr-1", "again"));

        // Same-state no-op must NOT overwrite stateReason.
        assertThat(result).isSameAs(connection);
        assertThat(result.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(result.getStateReason()).isNull();
        verify(auditRepository, never()).save(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void transition_staleSnapshotCannotOverwriteStateReadUnderLifecycleLock() {
        Connection stale = connectionInState(IntegrationState.ACTIVE);
        Connection authoritative = connectionInState(IntegrationState.UNINSTALLED);
        when(connectionRepository.findByIdAndWorkspaceId(stale.getId(), workspace.getId()))
                .thenReturn(java.util.Optional.of(authoritative));

        assertThatThrownBy(() -> service.transition(
                        stale,
                        new TransitionRequest(
                                IntegrationState.SUSPENDED,
                                "SUSPEND",
                                "ADMIN",
                                "actor-1",
                                "corr-stale",
                                "stale request")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNINSTALLED")
                .hasMessageContaining("SUSPENDED");

        assertThat(stale.getState()).isEqualTo(IntegrationState.ACTIVE);
        assertThat(authoritative.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        verify(auditRepository, never()).save(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void transition_toUninstalled_purgesCredentials() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);
        connection.setCredentials(new BearerToken("ghp-secret", null), credentialConverter);
        connection.markCredentialRotationFailed(Instant.parse("2026-09-03T12:00:00Z"));
        assertThat(connection.getCredentialsEncrypted()).isNotNull();
        assertThat(connection.getCredentialsAlg()).isEqualTo(CredentialBundleConverter.ALGORITHM_TAG);

        Connection result = service.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.UNINSTALLED, "UNINSTALL", "GITHUB_WEBHOOK", "actor-1", "corr-9", "removed"));

        assertThat(result.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(result.getCredentialsEncrypted()).isNull();
        assertThat(result.getCredentialsAlg()).isNull();
        assertThat(result.getCredentialsKeyVersion()).isNull();
        assertThat(result.getCredentialsRotationFailedAt()).isNull();
        // The transition that purged credentials is still audited.
        ArgumentCaptor<ConnectionAudit> audit = ArgumentCaptor.forClass(ConnectionAudit.class);
        verify(auditRepository).save(audit.capture());
        assertThat(audit.getValue().getToState()).isEqualTo(IntegrationState.UNINSTALLED);
    }

    @Test
    void transition_toUninstalled_clearsProviderWebhookConfig() {
        Connection gitLab = connection(
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.example", 12L, 34L, ConnectionConfig.GitLabConfig.SigningMode.WHSEC, Set.of()));
        Connection outline = connection(
                IntegrationKind.OUTLINE,
                new ConnectionConfig.OutlineConfig("https://outline.example", "subscription-1", "secret-1", Set.of()));

        service.transition(gitLab, uninstallRequest("gitlab"));
        service.transition(outline, uninstallRequest("outline"));

        var gitLabConfig = (ConnectionConfig.GitLabConfig) gitLab.getConfig();
        assertThat(gitLabConfig.gitlabWebhookId()).isNull();
        assertThat(gitLabConfig.gitlabGroupId()).isEqualTo(12L);
        var outlineConfig = (ConnectionConfig.OutlineConfig) outline.getConfig();
        assertThat(outlineConfig.webhookSubscriptionId()).isNull();
        assertThat(outlineConfig.webhookSecret()).isNull();
        assertThat(outlineConfig.serverUrl()).isEqualTo("https://outline.example");
    }

    @Test
    void findReferenced_resolvesAnExplicitSuspendedConnection() {
        Connection connection = connection(
                IntegrationKind.SLACK, new ConnectionConfig.SlackConfig("team-1", "Acme", null, null, null, Set.of()));
        connection.setState(IntegrationState.SUSPENDED);
        IntegrationRef ref = new IntegrationRef(
                IntegrationKind.SLACK, workspace.getId(), connection.getInstanceKey(), connection.getId());

        assertThat(service.findReferenced(ref)).contains(connection);
    }

    @Test
    void transition_toUninstalled_purgesAuthoritativelyWithoutFencing() {
        // System/vendor-driven uninstall (workspace PURGE, Slack app_uninstalled) is a statement of
        // fact with no one to hand a 409 to and no retry behind it. A background reconcile must never
        // be able to keep credentials on disk, so this path does not consult the sync fence at all.
        Connection connection = connectionInState(IntegrationState.ACTIVE);
        connection.setCredentials(new BearerToken("ghp-secret", null), credentialConverter);

        service.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.UNINSTALLED, "WORKSPACE_PURGED", "SYSTEM", "purge", "corr-p", "gone"));

        assertThat(connection.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(connection.getCredentialsEncrypted()).isNull();
        verify(syncJobService, never()).requestCancelForTeardown(anyLong());
    }

    @Test
    void disconnect_checksLifecycleFenceBeforeRevokingVendorAccess() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);
        Runnable revoke = Mockito.mock(Runnable.class);
        InOrder order = Mockito.inOrder(connectionRepository, syncJobService, revoke);

        service.disconnect(
                connection,
                new TransitionRequest(
                        IntegrationState.UNINSTALLED, "DISCONNECT", "ADMIN", "actor-1", "corr-disconnect", "removed"),
                revoke);

        // The fence must read the job state under the row lock, or a job could start in the window
        // between the check and the state write.
        order.verify(connectionRepository).acquireLifecycleLock(connection.getId(), workspace.getId());
        order.verify(syncJobService).requestCancelForTeardown(connection.getId());
        order.verify(revoke).run();
        assertThat(connection.getState()).isEqualTo(IntegrationState.UNINSTALLED);
    }

    /**
     * The revoke callback must run on its OWN (REQUIRES_NEW) transaction. On the lifecycle transaction
     * the erasers join with REQUIRED propagation, so a DataAccessException marks the shared transaction
     * rollback-only and the commit fails with UnexpectedRollbackException — defeating the "best effort,
     * proceed locally" contract.
     */
    @Test
    void disconnect_runsRevokeOnItsOwnRequiresNewTransaction() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);

        service.disconnect(connection, disconnectRequest(), Mockito.mock(Runnable.class));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void disconnect_revokeThrowsDataAccessException_isAbsorbedAndUninstalledStillCommits() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);
        connection.setCredentials(new BearerToken("xoxb-secret", null), credentialConverter);
        // Stands in for a statement timeout on a large mirror delete inside ScmWorkspaceContentEraser.
        Runnable revoke = () -> {
            throw new QueryTimeoutException("statement timeout erasing workspace mirror");
        };

        Connection result = service.disconnect(connection, disconnectRequest(), revoke);

        assertThat(result.getState()).isEqualTo(IntegrationState.UNINSTALLED);
        assertThat(result.getCredentialsEncrypted())
                .as("credentials are still purged")
                .isNull();
        verify(auditRepository).save(any(ConnectionAudit.class));
        verify(connectionRepository).save(any(Connection.class));
    }

    /**
     * If a caller swallows the erase failure inside the callback, the nested transaction is already
     * rollback-only and its commit raises UnexpectedRollbackException. That must be absorbed too, or it
     * reaches the admin as a 500.
     */
    @Test
    void disconnect_revokeSwallowsFailureAndNestedCommitRollsBack_isStillAbsorbed() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);
        Mockito.doThrow(new UnexpectedRollbackException("Transaction silently rolled back"))
                .when(transactionManager)
                .commit(any());

        Connection result = service.disconnect(connection, disconnectRequest(), () -> {
            /* callback catches its own failure */
        });

        assertThat(result.getState()).isEqualTo(IntegrationState.UNINSTALLED);
    }

    @Test
    void disconnect_withActiveSyncRejectsBeforeRevokingVendorAccess() {
        Connection connection = connectionInState(IntegrationState.ACTIVE);
        when(syncJobService.requestCancelForTeardown(connection.getId())).thenReturn(java.util.Optional.of(99L));
        Runnable revoke = Mockito.mock(Runnable.class);

        assertThatThrownBy(() -> service.disconnect(
                        connection,
                        new TransitionRequest(
                                IntegrationState.UNINSTALLED,
                                "DISCONNECT",
                                "ADMIN",
                                "actor-1",
                                "corr-disconnect",
                                "removed"),
                        revoke))
                .isInstanceOf(ConnectionBusyException.class)
                // The 409 names the job and promises a retry, because the fence already asked it to stop.
                .hasMessageContaining("active sync job 99")
                .hasMessageContaining("retry");

        verify(revoke, never()).run();
        assertThat(connection.getState()).isEqualTo(IntegrationState.ACTIVE);
        verify(auditRepository, never()).save(any());
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void transition_duplicateCorrelationId_shortCircuitsWithoutMutatingState() {
        Connection connection = pendingConnection();
        // Conflicting INSERT on the idempotency index → the conflicting redelivery is dropped.
        when(auditRepository.save(any(ConnectionAudit.class)))
                .thenThrow(new DataIntegrityViolationException("uq_connection_audit_idempotency"));

        Connection result = service.transition(
                connection,
                new TransitionRequest(
                        IntegrationState.ACTIVE, "INSTALL_BIND", "GITHUB_WEBHOOK", "actor-1", "corr-dup", "linked"));

        assertThat(result).isSameAs(connection);
        assertThat(result.getState()).isEqualTo(IntegrationState.PENDING);
        verify(connectionRepository, never()).save(any());
    }

    /**
     * The unauthenticated webhook hot path: the subscription id from the delivery body selects which
     * stored secret the HMAC is checked against. Contract: exactly one indexed lookup (never a scan
     * over the connected fleet — {@code /webhooks/**} is exempt from the auth rate limiter), a forged
     * id resolves to nothing, and a delivery can never select another workspace's secret.
     */
    @org.junit.jupiter.api.Nested
    class FindOutlineSubscription {

        /**
         * A hand-rolled stub, not a Mockito mock: the projections are constructed inside the
         * argument list of the outer {@code when(...)}, and a nested {@code when()} there trips
         * {@code UnfinishedStubbingException}.
         */
        private ConnectionRepository.OutlineSubscriptionProjection projection(
                Long workspaceId, @org.jspecify.annotations.Nullable String secret) {
            return new ConnectionRepository.OutlineSubscriptionProjection() {
                @Override
                public Long getWorkspaceId() {
                    return workspaceId;
                }

                @Override
                public String getServerUrl() {
                    return "https://wiki.example.com";
                }

                @Override
                public @org.jspecify.annotations.Nullable String getSigningSecret() {
                    return secret;
                }
            };
        }

        @Test
        void resolvesTheMatchingSubscriptionToItsWorkspaceAndSecret() {
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-b"))
                    .thenReturn(java.util.List.of(projection(2L, "secret-b")));

            var resolved = service.findOutlineSubscription("sub-b");

            assertThat(resolved).isPresent();
            assertThat(resolved.get().workspaceId()).isEqualTo(2L);
            assertThat(resolved.get().serverUrl()).isEqualTo("https://wiki.example.com");
            assertThat(resolved.get().signingSecret()).isEqualTo("secret-b");
        }

        @Test
        void resolvesWithASingleIndexedLookupAndNeverEnumeratesTheFleet() {
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-b"))
                    .thenReturn(java.util.List.of(projection(2L, "secret-b")));

            service.findOutlineSubscription("sub-b");

            verify(connectionRepository, times(1)).findOutlineSubscriptionsBySubscriptionId("sub-b");
            // Never the 1+N amplifier: fleet enumeration + a per-workspace config fetch.
            verify(connectionRepository, never()).findWorkspaceIdsByKindAndState(any(), any());
            verify(connectionRepository, never())
                    .findFirstByWorkspaceIdAndKindAndStateOrderByCreatedAtDesc(anyLong(), any(), any());
        }

        @Test
        void aForgedSubscriptionIdResolvesToNoSecret() {
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("forged"))
                    .thenReturn(java.util.List.of());

            assertThat(service.findOutlineSubscription("forged")).isEmpty();
        }

        @Test
        void aDeliveryForWorkspaceANeverSelectsWorkspaceBsSecret() {
            // The query is keyed on the subscription id, so B's row is simply not in the result set.
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-a"))
                    .thenReturn(java.util.List.of(projection(1L, "secret-a")));
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-b"))
                    .thenReturn(java.util.List.of(projection(2L, "secret-b")));

            var a = service.findOutlineSubscription("sub-a").orElseThrow();
            var b = service.findOutlineSubscription("sub-b").orElseThrow();

            assertThat(a.workspaceId()).isEqualTo(1L);
            assertThat(a.signingSecret()).isEqualTo("secret-a");
            assertThat(b.workspaceId()).isEqualTo(2L);
            assertThat(b.signingSecret()).isEqualTo("secret-b");
        }

        @Test
        void failsClosedWhenTwoActiveConnectionsClaimTheSameSubscriptionId() {
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-dup"))
                    .thenReturn(java.util.List.of(projection(1L, "secret-a"), projection(2L, "secret-b")));

            assertThat(service.findOutlineSubscription("sub-dup")).isEmpty();
        }

        @Test
        void isEmptyWhenTheSubscriptionMatchesButNoSecretIsStored() {
            when(connectionRepository.findOutlineSubscriptionsBySubscriptionId("sub-a"))
                    .thenReturn(java.util.List.of(projection(1L, null)));

            assertThat(service.findOutlineSubscription("sub-a")).isEmpty();
        }

        @Test
        void isEmptyForABlankSubscriptionWithoutTouchingTheDatabase() {
            assertThat(service.findOutlineSubscription(null)).isEmpty();
            assertThat(service.findOutlineSubscription("  ")).isEmpty();

            verify(connectionRepository, never()).findOutlineSubscriptionsBySubscriptionId(any());
        }
    }

    /**
     * The lifecycle-event seam: {@code transition} signals genuine ACTIVE-boundary crossings to
     * vendor adapters and stays silent on no-ops and idempotent replays.
     */
    @Nested
    class LifecycleEvents {

        @Test
        void transitionToActive_publishesActivated() {
            Connection connection = pendingConnection();

            service.transition(
                    connection,
                    new TransitionRequest(
                            IntegrationState.ACTIVE, "INSTALL_BIND", "SYSTEM", "actor-1", "corr-1", "linked"));

            verify(eventPublisher)
                    .publishEvent(new ConnectionLifecycleEvent.Activated(55L, 7L, IntegrationKind.GITHUB));
        }

        @Test
        void transitionActiveToSuspended_publishesDeactivated() {
            Connection connection = connectionInState(IntegrationState.ACTIVE);

            service.transition(
                    connection,
                    new TransitionRequest(
                            IntegrationState.SUSPENDED, "SUSPEND", "ADMIN", "actor-1", "corr-2", "paused"));

            verify(eventPublisher)
                    .publishEvent(new ConnectionLifecycleEvent.Deactivated(55L, 7L, IntegrationKind.GITHUB));
        }

        @Test
        void sameStateNoOp_publishesNothing() {
            Connection connection = connectionInState(IntegrationState.ACTIVE);

            service.transition(
                    connection,
                    new TransitionRequest(
                            IntegrationState.ACTIVE, "INSTALL_BIND", "SYSTEM", "actor-1", "corr-3", "again"));

            Mockito.verifyNoInteractions(eventPublisher);
        }

        @Test
        void duplicateCorrelationId_publishesNothing() {
            Connection connection = pendingConnection();
            when(auditRepository.save(any(ConnectionAudit.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_connection_audit_idempotency"));

            service.transition(
                    connection,
                    new TransitionRequest(
                            IntegrationState.ACTIVE, "INSTALL_BIND", "SYSTEM", "actor-1", "corr-dup", "linked"));

            Mockito.verifyNoInteractions(eventPublisher);
        }
    }

    private Connection pendingConnection() {
        return connectionInState(IntegrationState.PENDING);
    }

    private Connection connectionInState(IntegrationState state) {
        Connection connection = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "100",
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        // Lifecycle events carry the connection id; persisted rows always have one.
        setId(connection, 55L);
        connection.setState(state);
        Mockito.lenient()
                .when(connectionRepository.findByIdAndWorkspaceId(connection.getId(), workspace.getId()))
                .thenReturn(java.util.Optional.of(connection));
        return connection;
    }

    private Connection connection(IntegrationKind kind, ConnectionConfig config) {
        Connection connection = new Connection(workspace, kind, kind.name().toLowerCase(), config);
        setId(connection, 55L + kind.ordinal());
        connection.setState(IntegrationState.ACTIVE);
        Mockito.lenient()
                .when(connectionRepository.findByIdAndWorkspaceId(connection.getId(), workspace.getId()))
                .thenReturn(java.util.Optional.of(connection));
        return connection;
    }

    private static TransitionRequest uninstallRequest(String correlationId) {
        return new TransitionRequest(
                IntegrationState.UNINSTALLED, "UNINSTALL", "SYSTEM", "system", correlationId, "removed");
    }

    private static TransitionRequest disconnectRequest() {
        return new TransitionRequest(
                IntegrationState.UNINSTALLED, "DISCONNECT", "ADMIN", "actor-1", "corr-disconnect", "removed");
    }

    private static void setId(Connection connection, long id) {
        try {
            Field idField = Connection.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(connection, id);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A GitHub App connection runs on no stored token: the write refuses one rather than overwriting its identity. */
    @Test
    void shouldRefuseToReplaceTheTokenOfAnAppInstallation() {
        Connection app = new Connection(
                TestEntities.workspace(7L),
                IntegrationKind.GITHUB,
                "acme",
                new ConnectionConfig.GitHubAppConfig(100L, null, null, Set.of()));
        when(connectionRepository.findActive(7L, IntegrationKind.GITHUB)).thenReturn(Optional.of(app));

        assertThatThrownBy(
                        () -> service.rotateBearerToken(7L, IntegrationKind.GITHUB, new BearerToken("ghp-new", null)))
                .isInstanceOf(ConnectionModeConflictException.class)
                .hasMessageContaining("App installation");
        verify(connectionRepository, never()).save(any());
    }
}
