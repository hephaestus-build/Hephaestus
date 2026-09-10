package de.tum.cit.aet.hephaestus.workspace.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtPrincipalFactory;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobStatus;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncJobDTO;
import de.tum.cit.aet.hephaestus.testconfig.OrganizationalIdentityIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceAccountMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

/** Real signed sessions, persisted policies and leased jobs; only outbound directory HTTP is a fixture. */
class DirectoryAccessIntegrationTest extends OrganizationalIdentityIntegrationTest {
    private static final String ISSUER = "https://identity.example.com/realms/team";
    private static final String OTHER_ISSUER = "https://identity.example.com/realms/other";

    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAccountMembershipRepository memberships;

    @Autowired
    private LoginProviderRepository providers;

    @Autowired
    private GitProviderRegistry identityProviders;

    @Autowired
    private IdentityLinkRepository links;

    @Autowired
    private HephaestusJwtIssuer jwt;

    @Autowired
    private JwtPrincipalFactory principals;

    @Autowired
    private DirectoryPolicyRepository policies;

    @Autowired
    private DirectoryPolicyService policyService;

    @Autowired
    private DirectoryReconciliationService reconciliation;

    @Autowired
    private DirectoryPolicyErasure erasure;

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactions;

    @Autowired
    private SyncJobRepository jobs;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ObjectMapper mapper;

    private Account owner;
    private Account operator;
    private Workspace workspace;
    private long providerId;
    private final Map<String, Set<String>> directoryGroups = new ConcurrentHashMap<>();
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean failPages = new AtomicBoolean();
    private CountDownLatch pageReached = new CountDownLatch(0);
    private CountDownLatch releasePage = new CountDownLatch(0);

