package de.tum.cit.aet.hephaestus.core.auth.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.RepositorySelection;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

// Bearer clients must pass the same recent-sign-in gate as the SPA.
class StepUpGateIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private IssuedJwtRepository issuedJwtRepository;

    @Test
    void shouldAskAStaleSessionToConfirmAccessBeforeItSignsOutEveryOtherSession() {
        Account owner = persistUser("Cautious Cora");
        long ownerId = persistedId(owner.getId());
        tokenFor(owner, Instant.now());
        String stale = tokenFor(owner, staleSignIn());

        webTestClient
                .delete()
                .uri("/user/sessions")
                .headers(h -> h.setBearerAuth(stale))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.STEP_UP_REQUIRED.uri().toString());

        assertThat(issuedJwtRepository.findActiveByAccountId(ownerId, Instant.now()))
                .hasSize(2);
    }

    @Test
    void shouldAskAStaleSessionToConfirmAccessBeforeItEndsOneOtherSession() {
        Account owner = persistUser("Quick Quinn");
        long ownerId = persistedId(owner.getId());
        HephaestusJwtIssuer.Token other = jwtIssuer.issue(ownerId, TokenConstraints.session(null, Instant.now()), null);
        String stale = tokenFor(owner, staleSignIn());

        webTestClient
                .delete()
                .uri("/user/sessions/{jti}", other.jti())
                .headers(h -> h.setBearerAuth(stale))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.STEP_UP_REQUIRED.uri().toString());

        assertThat(issuedJwtRepository.findActiveByAccountId(ownerId, Instant.now()))
                .hasSize(2);
    }

    @Test
    void shouldKeepTheAccountMenuSignOutAvailableWithAStaleSignIn() {
        Account owner = persistUser("Signing Out Sam");
        long ownerId = persistedId(owner.getId());
        String stale = tokenFor(owner, staleSignIn());

        webTestClient
                .post()
                .uri("/auth/logout")
                .headers(h -> h.setBearerAuth(stale))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(issuedJwtRepository.findActiveByAccountId(ownerId, Instant.now()))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldEndOtherSessionsAfterARecentSignIn(boolean allOthers) {
        Account owner = persistUser("Confirmed Cora");
        long ownerId = persistedId(owner.getId());
        HephaestusJwtIssuer.Token other = jwtIssuer.issue(ownerId, TokenConstraints.session(null, Instant.now()), null);
        String fresh = tokenFor(owner, Instant.now());

        webTestClient
                .delete()
                .uri(allOthers ? "/user/sessions" : "/user/sessions/" + other.jti())
                .headers(h -> h.setBearerAuth(fresh))
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(issuedJwtRepository.findActiveByAccountId(ownerId, Instant.now()))
                .hasSize(1);
        assertThat(issuedJwtRepository.findById(other.jti()).orElseThrow().getRevokedAt())
                .isNotNull();
        assertThat(authEventRepository.findByAccountSince(ownerId, Instant.now().minusSeconds(60)))
                .anySatisfy(event -> {
                    assertThat(event.getEventType()).isEqualTo(AuthEvent.EventType.JWT_REVOKED);
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.SUCCESS);
                });
    }

    @Test
    void aStaleAdminIsAskedToConfirmAccessAndTheRoleIsUnchanged() {
        Account admin = persistAdmin("Stale Sam");
        Account victim = persistUser("Unchanged Ursula");
        long victimId = persistedId(victim.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", victimId)
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.STEP_UP_REQUIRED.uri().toString())
                .jsonPath("$.maxAgeSeconds")
                .isEqualTo(authProperties.stepUpMaxAge().toSeconds());

        assertThat(accountRepository.findById(victimId))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.USER);
    }

    @Test
    void aRefusalIsRecordedAgainstTheAdminWhoAttemptedIt() {
        Account admin = persistAdmin("Audited Ada");
        Account victim = persistUser("Untouched Uma");
        long adminId = persistedId(admin.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", persistedId(victim.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        assertThat(authEventRepository.findByAccountSince(adminId, Instant.now().minus(Duration.ofMinutes(5))))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getEventType()).isEqualTo(AuthEvent.EventType.APP_ROLE_CHANGED);
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.FAILURE);
                    assertThat(event.getAccountId()).isEqualTo(adminId);
                    assertThat(event.getFailureReason()).isEqualTo(StepUpRequiredException.CODE);
                });
    }

    /** A session with no {@code auth_time} at all is not a session that signed in a moment ago. */
    @Test
    void aSessionWithoutARecordedSignInIsRefused() {
        Account admin = persistAdmin("Claimless Cleo");
        Account victim = persistUser("Safe Sofia");

        webTestClient
                .patch()
                .uri("/admin/users/{id}", persistedId(victim.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(admin, null)))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.STEP_UP_REQUIRED.uri().toString());
    }

    @Test
    void aFreshAdminChangesTheRole() {
        Account admin = persistAdmin("Fresh Fran");
        Account victim = persistUser("Promoted Pia");
        long victimId = persistedId(victim.getId());

        webTestClient
                .patch()
                .uri("/admin/users/{id}", victimId)
                .headers(h -> h.setBearerAuth(tokenFor(admin, Instant.now())))
                .bodyValue(Map.of("appRole", "APP_ADMIN"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(accountRepository.findById(victimId))
                .get()
                .extracting(Account::getAppRole)
                .isEqualTo(Account.AppRole.APP_ADMIN);
    }

    /** An admin surface with a recorded exemption stays reachable on the same stale session. */
    @Test
    void anExemptAdminReadIsUnaffectedByAStaleSignIn() {
        Account admin = persistAdmin("Reading Rita");

        webTestClient
                .get()
                .uri("/admin/users")
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldRecordOnlyARefusalWhenAUserViewRequiresRecentSignIn() {
        Account admin = persistAdmin("Viewing Vera");
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug("stale-user-view");
        workspace.setDisplayName("User view step-up");
        workspace.setAccountLogin("support");
        workspace.setAccountType(AccountType.USER);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        workspaceRepository.save(workspace);

        webTestClient
                .get()
                .uri("/workspaces/stale-user-view/practices/standings")
                .headers(h -> h.setBearerAuth(tokenFor(admin, staleSignIn())))
                .header("X-User-View-Workspace", "stale-user-view")
                .header("X-User-View-Account", "99")
                .header("X-User-View-Reason", "Support")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.STEP_UP_REQUIRED.uri().toString());

        assertThat(authEventRepository.findByAccountSince(
                        persistedId(admin.getId()), Instant.now().minusSeconds(60)))
                .filteredOn(event -> event.getEventType() == AuthEvent.EventType.USER_VIEW)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.FAILURE);
                    assertThat(event.getViewedActorId()).isNull();
                    assertThat(event.getFailureReason()).isEqualTo(StepUpRequiredException.CODE);
                });
    }

    @Test
    void aStaleOwnerIsAskedToConfirmAccessBeforeGrantingOwnerAndNothingChanges() {
        Workspace workspace = workspace("stale-owner-grant");
        Account owner = persistUser("Stale Owner Olga");
        Account member = persistUser("Member Mia");
        join(workspace, owner, Membership.WorkspaceRole.OWNER);
        join(workspace, member, Membership.WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/stale-owner-grant/members/assign")
                .headers(h -> h.setBearerAuth(tokenFor(owner, staleSignIn())))
                .bodyValue(Map.of("accountId", String.valueOf(member.getId()), "role", "OWNER"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.type")
                .isEqualTo(ProblemType.STEP_UP_REQUIRED.uri().toString());

        assertThat(role(workspace, member)).isEqualTo(Membership.WorkspaceRole.MEMBER);
        assertThat(authEventRepository.findByAccountSince(
                        persistedId(owner.getId()), Instant.now().minusSeconds(60)))
                .filteredOn(event -> event.getEventType() == AuthEvent.EventType.WORKSPACE_OWNER_CHANGE)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getResult()).isEqualTo(AuthEvent.Result.FAILURE);
                    assertThat(event.getFailureReason()).isEqualTo(StepUpRequiredException.CODE);
                });
    }

    @Test
    void aStaleOwnerCanNeitherDemoteNorRemoveAnotherOwner() {
        Workspace workspace = workspace("stale-owner-demote");
        Account owner = persistUser("Stale Owner Otto");
        Account other = persistUser("Other Owner Oona");
        join(workspace, owner, Membership.WorkspaceRole.OWNER);
        join(workspace, other, Membership.WorkspaceRole.OWNER);

        webTestClient
                .post()
                .uri("/workspaces/stale-owner-demote/members/assign")
                .headers(h -> h.setBearerAuth(tokenFor(owner, staleSignIn())))
                .bodyValue(Map.of("accountId", String.valueOf(other.getId()), "role", "ADMIN"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        webTestClient
                .delete()
                .uri("/workspaces/stale-owner-demote/members/{id}", persistedId(other.getId()))
                .headers(h -> h.setBearerAuth(tokenFor(owner, staleSignIn())))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);

        Membership kept = membershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), persistedId(other.getId()))
                .orElseThrow();
        assertThat(kept.getRole()).isEqualTo(Membership.WorkspaceRole.OWNER);
        assertThat(kept.getLeftAt()).isNull();
    }

    /** An ordinary role change, and an owner lowering their own role, stay one step on the same stale session. */
    @Test
    void aStaleOwnerStillMakesAnAdminAndLowersTheirOwnRole() {
        Workspace workspace = workspace("stale-owner-ordinary");
        Account owner = persistUser("Stale Owner Oskar");
        Account other = persistUser("Second Owner Odile");
        Account member = persistUser("Member Mo");
        join(workspace, owner, Membership.WorkspaceRole.OWNER);
        join(workspace, other, Membership.WorkspaceRole.OWNER);
        join(workspace, member, Membership.WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/stale-owner-ordinary/members/assign")
                .headers(h -> h.setBearerAuth(tokenFor(owner, staleSignIn())))
                .bodyValue(Map.of("accountId", String.valueOf(member.getId()), "role", "ADMIN"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        webTestClient
                .post()
                .uri("/workspaces/stale-owner-ordinary/members/assign")
                .headers(h -> h.setBearerAuth(tokenFor(owner, staleSignIn())))
                .bodyValue(Map.of("accountId", String.valueOf(owner.getId()), "role", "ADMIN"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(role(workspace, member)).isEqualTo(Membership.WorkspaceRole.ADMIN);
        assertThat(role(workspace, owner)).isEqualTo(Membership.WorkspaceRole.ADMIN);
    }

    @Test
    void aFreshOwnerGrantsOwner() {
        Workspace workspace = workspace("fresh-owner-grant");
        Account owner = persistUser("Fresh Owner Flora");
        Account member = persistUser("Member Max");
        join(workspace, owner, Membership.WorkspaceRole.OWNER);
        join(workspace, member, Membership.WorkspaceRole.MEMBER);

        webTestClient
                .post()
                .uri("/workspaces/fresh-owner-grant/members/assign")
                .headers(h -> h.setBearerAuth(tokenFor(owner, Instant.now())))
                .bodyValue(Map.of("accountId", String.valueOf(member.getId()), "role", "OWNER"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(role(workspace, member)).isEqualTo(Membership.WorkspaceRole.OWNER);
    }

    private Workspace workspace(String slug) {
        Workspace workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        workspace.setRepositorySelection(RepositorySelection.ALL);
        return workspaceRepository.save(workspace);
    }

    private void join(Workspace workspace, Account account, Membership.WorkspaceRole role) {
        Membership membership = new Membership();
        membership.setWorkspace(workspace);
        membership.setId(new Membership.Id(workspace.getId(), persistedId(account.getId())));
        membership.setRole(role);
        membershipRepository.save(membership);
    }

    private Membership.WorkspaceRole role(Workspace workspace, Account account) {
        return membershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), persistedId(account.getId()))
                .orElseThrow()
                .getRole();
    }

    private Instant staleSignIn() {
        return Instant.now().minus(authProperties.stepUpMaxAge()).minus(Duration.ofMinutes(1));
    }

    private String tokenFor(Account account, @Nullable Instant authTime) {
        return jwtIssuer
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, authTime), null)
                .value();
    }

    private Account persistAdmin(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.APP_ADMIN);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private Account persistUser(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.USER);
        account.setStatus(Account.Status.ACTIVE);
        return accountRepository.save(account);
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
