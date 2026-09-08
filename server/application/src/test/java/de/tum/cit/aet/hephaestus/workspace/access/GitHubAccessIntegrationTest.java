package de.tum.cit.aet.hephaestus.workspace.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.core.auth.domain.*;
import de.tum.cit.aet.hephaestus.core.auth.jwt.*;
import de.tum.cit.aet.hephaestus.core.auth.provider.*;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure;
import de.tum.cit.aet.hephaestus.integration.core.connection.*;
import de.tum.cit.aet.hephaestus.integration.core.spi.*;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.ClientCredentials;
import de.tum.cit.aet.hephaestus.integration.core.sync.*;
import de.tum.cit.aet.hephaestus.integration.core.sync.api.SyncJobDTO;
import de.tum.cit.aet.hephaestus.integration.directory.KeycloakDirectoryClient;
import de.tum.cit.aet.hephaestus.testconfig.OrganizationalIdentityIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.*;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.directory.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Real sessions, SQL rows and leased jobs. Provider transport is covered separately by committed HTTP fixtures. */
class GitHubAccessIntegrationTest extends OrganizationalIdentityIntegrationTest {
    private static final String ISSUER = "https://identity.example.com/realms/team";

    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceAccountMembershipRepository workspaceMembers;

    @Autowired
    private LoginProviderRepository providers;

    @Autowired
    private GitProviderRegistry registry;

    @Autowired
    private IdentityLinkRepository links;

    @Autowired
    private HephaestusJwtIssuer jwt;

    @Autowired
    private JwtPrincipalFactory principals;

    @Autowired
    private DirectoryPolicyRepository directories;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private GitHubAccessTargetRepository targets;

    @Autowired
    private GitHubAccessMembershipRepository members;

    @Autowired
    private GitHubAccessActionRepository actions;

    @Autowired
    private SyncJobRepository jobs;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private GitHubAccessLifecycle lifecycle;

    @Autowired
    private DirectoryPolicyService directoryService;

    @Autowired
    private CredentialBundleConverter credentialConverter;

    private Account owner;
    private Account organizationOwner;
    private Account developer;
    private Account administrator;
    private Workspace workspace;
    private DirectoryPolicy directory;
    private final AtomicReference<GitHubAccessClient.State> external =
            new AtomicReference<>(GitHubAccessClient.State.ABSENT);
    private GitHubAccessClient.Session session;

