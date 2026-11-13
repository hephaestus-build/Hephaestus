package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ProviderResourceRepairAdminService;
import de.tum.cit.aet.hephaestus.integration.core.connection.api.ReleaseProviderResourceRepairRequestDTO;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.testconfig.SecurityRetentionDeletionProof;
import de.tum.cit.aet.hephaestus.testconfig.WithMockUser;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.Serial;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@SecurityRetentionDeletionProof(
        table = "provider_resource_repair",
        resolution = "resolutionDeletesTheRetainedIdentity",
        adminRelease = "adminReleaseDeletesAnOrphanAndRecordsItsConfirmation")
class ProviderResourceRepairDeletionIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WebTestClient http;

    @Autowired
    private ProviderResourceRepairRepository rows;

    @Autowired
    private ConnectionProviderRepairRepository references;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private CredentialReader credentials;

    @Autowired
    private PlatformTransactionManager manager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProviderResourceRepairAdminService admin;

    @PersistenceContext
    private @Nullable EntityManager entities;

    private final TestClock clock = new TestClock();
    private final FakeProvider provider = new FakeProvider();
    private final List<UUID> created = new ArrayList<>();
    private ProviderResourceRepairs repairs;
    private ProviderResourceRepairDispatcher dispatcher;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(manager);
        var beans = new StaticListableBeanFactory();
        beans.addBean("provider", provider);
        repairs = new ProviderResourceRepairs(
                rows,
                references,
                connections,
                credentials,
                beans.getBeanProvider(ProviderResourceAdapter.class),
                event -> {},
                clock);
        ReflectionTestUtils.setField(repairs, "entityManager", Objects.requireNonNull(entities));
        dispatcher = new ProviderResourceRepairDispatcher(
                rows, references, repairs, manager, clock, new SimpleMeterRegistry());
        ReflectionTestUtils.setField(dispatcher, "entityManager", Objects.requireNonNull(entities));
    }

    @AfterEach
    void removeOwnFences() {
        transactions.executeWithoutResult(status -> {
            for (UUID id : created) {
                references.deleteAll(references.findByRepair_Id(id));
                rows.deleteById(id);
            }
        });
    }

    @Test
    void resolutionDeletesTheRetainedIdentity() {
        UUID id = pending();
        provider.upstream.clear();
        dispatcher.dispatch(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_resource_repair WHERE id = ?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM connection_provider_repair WHERE repair_id = ?", Long.class, id))
                .isZero();
    }

    @Test
    @WithMockUser(authorities = "app_admin")
    void adminReleaseDeletesAnOrphanAndRecordsItsConfirmation() {
        var actor = persistAccount("repair admin " + UUID.randomUUID());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                Objects.requireNonNull(actor.getId()).toString(), new String[] {"app_admin"}, "repair-admin"));
        UUID id = pending();
        String identity = rows.findById(id).orElseThrow().getIdentityHash();
        transactions.executeWithoutResult(status -> references.deleteAll(references.findByRepair_Id(id)));
        admin.releaseOrphan(id, new ReleaseProviderResourceRepairRequestDTO(true, identity));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_resource_repair WHERE id = ?", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT actor_account_id FROM config_audit_event WHERE entity_type = 'PROVIDER_RESOURCE_RELEASE' AND entity_id = ?",
                        Long.class,
                        id.toString()))
                .isEqualTo(actor.getId());
    }

    @Test
    @WithMockUser(authorities = "workspace_admin")
    void aWorkspaceOnlyAdministratorCannotReleaseTheIdentity() {
        UUID id = pending();
        var row = rows.findById(id).orElseThrow();
        assertThatThrownBy(() -> admin.releaseOrphan(
                        id, new ReleaseProviderResourceRepairRequestDTO(true, row.getIdentityHash())))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(rows.existsById(id)).isTrue();
    }

    @Test
    @WithMockUser(authorities = "app_admin")
    void substitutedWorkspaceAndConnectionIdsReleaseNothing() {
        var actor = persistAccount("mismatch admin " + UUID.randomUUID());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                Objects.requireNonNull(actor.getId()).toString(), new String[] {"app_admin"}, "repair-admin"));
        UUID id = pending();
        var reference = references.findByRepair_Id(id).getFirst();
        var row = rows.findById(id).orElseThrow();
        var confirmation = new ReleaseProviderResourceRepairRequestDTO(true, row.getIdentityHash());
        assertThatThrownBy(() -> admin.release(
                        reference.getWorkspaceId() + 1,
                        reference.getConnection().getId(),
                        id,
                        confirmation))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> admin.release(
                        reference.getWorkspaceId(), reference.getConnection().getId() + 1, id, confirmation))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(rows.existsById(id)).isTrue();
    }

    @Test
    void absenceAfterAnUncertainDeleteCannotAdmitAReusedIdentity() {
        UUID id = pending();
        Resource target = rows.findById(id).orElseThrow().resource();
        dispatcher.dispatch(id);
        assertThat(rows.findById(id).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.AWAITING_CONFIRMATION);
        assertThat(provider.removals).containsExactly(target.resourceId());
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repairs.requireAdoptable(
                        new IntegrationRef(IntegrationKind.OUTLINE, 1, "provider"),
                        new ConnectionConfig.OutlineConfig(target.origin(), target.resourceId(), "key/route", Set.of()),
                        null)))
                .isInstanceOf(ResponseStatusException.class);
        provider.upstream.clear();
        clock.now = clock.now.plusSeconds(61);
        dispatcher.dispatch(id);
        assertThat(rows.findById(id).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.CONFIRMATION_REQUIRED);
        provider.upstream.put(target.resourceId(), "replacement with reused ID");
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repairs.requireAdoptable(
                        new IntegrationRef(IntegrationKind.OUTLINE, 1, "provider"),
                        new ConnectionConfig.OutlineConfig(
                                target.origin(), target.resourceId(), "new-key/new-route", Set.of()),
                        null)))
                .isInstanceOf(ResponseStatusException.class);
        String freshId = target.resourceId() + "-fresh";
        transactions.executeWithoutResult(status -> repairs.requireAdoptable(
                new IntegrationRef(IntegrationKind.OUTLINE, 1, "provider"),
                new ConnectionConfig.OutlineConfig(target.origin(), freshId, "new-key/fresh-route", Set.of()),
                null));
        provider.upstream.put(freshId, "fresh adopted subscription");
        provider.completeAcceptedRemovals();
        clock.now = clock.now.plusSeconds(600);
        dispatcher.dispatch(id);
        assertThat(provider.upstream).containsOnlyKeys(freshId);
        assertThat(provider.removals).containsExactly(target.resourceId());
        assertThat(rows.existsById(id)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(
            value = CrashPoint.class,
            names = {"BEFORE_DISPATCH", "AFTER_DISPATCH"})
    void aCrashCannotTurnAPossibleDispatchIntoProofOfAbsence(CrashPoint point) {
        UUID id = pending();
        provider.crash = point;
        assertThatThrownBy(() -> dispatcher.dispatch(id)).isInstanceOf(SimulatedCrash.class);
        assertThat(rows.findById(id).orElseThrow().getRemovalAttempts())
                .isEqualTo(point == CrashPoint.BEFORE_DISPATCH ? 0 : 1);
        provider.upstream.clear();
        clock.now = clock.now.plusSeconds(601);
        dispatcher.dispatch(id);
        assertThat(rows.existsById(id)).isEqualTo(point == CrashPoint.AFTER_DISPATCH);
        if (point == CrashPoint.AFTER_DISPATCH) {
            assertThat(rows.findById(id).orElseThrow().getState())
                    .isEqualTo(ProviderResourceRepair.State.CONFIRMATION_REQUIRED);
        }
    }

    @Test
    void shouldAllowOnlyAnInstanceAdminToReleaseThroughTheOwningConnectionEndpoint() {
        UUID id = pending();
        var ref = references.findByRepair_Id(id).getFirst();
        Workspace owner = workspaces.findById(ref.getWorkspaceId()).orElseThrow();
        long member = Objects.requireNonNull(ensureAdminMembership(owner).getAccountId());
        String path = "/workspaces/" + owner.getWorkspaceSlug() + "/connections/"
                + ref.getConnection().getId() + "/provider-repairs/" + id + "/release";
        var body = new ReleaseProviderResourceRepairRequestDTO(
                true, rows.findById(id).orElseThrow().getIdentityHash());
        http.post()
                .uri(path)
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + member))
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(rows.existsById(id)).isTrue();
        http.post()
                .uri(path)
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + member))
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);
        assertThat(rows.existsById(id)).isFalse();
        assertThat(((ConnectionConfig.OutlineConfig) connections
                                .findById(ref.getConnection().getId())
                                .orElseThrow()
                                .getConfig())
                        .webhookSubscriptionId())
                .isNull();
        transactions.executeWithoutResult(status ->
                repairs.captureSuspendedHook(owner.getId(), ref.getConnection().getId()));
        assertThat(references.findByWorkspaceIdAndConnection_Id(
                        owner.getId(), ref.getConnection().getId()))
                .isEmpty();

        assertThat(jdbc.queryForObject(
                        "SELECT actor_account_id FROM config_audit_event WHERE entity_type = 'PROVIDER_RESOURCE_RELEASE' AND entity_id = ?",
                        Long.class,
                        id.toString()))
                .isEqualTo(member);
    }

    @Test
    void shouldRejectAnOrphanReleaseForAFenceWithASurvivingConnection() {
        UUID id = pending();
        var actor = persistAccount("release actor " + UUID.randomUUID());
        var body = new ReleaseProviderResourceRepairRequestDTO(
                true, rows.findById(id).orElseThrow().getIdentityHash());
        http.post()
                .uri("/admin/provider-resource-repairs/" + id + "/release")
                .headers(headers -> headers.setBearerAuth("mock-jwt-admin-" + actor.getId()))
                .bodyValue(body)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(rows.existsById(id)).isTrue();
    }

    @Test
    void shouldReadRepairsOnlyThroughTheirScopedOwnerAndExposeOwnersToInstanceAdmins() {
        UUID mine = pending();
        UUID other = pending();
        var ref = references.findByRepair_Id(mine).getFirst();
        String slug = workspaces.findById(ref.getWorkspaceId()).orElseThrow().getWorkspaceSlug();
        var actor = persistAccount("repair reader " + UUID.randomUUID());
        String token = "mock-jwt-admin-" + actor.getId();
        http.get()
                .uri("/workspaces/" + slug + "/connections/"
                        + ref.getConnection().getId() + "/provider-repairs")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(mine.toString())
                .jsonPath("$.content[0].owners.length()")
                .isEqualTo(0);
        http.get()
                .uri("/admin/provider-resource-repairs?size=100")
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[?(@.id == '" + mine + "')].owners[0].workspaceSlug")
                .isEqualTo(List.of(slug));
        assertThat(rows.existsById(other)).isTrue();
    }

    @Test
    void shouldShowAFreshConnectionUsingADifferentResourceInTheOwningWorkspace() {
        UUID id = pending();
        var reference = references.findByRepair_Id(id).getFirst();
        transactions.executeWithoutResult(status -> {
            var workspace = workspaces.findById(reference.getWorkspaceId()).orElseThrow();
            var replacement = new Connection(
                    workspace,
                    IntegrationKind.OUTLINE,
                    "replacement",
                    new ConnectionConfig.OutlineConfig(
                            "https://outline.example.test", "fresh-" + id, "key/fresh-route", Set.of()));
            ConnectionFixtures.reachState(replacement, IntegrationState.ACTIVE);
            connections.saveAndFlush(replacement);
        });
        var projected =
                admin.list(reference.getWorkspaceId(), reference.getConnection().getId(), 0, 25);
        assertThat(projected.content())
                .singleElement()
                .satisfies(row -> assertThat(row.replacementInUse()).isTrue());
    }

    @Test
    void shouldRetainAnUntrackedPreCutoverRemovalEvenAfterVerifiedAbsence() {
        UUID id = pending(true);
        Resource target = rows.findById(id).orElseThrow().resource();
        assertThat(rows.findById(id).orElseThrow().getRemovalAttempts()).isEqualTo(1);
        provider.upstream.clear();
        dispatcher.dispatch(id);
        assertThat(rows.findById(id).orElseThrow().getState())
                .isEqualTo(ProviderResourceRepair.State.CONFIRMATION_REQUIRED);
        assertThat(provider.removals).isEmpty();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repairs.requireAdoptable(
                        new IntegrationRef(target.kind(), 1, "provider"),
                        new ConnectionConfig.OutlineConfig(
                                target.origin(), target.resourceId(), "key/new-route", Set.of()),
                        null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void shouldCaptureALegacySuspendedHookBeforeCredentialCompletionReplacesItsConfig() {
        UUID seed = pending();
        var reference = references.findByRepair_Id(seed).getFirst();
        long connectionId = reference.getConnection().getId();
        String resourceId = rows.findById(seed).orElseThrow().resource().resourceId();
        transactions.executeWithoutResult(status -> {
            references.deleteAll(references.findByRepair_Id(seed));
            rows.deleteById(seed);
        });
        jdbc.update("UPDATE connection SET provider_repair_capture_generation = NULL WHERE id = ?", connectionId);
        UUID captured = transactions.execute(status -> {
            Connection owner = connections.findById(connectionId).orElseThrow();
            var fresh = new ConnectionConfig.OutlineConfig("https://outline.example.test", null, null, Set.of());
            Connection locked = repairs.prepareCredentialCompletion(owner, fresh, null);
            locked.setConfig(fresh);
            return references
                    .findByWorkspaceIdAndConnection_Id(owner.getWorkspace().getId(), connectionId)
                    .getFirst()
                    .getRepair()
                    .getId();
        });
        created.add(Objects.requireNonNull(captured));
        var target = rows.findById(captured).orElseThrow();
        assertThat(target.resource().resourceId()).isEqualTo(resourceId);
        assertThat(target.getRemovalAttempts()).isEqualTo(1);
    }

    @Test
    @WithMockUser(authorities = "app_admin")
    void shouldAuditEveryReferencingWorkspaceWhenAnInstanceAdminReleasesASharedFence() {
        var actor = persistAccount("shared repair admin " + UUID.randomUUID());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                Objects.requireNonNull(actor.getId()).toString(), new String[] {"app_admin"}, "shared-admin"));
        UUID id = pending();
        var first = references.findByRepair_Id(id).getFirst();
        long otherWorkspace = Objects.requireNonNull(transactions.execute(status -> {
            Workspace other = workspaces.save(WorkspaceTestFixtures.activeWorkspace("shared-" + UUID.randomUUID()));
            var target = rows.findById(id).orElseThrow().resource();
            var owner = new Connection(
                    other,
                    IntegrationKind.OUTLINE,
                    "shared",
                    new ConnectionConfig.OutlineConfig(target.origin(), target.resourceId(), "key/route", Set.of()));
            ConnectionFixtures.reachState(owner, IntegrationState.UNINSTALLED);
            owner = connections.saveAndFlush(owner);
            references.save(
                    new ConnectionProviderRepair(owner, rows.findById(id).orElseThrow()));
            return other.getId();
        }));
        admin.release(
                first.getWorkspaceId(),
                first.getConnection().getId(),
                id,
                new ReleaseProviderResourceRepairRequestDTO(
                        true, rows.findById(id).orElseThrow().getIdentityHash()));
        assertThat(jdbc.queryForList(
                        "SELECT workspace_id FROM config_audit_event WHERE entity_type = 'PROVIDER_RESOURCE_RELEASE' AND entity_id = ?",
                        Long.class,
                        id.toString()))
                .containsExactlyInAnyOrder(first.getWorkspaceId(), otherWorkspace);
        assertThat(rows.existsById(id)).isFalse();
    }

    @Test
    @WithMockUser(authorities = "app_admin")
    void shouldRefuseAReleaseWhileTheExternalRegistrationIsStillInProgress() {
        var actor = persistAccount("registration admin " + UUID.randomUUID());
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                Objects.requireNonNull(actor.getId()).toString(), new String[] {"app_admin"}, "registration-admin"));
        UUID seed = pending();
        var owner = references.findByRepair_Id(seed).getFirst();
        UUID registration = Objects.requireNonNull(transactions.execute(status -> {
            var record = rows.save(new ProviderResourceRepair(
                    new Resource(
                            IntegrationKind.OUTLINE,
                            "https://outline.example.test",
                            ProviderResourceAdapter.ResourceType.REGISTRATION,
                            "",
                            UUID.randomUUID().toString(),
                            null),
                    clock.instant()));
            references.save(new ConnectionProviderRepair(
                    connections.findById(owner.getConnection().getId()).orElseThrow(), record));
            return record.getId();
        }));
        created.add(registration);
        assertThatThrownBy(() -> admin.release(
                        owner.getWorkspaceId(),
                        owner.getConnection().getId(),
                        registration,
                        new ReleaseProviderResourceRepairRequestDTO(
                                true, rows.findById(registration).orElseThrow().getIdentityHash())))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(rows.existsById(registration)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM config_audit_event WHERE entity_type = 'PROVIDER_RESOURCE_RELEASE' AND entity_id = ?",
                        Long.class,
                        registration.toString()))
                .isZero();
    }

    @Test
    void shouldOmitTheTokenFingerprintFromAWorkspaceAdministratorsRepairResponse() {
        UUID seed = pending();
        var source = references.findByRepair_Id(seed).getFirst();
        Workspace workspace = workspaces.findById(source.getWorkspaceId()).orElseThrow();
        String fingerprint = ProviderResourceAdapter.slackTokenFingerprint("test-token-" + seed);
        long connectionId = Objects.requireNonNull(transactions.execute(status -> {
            String team = "T-" + seed;
            var owner = new Connection(
                    workspace,
                    IntegrationKind.SLACK,
                    team,
                    new ConnectionConfig.SlackConfig(team, "U-BOT", fingerprint, "Team", null, Set.of()));
            ConnectionFixtures.reachState(owner, IntegrationState.UNINSTALLED);
            owner = connections.saveAndFlush(owner);
            var record = rows.save(new ProviderResourceRepair(
                    new Resource(
                            IntegrationKind.SLACK,
                            "https://slack.com",
                            ProviderResourceAdapter.ResourceType.BOT_TOKEN,
                            team + "/U-BOT",
                            fingerprint,
                            null),
                    clock.instant()));
            created.add(record.getId());
            references.save(new ConnectionProviderRepair(owner, record));
            return owner.getId();
        }));
        long member = Objects.requireNonNull(ensureAdminMembership(workspace).getAccountId());
        String response = Objects.requireNonNull(http.get()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/connections/" + connectionId
                        + "/provider-repairs")
                .headers(headers -> headers.setBearerAuth("mock-jwt-member-" + member))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody());
        assertThat(response).isNotNull().doesNotContain(fingerprint).contains("BOT_TOKEN");
    }

    private UUID pending() {
        return pending(false);
    }

    private UUID pending(boolean earlierCallUntracked) {
        UUID id = transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Workspace workspace = workspaces.save(WorkspaceTestFixtures.activeWorkspace("repair-" + suffix));
            Connection owner = new Connection(
                    workspace,
                    IntegrationKind.OUTLINE,
                    "repair",
                    new ConnectionConfig.OutlineConfig("https://outline.example.test", suffix, "key/route", Set.of()));
            provider.upstream.put(suffix, "owned old subscription");
            ConnectionFixtures.reachState(
                    owner, earlierCallUntracked ? IntegrationState.SUSPENDED : IntegrationState.ACTIVE);
            owner = connections.saveAndFlush(owner);
            repairs.requestRemoval(owner, true);
            ConnectionFixtures.reachState(owner, IntegrationState.SUSPENDED);
            return references
                    .findByWorkspaceIdAndConnection_Id(workspace.getId(), owner.getId())
                    .getFirst()
                    .getRepair()
                    .getId();
        });
        created.add(Objects.requireNonNull(id));
        return id;
    }

    private static final class FakeProvider implements ProviderResourceAdapter {
        CrashPoint crash = CrashPoint.NONE;
        final Map<String, String> upstream = new ConcurrentHashMap<>();
        final List<String> removals = new ArrayList<>();

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.OUTLINE;
        }

        @Override
        public List<Resource> resources(
                IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
            if (!(config instanceof ConnectionConfig.OutlineConfig outline) || outline.webhookSubscriptionId() == null)
                return List.of();
            return List.of(new Resource(
                    kind(),
                    "https://outline.example.test",
                    ResourceType.SUBSCRIPTION,
                    "",
                    outline.webhookSubscriptionId(),
                    "ownership"));
        }

        @Override
        public Removal remove(
                Resource resource, @Nullable CredentialBundle credential, BooleanSupplier beforeDispatch) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                    .isFalse();
            if (crash == CrashPoint.BEFORE_DISPATCH) {
                crash = CrashPoint.NONE;
                throw new SimulatedCrash();
            }
            if (!upstream.containsKey(resource.resourceId())) return Removal.VERIFIED_ABSENT;
            if (!beforeDispatch.getAsBoolean()) return Removal.CANCELLED;
            removals.add(resource.resourceId());
            if (crash == CrashPoint.AFTER_DISPATCH) {
                crash = CrashPoint.NONE;
                throw new SimulatedCrash();
            }
            return Removal.AWAITING_CONFIRMATION;
        }

        void completeAcceptedRemovals() {
            removals.forEach(upstream::remove);
        }
    }

    enum CrashPoint {
        NONE,
        BEFORE_DISPATCH,
        AFTER_DISPATCH
    }

    private static final class SimulatedCrash extends Error {
        @Serial
        private static final long serialVersionUID = 1L;
    }

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-13T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