    @BeforeEach
    void configureFixture() throws Exception {
        owner = account("Workspace owner");
        operator = account("Directory operator");
        operator.setAppRole(Account.AppRole.APP_ADMIN);
        accounts.saveAndFlush(operator);
        workspace = workspace("directory-access", owner);
        providerId = source("organization", ISSUER);
        source("other", OTHER_ISSUER);
        client.patch()
                .uri("/admin/login-providers/organization/directory-groups")
                .headers(headers -> headers.setBearerAuth(token(operator)))
                .bodyValue(Map.of("groupIds", List.of("eligible", "alternate")))
                .exchange()
                .expectStatus()
                .isOk();
        when(requests.createRequest(any(URI.class), any(HttpMethod.class))).thenAnswer(invocation -> {
            URI uri = invocation.getArgument(0);
            HttpMethod method = invocation.getArgument(1);
            return new MockClientHttpRequest(method, uri) {
                @Override
                protected ClientHttpResponse executeInternal() {
                    String path = uri.getPath();
                    if (path.endsWith("/protocol/openid-connect/token")) {
                        assertThat(method).isEqualTo(HttpMethod.POST);
                        assertThat(getBodyAsString()).contains("client_id=readonly", "client_secret=fixture-secret");
                        return json(Map.of("access_token", "fixture-token"), HttpStatus.OK);
                    }
                    assertThat(method).isEqualTo(HttpMethod.GET);
                    assertThat(getHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-token");
                    String prefix = "/admin/realms/team/";
                    assertThat(path).startsWith(prefix);
                    String[] segments = path.substring(prefix.length()).split("/");
                    int first = Integer.parseInt(Objects.requireNonNullElse(
                            UriComponentsBuilder.fromUri(uri)
                                    .build()
                                    .getQueryParams()
                                    .getFirst("first"),
                            "0"));
                    if (segments[0].equals("groups")) {
                        String group = segments[1];
                        if (segments.length == 2)
                            return json(Map.of("id", group, "name", "Approved " + group), HttpStatus.OK);
                        pageReached.countDown();
                        try {
                            assertThat(releasePage.await(20, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        if (failPages.get())
                            return json(Map.of("error", "private fixture details"), HttpStatus.FORBIDDEN);
                        var page = directoryGroups.entrySet().stream()
                                .filter(entry -> entry.getValue().contains(group))
                                .sorted(Map.Entry.comparingByKey())
                                .skip(first)
                                .limit(1)
                                .map(entry ->
                                        Map.of("id", entry.getKey(), "enabled", !disabled.contains(entry.getKey())))
                                .toList();
                        return json(page, HttpStatus.OK);
                    }
                    assertThat(segments[0]).isEqualTo("users");
                    String subject = segments[1];
                    Set<String> groups = directoryGroups.get(subject);
                    if (groups == null) return json(Map.of("error", "absent"), HttpStatus.NOT_FOUND);
                    if (segments.length == 2)
                        return json(Map.of("id", subject, "enabled", !disabled.contains(subject)), HttpStatus.OK);
                    return json(
                            groups.stream()
                                    .sorted()
                                    .skip(first)
                                    .limit(1)
                                    .map(id -> Map.of("id", id))
                                    .toList(),
                            HttpStatus.OK);
                }
            };
        });
    }

    @Test
    void shouldRequireOwnerPreviewApprovalAndPreserveManualExceptionsThroughDepartureAndRejoining() {
        var member = linkedAccount("Directory member", "member");
        var exception = linkedAccount("Manual exception", "exception");
        membership(workspace, exception, WorkspaceRole.ADMIN);
        directoryGroups.put("member", Set.of("eligible"));
        directoryGroups.put("exception", Set.of("eligible"));
        configure(workspace, owner, Set.of("eligible")).expectStatus().isOk();
        assertThat(membership(member)).isEmpty();
        run(workspace, owner, true, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member)).isEmpty();
        approve(workspace, owner).expectStatus().isOk();
        assertThat(membership(member))
                .get()
                .extracting(WorkspaceAccountMembership::getSource)
                .isEqualTo(WorkspaceAccountMembership.Source.DIRECTORY);
        assertThat(membership(exception))
                .get()
                .extracting(WorkspaceAccountMembership::getRole)
                .isEqualTo(WorkspaceRole.ADMIN);
        directoryGroups.put("member", Set.of());
        directoryGroups.put("exception", Set.of());
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member)).isEmpty();
        assertThat(membership(exception)).isPresent();
        directoryGroups.put("member", Set.of("eligible"));
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member)).isPresent();
    }

    @Test
    void shouldKeepSuspensionStickyAndRequireAnExplicitEndToManualExceptions() {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        activate();
        client.delete()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/members/" + member.getId())
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isNoContent();
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member))
                .get()
                .extracting(WorkspaceAccountMembership::isSuspended)
                .isEqualTo(true);
        client.post()
                .uri("/user/workspace-access/" + workspace.getId())
                .headers(headers -> headers.setBearerAuth(token(member)))
                .exchange()
                .expectStatus()
                .isForbidden();
        client.post()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/members/assign")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("accountId", member.getId(), "role", "MEMBER"))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(membership(member))
                .get()
                .extracting(WorkspaceAccountMembership::getSource)
                .isEqualTo(WorkspaceAccountMembership.Source.MANUAL);
        client.put()
                .uri(path(workspace, "/members/" + member.getId()))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isOk();
        assertThat(membership(member))
                .get()
                .extracting(WorkspaceAccountMembership::getSource)
                .isEqualTo(WorkspaceAccountMembership.Source.DIRECTORY);
    }

    @Test
    void shouldNotInferDeparturesOrAdmitNewAccountsFromFailedOrDisabledEvidence() {
        var existing = linkedAccount("Existing", "existing");
        directoryGroups.put("existing", Set.of("eligible"));
        directoryGroups.put("not-yet-linked", Set.of("eligible"));
        activate();
        var newcomer = linkedAccount("Newcomer", "not-yet-linked");
        failPages.set(true);
        run(workspace, owner, false, SyncJobStatus.FAILED);
        assertThat(membership(existing)).isPresent();
        assertThat(membership(newcomer)).isEmpty();
        client.get()
                .uri("/user/workspace-access")
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
        failPages.set(false);
        client.patch()
                .uri("/admin/login-providers/organization")
                .headers(headers -> headers.setBearerAuth(token(operator)))
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/user/workspace-access/" + workspace.getId())
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isNotFound();
        assertThat(membership(existing)).isPresent();
    }

    @Test
    void shouldExposeOnlyCurrentAccountsVerifiedOffersBeforeFirstWorkspaceAccess() {
        directoryGroups.put("future-subject", Set.of("eligible"));
        activate();
        var newcomer = linkedAccount("Newcomer", "future-subject");
        var lookalike = linkedAccount("Newcomer", "different-subject");
        client.get()
                .uri("/user/workspace-access")
                .headers(headers -> headers.setBearerAuth(token(lookalike)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
        client.post()
                .uri("/user/workspace-access/" + workspace.getId())
                .headers(headers -> headers.setBearerAuth(token(lookalike)))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.get()
                .uri("/user/workspace-access")
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[0].workspaceId")
                .isEqualTo(workspace.getId())
                .jsonPath("$[0].joined")
                .isEqualTo(false)
                .jsonPath("$[0].members")
                .doesNotExist();
        client.post()
                .uri("/user/workspace-access/" + workspace.getId())
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.joined")
                .isEqualTo(true);
        assertThat(membership(newcomer)).isPresent();
    }

    @Test
    void shouldAllowAdminsToReconcileButNeverWidenOrApproveEligibility() {
        var admin = account("Workspace administrator");
        membership(workspace, admin, WorkspaceRole.ADMIN);
        activate();
        configure(workspace, admin, Set.of("alternate")).expectStatus().isForbidden();
        approve(workspace, admin).expectStatus().isForbidden();
        client.post()
                .uri(path(workspace, "/previews"))
                .headers(headers -> headers.setBearerAuth(token(admin)))
                .exchange()
                .expectStatus()
                .isForbidden();
        status(workspace, admin, "ENDED").expectStatus().isForbidden();
        run(workspace, admin, false, SyncJobStatus.SUCCEEDED);
        var unrelated = workspace("other-workspace", admin);
        client.get()
                .uri(path(unrelated, ""))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    @Test
    void shouldInvalidateStaleJobsWhenTheOwnerChangesConfigurationAndKeepOneLease() throws Exception {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        configure(workspace, owner, Set.of("eligible")).expectStatus().isOk();
        pageReached = new CountDownLatch(1);
        releasePage = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> request(workspace, owner, true));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> pageReached.getCount() == 0);
                SyncJobDTO duplicate = request(workspace, owner, true);
                configure(workspace, owner, Set.of("alternate")).expectStatus().isOk();
                releasePage.countDown();
                SyncJobDTO completed = first.get(20, TimeUnit.SECONDS);
                assertThat(duplicate.id()).isEqualTo(completed.id());
                awaitJob(completed, SyncJobStatus.FAILED);
            } finally {
                releasePage.countDown();
            }
            assertThat(policies.findByWorkspace_Id(workspace.getId()))
                    .get()
                    .extracting(DirectoryPolicy::getPreviewSnapshot)
                    .isNull();
            assertThat(membership(member)).isEmpty();
        } finally {
            releasePage.countDown();
        }
    }

    @Test
    void shouldRemoveDirectoryAccessImmediatelyOnUnlinkAndNeverReclassifyItAsManual() {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        activate();
        IdentityLink target = links.findActiveByAccountId(Objects.requireNonNull(member.getId()))
                .getFirst();
        link(member, identityProviders.resolveProviderId("OIDC", OTHER_ISSUER), "other-subject");
        client.delete()
                .uri("/user/identities/" + target.getId())
                .headers(headers -> headers.setBearerAuth(token(member)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(membership(member)).isEmpty();
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member)).isEmpty();
    }

    @Test
    void shouldPauseNewGrantsWhileConfirmingDeparturesAndEndManagementWithoutTouchingOwners() {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        activate();
        status(workspace, owner, "PAUSED").expectStatus().isOk();
        var newcomer = linkedAccount("Newcomer", "newcomer");
        directoryGroups.put("newcomer", Set.of("eligible"));
        directoryGroups.put("member", Set.of());
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member)).isEmpty();
        assertThat(membership(newcomer)).isEmpty();
        status(workspace, owner, "ACTIVE").expectStatus().isOk();
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(newcomer)).isPresent();
        status(workspace, owner, "ENDED").expectStatus().isOk();
        assertThat(membership(newcomer)).isEmpty();
        assertThat(membership(owner))
                .get()
                .extracting(WorkspaceAccountMembership::getRole)
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void shouldRejectExpiredEvidenceWithoutRemovingPreviouslyGrantedAccess() {
        var existing = linkedAccount("Existing", "existing");
        directoryGroups.put("existing", Set.of("eligible"));
        directoryGroups.put("newcomer", Set.of("eligible"));
        activate();
        var newcomer = linkedAccount("Newcomer", "newcomer");
        var policy = policies.findByWorkspace_Id(workspace.getId()).orElseThrow();
        var snapshot = Objects.requireNonNull(policy.getActiveSnapshot());
        var stale = new DirectorySnapshot(
                Instant.now().minus(Duration.ofMinutes(16)),
                Instant.now(),
                snapshot.configurationVersion(),
                snapshot.sourceVersion(),
                snapshot.groupIds(),
                snapshot.groupNames(),
                snapshot.eligibleSubjects(),
                snapshot.confirmedDepartures());
        policy.setActiveSnapshot(stale);
        policy.setPreviewSnapshot(stale);
        policies.saveAndFlush(policy);
        client.get()
                .uri("/user/workspace-access")
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
        client.post()
                .uri("/user/workspace-access/" + workspace.getId())
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isNotFound();
        approve(workspace, owner).expectStatus().isBadRequest();
        assertThat(membership(existing)).isPresent();
        assertThat(membership(newcomer)).isEmpty();
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(newcomer)).isPresent();
    }

    @Test
    void shouldRejectAReadWhoseSourceApprovalChangesWhileItIsRunning() throws Exception {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        configure(workspace, owner, Set.of("eligible")).expectStatus().isOk();
        pageReached = new CountDownLatch(1);
        releasePage = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> request(workspace, owner, true));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> pageReached.getCount() == 0);
                client.patch()
                        .uri("/admin/login-providers/organization/directory-groups")
                        .headers(headers -> headers.setBearerAuth(token(operator)))
                        .bodyValue(Map.of("groupIds", List.of("alternate")))
                        .exchange()
                        .expectStatus()
                        .isOk();
                releasePage.countDown();
                awaitJob(first.get(20, TimeUnit.SECONDS), SyncJobStatus.FAILED);
            } finally {
                releasePage.countDown();
            }
        }
        assertThat(policies.findByWorkspace_Id(workspace.getId()))
                .get()
                .extracting(DirectoryPolicy::getPreviewSnapshot)
                .isNull();
        assertThat(membership(member)).isEmpty();
    }

    @Test
    void shouldCancelAnIncompleteReadWithoutApplyingAnyDeparture() throws Exception {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        activate();
        directoryGroups.clear();
        pageReached = new CountDownLatch(1);
        releasePage = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> request(workspace, owner, false));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> pageReached.getCount() == 0);
                var running = request(workspace, owner, false);
                long connectionId = policies.findByWorkspace_Id(workspace.getId())
                        .orElseThrow()
                        .getConnectionId();
                client.patch()
                        .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/connections/" + connectionId
                                + "/sync/jobs/" + running.id())
                        .headers(headers -> headers.setBearerAuth(token(owner)))
                        .bodyValue(Map.of("cancelRequested", true))
                        .exchange()
                        .expectStatus()
                        .isAccepted();
                releasePage.countDown();
                awaitJob(first.get(20, TimeUnit.SECONDS), SyncJobStatus.CANCELLED);
            } finally {
                releasePage.countDown();
            }
        }
        assertThat(membership(member)).isPresent();
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(member)).isEmpty();
    }

    @Test
    void shouldRequireFreshApprovalAfterEndingAndReconnecting() {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        activate();
        status(workspace, owner, "ENDED").expectStatus().isOk();
        assertThat(membership(member)).isEmpty();
        configure(workspace, owner, Set.of("eligible")).expectStatus().isOk();
        assertThat(membership(member)).isEmpty();
        approve(workspace, owner).expectStatus().isBadRequest();
        run(workspace, owner, true, SyncJobStatus.SUCCEEDED);
        approve(workspace, owner).expectStatus().isOk();
        assertThat(membership(member)).isPresent();
    }

    @Test
    void shouldEndManagementEvenWhenTheConnectionIsUnavailableAndPreserveManualAccess() {
        var member = linkedAccount("Member", "member");
        var manual = linkedAccount("Manual administrator", "manual");
        membership(workspace, manual, WorkspaceRole.ADMIN);
        directoryGroups.put("member", Set.of("eligible"));
        directoryGroups.put("manual", Set.of("eligible"));
        activate();
        long connectionId =
                policies.findByWorkspace_Id(workspace.getId()).orElseThrow().getConnectionId();
        var connection = connections
                .findByIdAndWorkspaceId(connectionId, workspace.getId())
                .orElseThrow();
        connection.setState(de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState.SUSPENDED);
        connections.saveAndFlush(connection);
        status(workspace, owner, "ENDED").expectStatus().isOk();
        assertThat(membership(member)).isEmpty();
        assertThat(membership(manual))
                .get()
                .extracting(WorkspaceAccountMembership::getRole)
                .isEqualTo(WorkspaceRole.ADMIN);
        assertThat(membership(owner))
                .get()
                .extracting(WorkspaceAccountMembership::getRole)
                .isEqualTo(WorkspaceRole.OWNER);
    }

    @Test
    void shouldRecoverAnAbandonedCaptureWithoutTreatingUnfinishedEvidenceAsDeparture() {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        directoryGroups.put("newcomer", Set.of("eligible"));
        activate();
        var newcomer = linkedAccount("Newcomer", "newcomer");
        long connectionId =
                policies.findByWorkspace_Id(workspace.getId()).orElseThrow().getConnectionId();
        var connection = connections
                .findByIdAndWorkspaceId(connectionId, workspace.getId())
                .orElseThrow();
        var abandoned = new de.tum.cit.aet.hephaestus.integration.core.sync.SyncJob(
                workspace,
                connection,
                de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind.KEYCLOAK_DIRECTORY,
                de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobType.RECONCILIATION,
                de.tum.cit.aet.hephaestus.integration.core.sync.SyncJobTrigger.SCHEDULED,
                null);
        abandoned.setStatus(SyncJobStatus.RUNNING);
        abandoned.setStartedAt(Instant.now().minus(Duration.ofHours(1)));
        abandoned.setHeartbeatAt(Instant.now().minus(Duration.ofHours(1)));
        jobs.saveAndFlush(abandoned);
        policyService.prepareCapture(workspace.getId(), connectionId, false);
        assertThat(policies.findByWorkspace_Id(workspace.getId()))
                .get()
                .extracting(DirectoryPolicy::getHealth)
                .isEqualTo(DirectoryPolicy.Health.UNVERIFIED);
        client.get()
                .uri("/user/workspace-access")
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
        assertThat(membership(member)).isPresent();
        directoryGroups.put("member", Set.of());
        reconciliation.scheduled(workspace.getId());
        assertThat(jobs.findById(abandoned.getId()))
                .get()
                .extracting(value -> value.getStatus())
                .isEqualTo(SyncJobStatus.FAILED);
        assertThat(membership(member)).isEmpty();
        assertThat(membership(newcomer)).isPresent();
        assertThat(policies.findByWorkspace_Id(workspace.getId()))
                .get()
                .extracting(DirectoryPolicy::getHealth)
                .isEqualTo(DirectoryPolicy.Health.HEALTHY);
    }

    @Test
    void shouldNotRestoreALinkRevokedWhileDirectoryEvidenceIsBeingRead() throws Exception {
        var member = linkedAccount("Member", "member");
        directoryGroups.put("member", Set.of("eligible"));
        activate();
        IdentityLink target = links.findActiveByAccountId(Objects.requireNonNull(member.getId()))
                .getFirst();
        link(member, identityProviders.resolveProviderId("OIDC", OTHER_ISSUER), "replacement");
        pageReached = new CountDownLatch(1);
        releasePage = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> request(workspace, owner, false));
            try {
                await().atMost(Duration.ofSeconds(10)).until(() -> pageReached.getCount() == 0);
                client.delete()
                        .uri("/user/identities/" + target.getId())
                        .headers(headers -> headers.setBearerAuth(token(member)))
                        .exchange()
                        .expectStatus()
                        .isNoContent();
                assertThat(membership(member)).isEmpty();
                releasePage.countDown();
                awaitJob(first.get(20, TimeUnit.SECONDS), SyncJobStatus.SUCCEEDED);
            } finally {
                releasePage.countDown();
            }
        }
        assertThat(membership(member)).isEmpty();
    }

    @Test
    void shouldScopePolicyErasureAndClearOnlyTheDeletedApproversAttribution() {
        activate();
        var otherOwner = account("Other owner");
        var other = workspace("other-directory", otherOwner);
        configure(other, otherOwner, Set.of("eligible")).expectStatus().isOk();
        run(other, otherOwner, true, SyncJobStatus.SUCCEEDED);
        approve(other, otherOwner).expectStatus().isOk();
        transactions.executeWithoutResult(status -> erasure.eraseAccount(Objects.requireNonNull(owner.getId())));
        assertThat(policies.findByWorkspace_Id(workspace.getId()))
                .get()
                .extracting(DirectoryPolicy::getApprovedByAccountId)
                .isNull();
        assertThat(policies.findByWorkspace_Id(other.getId()))
                .get()
                .extracting(DirectoryPolicy::getApprovedByAccountId)
                .isEqualTo(otherOwner.getId());
        transactions.executeWithoutResult(status -> erasure.deleteWorkspaceData(workspace.getId()));
        assertThat(policies.findByWorkspace_Id(workspace.getId())).isEmpty();
        assertThat(policies.findByWorkspace_Id(other.getId())).isPresent();
    }

    @Test
    void shouldUseCurrentOwnerAuthorityAfterAnExplicitOwnershipTransfer() {
        activate();
        var successor = account("Successor");
        client.post()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/members/assign")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("accountId", successor.getId(), "role", "OWNER"))
                .exchange()
                .expectStatus()
                .isOk();
        client.post()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/members/assign")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("accountId", owner.getId(), "role", "MEMBER"))
                .exchange()
                .expectStatus()
                .isOk();
        configure(workspace, owner, Set.of("alternate")).expectStatus().isForbidden();
        configure(workspace, successor, Set.of("alternate")).expectStatus().isOk();
        run(workspace, successor, true, SyncJobStatus.SUCCEEDED);
        approve(workspace, successor).expectStatus().isOk();
        assertThat(policies.findByWorkspace_Id(workspace.getId()))
                .get()
                .extracting(DirectoryPolicy::getApprovedByAccountId)
                .isEqualTo(successor.getId());
    }

    @Test
    void shouldStopOffersDuringWorkspaceSuspensionAndPurgePolicyBeforeItsConnection() {
        directoryGroups.put("newcomer", Set.of("eligible"));
        activate();
        var newcomer = linkedAccount("Newcomer", "newcomer");
        long connectionId =
                policies.findByWorkspace_Id(workspace.getId()).orElseThrow().getConnectionId();
        client.patch()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/status")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("status", "SUSPENDED"))
                .exchange()
                .expectStatus()
                .isOk();
        client.get()
                .uri("/user/workspace-access")
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .json("[]");
        client.post()
                .uri("/user/workspace-access/" + workspace.getId())
                .headers(headers -> headers.setBearerAuth(token(newcomer)))
                .exchange()
                .expectStatus()
                .isNotFound();
        client.patch()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/status")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("status", "ACTIVE"))
                .exchange()
                .expectStatus()
                .isOk();
        run(workspace, owner, false, SyncJobStatus.SUCCEEDED);
        assertThat(membership(newcomer)).isPresent();
        client.patch()
                .uri("/workspaces/" + workspace.getWorkspaceSlug() + "/status")
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("status", "SUSPENDED"))
                .exchange()
                .expectStatus()
                .isOk();
        client.delete()
                .uri("/workspaces/" + workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isNoContent();
        assertThat(policies.findByWorkspace_Id(workspace.getId())).isEmpty();
        var tombstone = connections
                .findByIdAndWorkspaceId(connectionId, workspace.getId())
                .orElseThrow();
        assertThat(tombstone.getState())
                .isEqualTo(de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState.UNINSTALLED);
        assertThat(tombstone.getCredentialsEncrypted()).isNull();
        assertThat(membership(newcomer)).isEmpty();
    }

    private void activate() {
        configure(workspace, owner, Set.of("eligible")).expectStatus().isOk();
        run(workspace, owner, true, SyncJobStatus.SUCCEEDED);
        approve(workspace, owner).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec configure(Workspace target, Account actor, Set<String> groups) {
        return client.put()
                .uri(path(target, ""))
                .headers(headers -> headers.setBearerAuth(token(actor)))
                .bodyValue(Map.of(
                        "registrationId",
                        "organization",
                        "groupIds",
                        groups,
                        "credentials",
                        Map.of("clientId", "readonly", "clientSecret", "fixture-secret")))
                .exchange();
    }

    private WebTestClient.ResponseSpec approve(Workspace target, Account actor) {
        long version = policies.findByWorkspace_Id(target.getId()).orElseThrow().getConfigurationVersion();
        return client.post()
                .uri(path(target, "/approvals"))
                .headers(headers -> headers.setBearerAuth(token(actor)))
                .bodyValue(Map.of("configurationVersion", version))
                .exchange();
    }

    private WebTestClient.ResponseSpec status(Workspace target, Account actor, String status) {
        return client.patch()
                .uri(path(target, "/status"))
                .headers(headers -> headers.setBearerAuth(token(actor)))
                .bodyValue(Map.of("status", status))
                .exchange();
    }

    private SyncJobDTO request(Workspace target, Account actor, boolean preview) {
        return Objects.requireNonNull(client.mutate()
                .responseTimeout(Duration.ofSeconds(30))
                .build()
                .post()
                .uri(path(target, preview ? "/previews" : "/reconciliations"))
                .headers(headers -> headers.setBearerAuth(token(actor)))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(SyncJobDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private void run(Workspace target, Account actor, boolean preview, SyncJobStatus expected) {
        awaitJob(request(target, actor, preview), expected);
    }

    private void awaitJob(SyncJobDTO job, SyncJobStatus expected) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jobs.findById(job.id()))
                        .get()
                        .extracting(value -> value.getStatus())
                        .isEqualTo(expected));
    }

    private Account account(String name) {
        return accounts.saveAndFlush(new Account(name));
    }

    private Account linkedAccount(String name, String subject) {
        var account = account(name);
        link(account, providerId, subject);
        return account;
    }

    private void link(Account account, long provider, String subject) {
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(provider);
        link.setSubject(subject);
        links.saveAndFlush(link);
    }

    private long source(String registrationId, String issuer) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(LoginProvider.ProviderType.OIDC);
        provider.setBaseUrl(issuer);
        provider.setDisplayName(registrationId);
        provider.setClientId("sign-in-client");
        provider.setClientSecret("sign-in-secret");
        provider.setScopes("openid profile");
        providers.saveAndFlush(provider);
        return identityProviders.resolveProviderId("OIDC", issuer);
    }

    private Workspace workspace(String slug, Account owner) {
        Workspace result = new Workspace();
        result.setWorkspaceSlug(slug);
        result.setDisplayName(slug);
        result.setAccountLogin(slug);
        result.setAccountType(AccountType.ORG);
        result.setIsPubliclyViewable(false);
        result = workspaces.saveAndFlush(result);
        membership(result, owner, WorkspaceRole.OWNER);
        return result;
    }

    private void membership(Workspace target, Account account, WorkspaceRole role) {
        WorkspaceAccountMembership member = new WorkspaceAccountMembership();
        member.setWorkspace(target);
        member.setAccountId(Objects.requireNonNull(account.getId()));
        member.setRole(role);
        memberships.saveAndFlush(member);
    }

    private java.util.Optional<WorkspaceAccountMembership> membership(Account account) {
        return memberships.findByWorkspace_IdAndAccountId(workspace.getId(), Objects.requireNonNull(account.getId()));
    }

    private String token(Account account) {
        return jwt.issue(principals.forAccount(account), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private static String path(Workspace workspace, String suffix) {
        return "/workspaces/" + workspace.getWorkspaceSlug() + "/directory-access" + suffix;
    }

    private ClientHttpResponse json(Object body, HttpStatus status) {
        var response =
                new MockClientHttpResponse(mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8), status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response;
    }
}