    @BeforeEach
    void fixture() {
        owner = accounts.saveAndFlush(new Account("Workspace owner"));
        organizationOwner = accounts.saveAndFlush(new Account("Organization owner"));
        developer = accounts.saveAndFlush(new Account("Developer"));
        administrator = accounts.saveAndFlush(new Account("Workspace administrator"));
        workspace = new Workspace();
        workspace.setWorkspaceSlug("github-access");
        workspace.setDisplayName("GitHub access workspace");
        workspace.setAccountLogin("github-access");
        workspace.setAccountType(AccountType.ORG);
        workspace.setIsPubliclyViewable(false);
        workspace = workspaces.saveAndFlush(workspace);
        membership(owner, WorkspaceRole.OWNER);
        membership(developer, WorkspaceRole.MEMBER);
        membership(administrator, WorkspaceRole.ADMIN);
        var provider = new LoginProvider();
        provider.setRegistrationId("organization");
        provider.setType(LoginProvider.ProviderType.OIDC);
        provider.setBaseUrl(ISSUER);
        provider.setDisplayName("Organization");
        provider.setClientId("fixture-client");
        provider.setClientSecret("fixture-secret");
        provider.setScopes("openid profile");
        provider.setDirectoryGroupIds(Set.of("eligible"));
        provider = providers.saveAndFlush(provider);
        long directoryProvider = registry.resolveProviderId("OIDC", ISSUER);
        long githubProvider = registry.resolveProviderId("GITHUB", "https://github.com");
        link(developer, directoryProvider, "person");
        link(developer, githubProvider, "61");
        link(organizationOwner, githubProvider, "60");
        var connection = new Connection(
                workspace,
                IntegrationKind.KEYCLOAK_DIRECTORY,
                "organization",
                new ConnectionConfig.KeycloakDirectoryConfig("organization", ISSUER, Set.of()));
        connection.setCredentials(new ClientCredentials("directory-client", "directory-secret"), credentialConverter);
        connection.setState(IntegrationState.ACTIVE);
        connection = connections.saveAndFlush(connection);
        directory = new DirectoryPolicy();
        directory.setWorkspace(workspace);
        directory.setConnectionId(connection.getId());
        directory.setRegistrationId("organization");
        directory.setIssuer(ISSUER);
        directory.setIdentityProviderId(directoryProvider);
        directory.setStatus(DirectoryPolicy.Status.ACTIVE);
        directory.setHealth(DirectoryPolicy.Health.HEALTHY);
        directory.setDraftGroupIds(Set.of("eligible"));
        directory.setApprovedGroupIds(Set.of("eligible"));
        directory.setApprovedByAccountId(owner.getId());
        var now = Instant.now().minusSeconds(1);
        directory.setActiveSnapshot(new DirectorySnapshot(
                now,
                now,
                directory.getConfigurationVersion(),
                provider.getUpdatedAt(),
                Set.of("eligible"),
                Map.of("eligible", "Eligible developers"),
                Map.of("person", Set.of("eligible")),
                Set.of()));
        directory = directories.saveAndFlush(directory);
        session = new GitHubAccessClient.Session(
                500,
                1000,
                "example-org",
                0,
                "example-org",
                Map.of("members", "write"),
                "fixture-token",
                Instant.now().plusSeconds(3600),
                Instant.now(),
                null);
        when(githubAccess.configured()).thenReturn(true);
        when(githubAccess.open(eq(500L), any(), eq(0L))).thenReturn(session);
        when(githubAccess.describe(500, "example-org", null)).thenReturn(session);
        when(githubAccess.inventory(any(), any()))
                .thenAnswer(ignored -> new GitHubAccessClient.Inventory(Instant.now(), List.of(), 0));
        when(githubAccess.inspect(any(), eq(61L))).thenAnswer(ignored -> observed());
        when(githubAccess.grant(any(), eq(61L))).thenAnswer(ignored -> {
            external.set(GitHubAccessClient.State.PENDING);
            return observed();
        });
        when(githubAccess.revoke(any(), eq(61L))).thenAnswer(ignored -> {
            external.set(GitHubAccessClient.State.ABSENT);
            return observed();
        });
    }

    @Test
    void shouldGrantTrackAcceptanceRevokeAndRejoinWhenEligibilityChanges() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isTrue();
        assertThat(member(id).getExternalState()).isEqualTo(GitHubAccessClient.State.PENDING);
        external.set(GitHubAccessClient.State.ACTIVE);
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).getExternalState()).isEqualTo(GitHubAccessClient.State.ACTIVE);
        leave(id);
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
        assertThat(member(id).isRevocationRequested()).isFalse();
        assertThat(external.get()).isEqualTo(GitHubAccessClient.State.ABSENT);
        enroll(id, true);
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isTrue();
        verify(githubAccess, times(2)).grant(any(), eq(61L));
        verify(githubAccess).revoke(any(), eq(61L));
    }

    @Test
    void shouldRequireAdoptionWhenAccessAlreadyExists() {
        external.set(GitHubAccessClient.State.ACTIVE);
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(member(id).isManaged()).isFalse();
        decision(id, "ADOPT", null).expectStatus().isOk();
        assertThat(member(id).isManaged()).isTrue();
        leave(id);
        run(id, false, SyncJobStatus.SUCCEEDED);
        verify(githubAccess, never()).grant(any(), anyLong());
        verify(githubAccess).revoke(any(), eq(61L));
    }

    @Test
    void shouldPreserveUnconfirmedGrantWhenProviderSucceededBeforeCrash() {
        long id = activeTarget();
        when(githubAccess.grant(any(), eq(61L))).thenAnswer(ignored -> {
            external.set(GitHubAccessClient.State.PENDING);
            throw new GitHubAccessFailure(
                    GitHubAccessFailure.Reason.UNAVAILABLE, "Connection lost after the invitation request");
        });
        run(id, false, SyncJobStatus.FAILED);
        assertThat(member(id).isManaged()).isFalse();
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(actions.findTop50ByWorkspace_IdAndTarget_IdOrderByCreatedAtDesc(workspace.getId(), id))
                .singleElement()
                .extracting(GitHubAccessAction::getStatus)
                .isEqualTo(GitHubAccessAction.Status.MANUAL_RECOVERY);
        verify(githubAccess).grant(any(), eq(61L));
        decision(id, "ADOPT", null).expectStatus().isOk();
        assertThat(member(id).isManaged()).isTrue();
    }

    @Test
    void shouldKeepPauseHonestAndCompletePendingRemovalAfterResume() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        pause(id, true);
        leave(id);
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(member(id).isManaged()).isTrue();
        assertThat(member(id).isRevocationRequested()).isTrue();
        verify(githubAccess, never()).revoke(any(), anyLong());
        pause(id, false);
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
    }

    @Test
    void shouldRejectExpiredAndReplayedHandoffsWithoutWorkspaceMembership() {
        var handoff = create(owner);
        authorize(handoff.token()).expectStatus().isNoContent();
        authorize(handoff.token()).expectStatus().isBadRequest();
        assertThat(workspaceMembers.findByWorkspace_IdAndAccountId(
                        workspace.getId(), Objects.requireNonNull(organizationOwner.getId())))
                .isEmpty();
        var expired = create(owner);
        var target = targets.findByIdAndWorkspace_Id(expired.target().id(), workspace.getId())
                .orElseThrow();
        target.setHandoffExpiresAt(Instant.now().minusSeconds(1));
        targets.saveAndFlush(target);
        authorize(expired.token()).expectStatus().isBadRequest();
        verify(githubAccess, times(1)).describe(anyLong(), anyString(), any());
    }

    @Test
    void shouldDenyAdministratorsPolicyExpansionAndOtherAccountsEnrollment() {
        client.post()
                .uri(path(""))
                .headers(headers -> headers.setBearerAuth(token(administrator)))
                .bodyValue(configuration())
                .exchange()
                .expectStatus()
                .isForbidden();
        var handoff = create(owner);
        client.patch()
                .uri("/user/github-access/" + handoff.target().id())
                .headers(headers -> headers.setBearerAuth(token(organizationOwner)))
                .bodyValue(Map.of("enrolled", true))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void shouldHoldExistingAccessWhenDirectoryEvidenceBecomesStale() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        snapshot(Map.of(), Set.of("person"), Instant.now().minus(Duration.ofDays(1)));
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(member(id).isManaged()).isTrue();
        assertThat(member(id).isRevocationRequested()).isFalse();
        verify(githubAccess, never()).revoke(any(), anyLong());
        snapshot(Map.of(), Set.of("person"), Instant.now().minusSeconds(1));
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
        verify(githubAccess).revoke(any(), eq(61L));
    }

    @Test
    void shouldRetainDepartedSubjectsAcrossDirectoryCapturesWhenGitHubIsUnavailable() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        when(githubAccess.open(eq(500L), any(), eq(0L)))
                .thenThrow(new GitHubAccessFailure(GitHubAccessFailure.Reason.UNAVAILABLE, "GitHub unavailable"));
        for (int capture = 0; capture < 2; capture++) {
            var input = directoryService.prepareCapture(workspace.getId(), directory.getConnectionId(), false);
            assertThat(input.previousSubjects()).contains("person");
            var now = Instant.now();
            directoryService.completeCapture(
                    input,
                    new KeycloakDirectoryClient.Capture(
                            now, now, Map.of("eligible", "Eligible developers"), Map.of(), Set.of("person")));
            run(id, false, SyncJobStatus.FAILED);
            assertThat(member(id).isManaged()).isTrue();
            assertThat(member(id).isRevocationRequested()).isTrue();
        }
        when(githubAccess.open(eq(500L), any(), eq(0L))).thenReturn(session);
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
    }

    @Test
    void shouldConfirmRemovalByReadbackWhenDeleteSucceededBeforeTimeout() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        leave(id);
        when(githubAccess.revoke(any(), eq(61L))).thenAnswer(ignored -> {
            external.set(GitHubAccessClient.State.ABSENT);
            throw new GitHubAccessFailure(GitHubAccessFailure.Reason.UNAVAILABLE, "Removal response lost");
        });
        run(id, false, SyncJobStatus.FAILED);
        assertThat(member(id).isManaged()).isTrue();
        assertThat(member(id).isRevocationRequested()).isTrue();
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
        assertThat(member(id).isRevocationRequested()).isFalse();
        verify(githubAccess).revoke(any(), eq(61L));
        assertThat(actions.findTop50ByWorkspace_IdAndTarget_IdOrderByCreatedAtDesc(workspace.getId(), id))
                .filteredOn(action -> action.getType() == GitHubAccessAction.Type.REVOKE)
                .singleElement()
                .extracting(GitHubAccessAction::getStatus)
                .isEqualTo(GitHubAccessAction.Status.CONFIRMED);
    }

    @Test
    void shouldKeepRateLimitedRemovalPendingUntilAConfirmedRetry() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        leave(id);
        when(githubAccess.revoke(any(), eq(61L)))
                .thenThrow(new GitHubAccessFailure(
                        GitHubAccessFailure.Reason.RATE_LIMITED,
                        "Retry later",
                        Instant.now().plusSeconds(60)));
        run(id, false, SyncJobStatus.FAILED);
        assertThat(member(id).isManaged()).isTrue();
        assertThat(member(id).isRevocationRequested()).isTrue();
        assertThat(targets.findByIdAndWorkspace_Id(id, workspace.getId())
                        .orElseThrow()
                        .getFailureCode())
                .isEqualTo(GitHubAccessFailure.Reason.RATE_LIMITED);
        when(githubAccess.revoke(any(), eq(61L))).thenAnswer(ignored -> {
            external.set(GitHubAccessClient.State.ABSENT);
            return observed();
        });
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
    }

    @Test
    void shouldRemoveOnlyTheOriginalGitHubIdentityWhenItsLinkIsReplaced() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        long provider = registry.resolveProviderId("GITHUB", "https://github.com");
        transactions.executeWithoutResult(ignored -> {
            lifecycle.beforeUnlink(Objects.requireNonNull(developer.getId()), provider, "61");
            var original = links.findById(Objects.requireNonNull(member(id).getGithubIdentityLinkId()))
                    .orElseThrow();
            links.delete(original);
            links.flush();
            link(developer, provider, "62");
        });
        when(githubAccess.inspect(any(), eq(62L)))
                .thenReturn(new GitHubAccessClient.Membership(
                        62, "replacement", GitHubAccessClient.State.ABSENT, null, null));
        when(githubAccess.grant(any(), eq(62L)))
                .thenReturn(new GitHubAccessClient.Membership(
                        62, "replacement", GitHubAccessClient.State.PENDING, 701L, null));
        run(id, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(id).isManaged()).isFalse();
        verify(githubAccess).revoke(any(), eq(61L));
        verify(githubAccess, never()).revoke(any(), eq(62L));
    }

    @Test
    void shouldReleaseAuthorityOnlyAfterEndingConfirmsEveryRemoval() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        pause(id, true);
        client.delete()
                .uri(path("/" + id))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isOk();
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(targets.findByIdAndWorkspace_Id(id, workspace.getId())
                        .orElseThrow()
                        .isAuthorityHeld())
                .isTrue();
        assertThat(member(id).isManaged()).isTrue();
        pause(id, false);
        run(id, false, SyncJobStatus.SUCCEEDED);
        var ended = targets.findByIdAndWorkspace_Id(id, workspace.getId()).orElseThrow();
        assertThat(ended.isAuthorityHeld()).isFalse();
        assertThat(ended.getStatus()).isEqualTo(GitHubAccessTarget.Status.ENDED);
        transactions.executeWithoutResult(ignored -> lifecycle.verifyQuiescent(workspace.getId()));
    }

    @Test
    void shouldRejectAnUnconsumedHandoffAfterItsIssuerLosesWorkspaceOwnership() {
        var handoff = create(owner);
        var membership = workspaceMembers
                .findByWorkspace_IdAndAccountId(workspace.getId(), Objects.requireNonNull(owner.getId()))
                .orElseThrow();
        membership.setRole(WorkspaceRole.ADMIN);
        workspaceMembers.saveAndFlush(membership);
        authorize(handoff.token()).expectStatus().isBadRequest();
        assertThat(targets.findByIdAndWorkspace_Id(handoff.target().id(), workspace.getId())
                        .orElseThrow()
                        .isAuthorityHeld())
                .isFalse();
    }

    @Test
    void shouldRejectPreviewApprovalAfterItsDirectoryEvidenceChanges() {
        var handoff = create(owner);
        authorize(handoff.token()).expectStatus().isNoContent();
        long id = handoff.target().id();
        run(id, true, SyncJobStatus.SUCCEEDED);
        var target = targets.findByIdAndWorkspace_Id(id, workspace.getId()).orElseThrow();
        var preview = Objects.requireNonNull(target.getPreview());
        snapshot(Map.of(), Set.of("person"), Instant.now());
        client.post()
                .uri(path("/" + id + "/approvals"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of(
                        "configurationVersion",
                        target.getConfigurationVersion(),
                        "previewCapturedAt",
                        preview.github().capturedAt()))
                .exchange()
                .expectStatus()
                .isBadRequest();
        verify(githubAccess, never()).grant(any(), anyLong());
    }

    @Test
    void shouldNotResumeGrantsAfterAppReauthorizationUntilTheWorkspaceOwnerApprovesANewPreview() {
        long id = activeTarget();
        var renewed = Objects.requireNonNull(client.post()
                .uri(path("/" + id + "/handoffs"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("installationId", 500))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(GitHubAccessController.GitHubAccessHandoffDTO.class)
                .returnResult()
                .getResponseBody());
        authorize(renewed.token()).expectStatus().isNoContent();
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        verify(githubAccess, never()).grant(any(), anyLong());
        assertThat(member(id).isManaged()).isFalse();
        assertThat(targets.findByIdAndWorkspace_Id(id, workspace.getId())
                        .orElseThrow()
                        .getApprovedAt())
                .isNull();
    }

    @Test
    void shouldRejectChangingTheDisplayedScopeWhileItsImmutableAuthorityIsHeld() {
        long id = activeTarget();
        client.put()
                .uri(path("/" + id))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("organization", "other-org", "installationId", 500, "groupIds", List.of("eligible")))
                .exchange()
                .expectStatus()
                .isBadRequest();
        assertThat(targets.findByIdAndWorkspace_Id(id, workspace.getId())
                        .orElseThrow()
                        .getRequestedOrganization())
                .isEqualTo("example-org");
    }

    @Test
    void shouldRestorePendingTeardownWithAReinstalledAppWithoutFreshDirectoryEvidence() {
        long id = activeTarget();
        run(id, false, SyncJobStatus.SUCCEEDED);
        client.delete()
                .uri(path("/" + id))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isOk();
        snapshot(Map.of(), Set.of("person"), Instant.now().minus(Duration.ofDays(1)));
        when(githubAccess.open(eq(500L), any(), eq(0L)))
                .thenThrow(new GitHubAccessFailure(GitHubAccessFailure.Reason.UNINSTALLED, "App uninstalled"));
        run(id, false, SyncJobStatus.FAILED);
        assertThat(member(id).isRevocationRequested()).isTrue();
        var replacement = new GitHubAccessClient.Session(
                501,
                session.organizationId(),
                session.organizationLogin(),
                0,
                session.scopeName(),
                session.permissions(),
                "replacement-token",
                session.expiresAt(),
                Instant.now(),
                null);
        when(githubAccess.open(eq(501L), any(), eq(0L))).thenReturn(replacement);
        var renewed = Objects.requireNonNull(client.post()
                .uri(path("/" + id + "/handoffs"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of("installationId", 501))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(GitHubAccessController.GitHubAccessHandoffDTO.class)
                .returnResult()
                .getResponseBody());
        authorize(renewed.token()).expectStatus().isNoContent();
        run(id, false, SyncJobStatus.SUCCEEDED_WITH_WARNINGS);
        assertThat(member(id).isManaged()).isFalse();
        assertThat(targets.findByIdAndWorkspace_Id(id, workspace.getId())
                        .orElseThrow()
                        .isAuthorityHeld())
                .isFalse();
        verify(githubAccess).revoke(argThat(value -> value.installationId() == 501), eq(61L));
    }

    @Test
    void shouldProgressASeparateOrganizationTeamWhileAnotherTargetIsUnavailable() {
        long first = activeTarget();
        run(first, false, SyncJobStatus.SUCCEEDED);
        var organization = new GitHubAccessClient.Session(
                502,
                1002,
                "other-org",
                0,
                "other-org",
                session.permissions(),
                "other-token",
                session.expiresAt(),
                Instant.now(),
                null);
        var team = new GitHubAccessClient.Session(
                502,
                1002,
                "other-org",
                30,
                "engineering",
                session.permissions(),
                "other-token",
                session.expiresAt(),
                Instant.now(),
                null);
        when(githubAccess.open(eq(502L), any(), eq(0L))).thenReturn(organization);
        when(githubAccess.open(eq(502L), any(), eq(30L))).thenReturn(team);
        when(githubAccess.describe(502, "other-org", "engineering")).thenReturn(team);
        var teamState = new AtomicReference<>(GitHubAccessClient.State.ABSENT);
        when(githubAccess.inspect(argThat(value -> value != null && value.organizationId() == 1002), eq(61L)))
                .thenAnswer(ignored -> new GitHubAccessClient.Membership(61, "developer", teamState.get(), null, null));
        when(githubAccess.grant(argThat(value -> value != null && value.organizationId() == 1002), eq(61L)))
                .thenAnswer(ignored -> {
                    teamState.set(GitHubAccessClient.State.ACTIVE);
                    return new GitHubAccessClient.Membership(61, "developer", teamState.get(), null, null);
                });
        when(githubAccess.revoke(argThat(value -> value != null && value.organizationId() == 1002), eq(61L)))
                .thenAnswer(ignored -> {
                    teamState.set(GitHubAccessClient.State.ABSENT);
                    return new GitHubAccessClient.Membership(61, "developer", teamState.get(), null, null);
                });
        long second = activeTarget(Map.of(
                "organization",
                "other-org",
                "team",
                "engineering",
                "installationId",
                502,
                "groupIds",
                List.of("eligible")));
        run(second, false, SyncJobStatus.SUCCEEDED);
        leave(first);
        leave(second);
        when(githubAccess.open(eq(500L), any(), eq(0L)))
                .thenThrow(new GitHubAccessFailure(GitHubAccessFailure.Reason.UNAVAILABLE, "First target unavailable"));
        run(first, false, SyncJobStatus.FAILED);
        run(second, false, SyncJobStatus.SUCCEEDED);
        assertThat(member(first).isManaged()).isTrue();
        assertThat(member(first).isRevocationRequested()).isTrue();
        assertThat(member(second).isManaged()).isFalse();
        assertThat(teamState.get()).isEqualTo(GitHubAccessClient.State.ABSENT);
        assertThat(external.get()).isEqualTo(GitHubAccessClient.State.PENDING);
    }

    private void snapshot(Map<String, Set<String>> eligible, Set<String> departures, Instant now) {
        var current = directories.findByWorkspace_Id(workspace.getId()).orElseThrow();
        var previous = Objects.requireNonNull(current.getActiveSnapshot());
        current.setActiveSnapshot(new DirectorySnapshot(
                now,
                now,
                current.getConfigurationVersion(),
                previous.sourceVersion(),
                Set.of("eligible"),
                Map.of("eligible", "Eligible developers"),
                eligible,
                departures));
        directories.saveAndFlush(current);
    }

    private long activeTarget() {
        return activeTarget(configuration());
    }

    private long activeTarget(Map<String, Object> configuration) {
        var handoff = create(owner, configuration);
        authorize(handoff.token()).expectStatus().isNoContent();
        long id = handoff.target().id();
        run(id, true, SyncJobStatus.SUCCEEDED);
        var target = targets.findByIdAndWorkspace_Id(id, workspace.getId()).orElseThrow();
        var preview = Objects.requireNonNull(target.getPreview());
        client.post()
                .uri(path("/" + id + "/approvals"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(Map.of(
                        "configurationVersion",
                        target.getConfigurationVersion(),
                        "previewCapturedAt",
                        preview.github().capturedAt()))
                .exchange()
                .expectStatus()
                .isOk();
        return id;
    }

    private GitHubAccessController.GitHubAccessHandoffDTO create(Account actor) {
        return create(actor, configuration());
    }

    private GitHubAccessController.GitHubAccessHandoffDTO create(Account actor, Map<String, Object> configuration) {
        return Objects.requireNonNull(client.post()
                .uri(path(""))
                .headers(headers -> headers.setBearerAuth(token(actor)))
                .bodyValue(configuration)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(GitHubAccessController.GitHubAccessHandoffDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private Map<String, Object> configuration() {
        return Map.of("organization", "example-org", "installationId", 500, "groupIds", List.of("eligible"));
    }

    private WebTestClient.ResponseSpec authorize(String token) {
        return client.post()
                .uri("/user/github-access/approval")
                .headers(headers -> headers.setBearerAuth(token(organizationOwner)))
                .bodyValue(Map.of("token", token))
                .exchange();
    }

    private WebTestClient.ResponseSpec decision(
            long id, String decision, @org.jspecify.annotations.Nullable String reason) {
        return client.put()
                .uri(path("/" + id + "/members/61/decision"))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .bodyValue(
                        reason == null ? Map.of("decision", decision) : Map.of("decision", decision, "reason", reason))
                .exchange();
    }

    private void pause(long id, boolean paused) {
        client.patch()
                .uri(path("/" + id + "/pause"))
                .headers(headers -> headers.setBearerAuth(token(administrator)))
                .bodyValue(Map.of("paused", paused))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void leave(long id) {
        enroll(id, false);
    }

    private void enroll(long id, boolean enrolled) {
        client.patch()
                .uri("/user/github-access/" + id)
                .headers(headers -> headers.setBearerAuth(token(developer)))
                .bodyValue(Map.of("enrolled", enrolled))
                .exchange()
                .expectStatus()
                .isOk();
    }

    private void run(long id, boolean preview, SyncJobStatus expected) {
        var job = Objects.requireNonNull(client.post()
                .uri(path("/" + id + (preview ? "/previews" : "/reconciliations")))
                .headers(headers -> headers.setBearerAuth(token(owner)))
                .exchange()
                .expectStatus()
                .isAccepted()
                .expectBody(SyncJobDTO.class)
                .returnResult()
                .getResponseBody());
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(
                        () -> assertThat(jobs.findById(job.id()).orElseThrow().getStatus())
                                .isEqualTo(expected));
    }

    private GitHubAccessMembership member(long id) {
        return members.findByWorkspace_IdAndTarget_IdAndGithubUserId(workspace.getId(), id, 61)
                .orElseThrow();
    }

    private GitHubAccessClient.Membership observed() {
        return new GitHubAccessClient.Membership(
                61,
                "developer",
                external.get(),
                external.get() == GitHubAccessClient.State.PENDING ? 700L : null,
                null);
    }

    private String token(Account account) {
        return jwt.issue(principals.forAccount(account), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }

    private String path(String suffix) {
        return "/workspaces/" + workspace.getWorkspaceSlug() + "/github-access" + suffix;
    }

    private void link(Account account, long provider, String subject) {
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(provider);
        link.setSubject(subject);
        links.saveAndFlush(link);
    }

    private void membership(Account account, WorkspaceRole role) {
        var member = new WorkspaceAccountMembership();
        member.setWorkspace(workspace);
        member.setAccountId(Objects.requireNonNull(account.getId()));
        member.setRole(role);
        workspaceMembers.saveAndFlush(member);
    }
}
