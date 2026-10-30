package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.AuthSessionService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.IssuedJwtRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspacePasskeyPolicyController;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspacePublicActivityRequestDTO;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@TestPropertySource(properties = "hephaestus.auth.passkeys.instance-admin-required=true")
class PasskeyLifecycleIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private AuthSessionService sessions;

    @Autowired
    private IssuedJwtRepository tokens;

    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private PasskeyChallengeRepository challenges;

    @Autowired
    private PasskeyRecoveryCodeRepository recoveryCodes;

    @Autowired
    private PasskeyCredentialRepository credentials;

    @Value("${hephaestus.auth.cookie-name:__Host-HEPHAESTUS_AT}")
    private String cookie;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void retainMigrationOnlyAuditConstraint() {
        // Hibernate omits this constraint because AuthEvent stores a scalar account ID.
        jdbc.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'sfk_auth_event_account_id') THEN
                        ALTER TABLE auth_event ADD CONSTRAINT sfk_auth_event_account_id
                        FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE SET NULL;
                    END IF;
                END $$
                """);
    }

    @Test
    void shouldEndThePendingRefreshSuccessorWhenStepUpIsFollowedByLogout() throws GeneralSecurityException {
        assertStepUpEndsPendingSuccessor(false);
    }

    @Test
    void shouldEndThePendingRefreshSuccessorWhenTheSteppedUpSessionIsRevoked() throws GeneralSecurityException {
        assertStepUpEndsPendingSuccessor(true);
    }

    private void assertStepUpEndsPendingSuccessor(boolean revoke) throws GeneralSecurityException {
        Account account = account();
        Long accountId = Objects.requireNonNull(account.getId());
        String held = token(account, null);
        String csrf = csrf();
        var authenticator = new PasskeyTestAuthenticator();
        enroll(held, csrf, authenticator, "Laptop");
        var refresh = post("/auth/refresh", held, csrf)
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class);
        String unused = Objects.requireNonNull(refresh.getResponseCookies().getFirst(cookie))
                .getValue();

        String steppedUp = verify(held, csrf, account, authenticator, 1);
        UUID current =
                UUID.fromString(Objects.requireNonNull(decoder.decode(steppedUp).getId()));
        assertThat(sessions.activeSessions(accountId))
                .extracting(AuthSessionService.SessionEntry::jti)
                .containsExactly(current);
        if (revoke) {
            sessions.revokeSession(accountId, current);
        } else {
            sessions.logout(accountId, current, null, new MockHttpServletResponse());
        }

        client.get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookie + "=" + unused)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        assertThat(tokens.findActiveByAccountId(accountId, Instant.now())).isEmpty();
    }

    private long auditCount(Account account, String type, String result, @Nullable String reason) {
        return Objects.requireNonNull(jdbc.queryForObject("""
                SELECT count(*) FROM auth_event
                WHERE account_id = ? AND event_type = ? AND result = ?
                  AND failure_reason IS NOT DISTINCT FROM ?
                """, Long.class, account.getId(), type, result, reason));
    }

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MembershipRepository memberships;

    private record WorkspaceActor(Account account, Workspace workspace) {}

    // Workspace access is account-owned; no provider identity is needed to hold a role.
    private WorkspaceActor workspaceActor(WorkspaceRole role) {
        String slug = "passkey-" + role.name().toLowerCase(Locale.ROOT) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        var account = accounts.saveAndFlush(new Account(slug));
        var workspace = WorkspaceTestFixtures.activeWorkspace(slug);
        workspace.setPublicActivityEnabled(false);
        workspace.setAdminPasskeyRequired(true);
        workspace = workspaces.saveAndFlush(workspace);
        var membership = new Membership();
        membership.setId(new Membership.Id(workspace.getId(), account.getId()));
        membership.setWorkspace(workspace);
        membership.setRole(role);
        memberships.saveAndFlush(membership);
        return new WorkspaceActor(account, workspace);
    }

    private boolean publicActivityEnabled(Workspace workspace) {
        return Boolean.TRUE.equals(
                workspaces.findById(workspace.getId()).orElseThrow().getPublicActivityEnabled());
    }

    private WebTestClient.RequestBodySpec patch(String path, String token, String csrf) {
        return client.patch()
                .uri(path)
                .header(HttpHeaders.COOKIE, cookie + "=" + token + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf);
    }

    @Test
    void shouldRequirePasskeysForWorkspaceAdministrationButNotMemberAccess() {
        var admin = workspaceActor(WorkspaceRole.ADMIN);
        String base = "/workspaces/" + admin.workspace().getWorkspaceSlug();
        String csrf = csrf();
        String oauth = token(admin.account(), null);
        client.get()
                .uri(base + "/members/me")
                .header(HttpHeaders.COOKIE, cookie + "=" + oauth)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.role")
                .isEqualTo("ADMIN");
        patch(base + "/public-activity", oauth, csrf)
                .bodyValue(new UpdateWorkspacePublicActivityRequestDTO(true, false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        assertThat(publicActivityEnabled(admin.workspace())).isFalse();
        patch(base + "/public-activity", token(admin.account(), Instant.now()), csrf)
                .bodyValue(new UpdateWorkspacePublicActivityRequestDTO(true, false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.enabled")
                .isEqualTo(true);
        assertThat(publicActivityEnabled(admin.workspace())).isTrue();
        var member = workspaceActor(WorkspaceRole.MEMBER);
        String memberBase = "/workspaces/" + member.workspace().getWorkspaceSlug();
        client.get()
                .uri(memberBase + "/members/me")
                .header(HttpHeaders.COOKIE, cookie + "=" + token(member.account(), null))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.role")
                .isEqualTo("MEMBER");
        patch(memberBase + "/public-activity", token(member.account(), Instant.now()), csrf)
                .bodyValue(new UpdateWorkspacePublicActivityRequestDTO(true, false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(publicActivityEnabled(member.workspace())).isFalse();
    }

    @Test
    void shouldRequireFreshOwnerProofForBothWorkspacePolicyDirections() {
        var owner = workspaceActor(WorkspaceRole.OWNER);
        String path = "/workspaces/" + owner.workspace().getWorkspaceSlug() + "/passkey-policy";
        String csrf = csrf();
        patch(path, token(owner.account(), null), csrf)
                .bodyValue(new WorkspacePasskeyPolicyController.UpdateWorkspacePasskeyPolicyDTO(false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        patch(path, token(owner.account(), Instant.now()), csrf)
                .bodyValue(new WorkspacePasskeyPolicyController.UpdateWorkspacePasskeyPolicyDTO(false))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.required")
                .isEqualTo(false);
        patch(path, token(owner.account(), null), csrf)
                .bodyValue(new WorkspacePasskeyPolicyController.UpdateWorkspacePasskeyPolicyDTO(true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        patch(path, token(owner.account(), Instant.now()), csrf)
                .bodyValue(new WorkspacePasskeyPolicyController.UpdateWorkspacePasskeyPolicyDTO(true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.required")
                .isEqualTo(true);
        var admin = workspaceActor(WorkspaceRole.ADMIN);
        patch(
                        "/workspaces/" + admin.workspace().getWorkspaceSlug() + "/passkey-policy",
                        token(admin.account(), Instant.now()),
                        csrf)
                .bodyValue(new WorkspacePasskeyPolicyController.UpdateWorkspacePasskeyPolicyDTO(false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldRequireInstanceElevationProofWithoutGrantingWorkspaceOwnership() {
        var member = workspaceActor(WorkspaceRole.MEMBER);
        var admin = account();
        String base = "/workspaces/" + member.workspace().getWorkspaceSlug();
        client.get()
                .uri(base + "/members")
                .header(HttpHeaders.COOKIE, cookie + "=" + token(admin, null))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        client.get()
                .uri(base + "/members")
                .header(HttpHeaders.COOKIE, cookie + "=" + token(admin, Instant.now()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        patch(base + "/passkey-policy", token(admin, Instant.now()), csrf())
                .bodyValue(new WorkspacePasskeyPolicyController.UpdateWorkspacePasskeyPolicyDTO(false))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        String stale = token(admin, Instant.now().minusSeconds(600));
        client.get()
                .uri(base + "/user-view/users/" + member.account().getId())
                .header(HttpHeaders.COOKIE, cookie + "=" + stale)
                .header(UserViewContextHolder.REASON_HEADER, "Support")
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        assertThat(auditCount(admin, "USER_VIEW", "SUCCESS", null)).isZero();
        userView(base + "/members/me", stale, member)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        userView(base + "/members/me", token(admin, Instant.now()), member)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.account.id")
                .isEqualTo(String.valueOf(member.account().getId()));
    }

    private WebTestClient.RequestHeadersSpec<?> userView(String path, String token, WorkspaceActor viewed) {
        return client.get()
                .uri(path)
                .header(HttpHeaders.COOKIE, cookie + "=" + token)
                .header(
                        UserViewContextHolder.WORKSPACE_HEADER,
                        viewed.workspace().getWorkspaceSlug())
                .header(
                        UserViewContextHolder.ACCOUNT_HEADER,
                        String.valueOf(viewed.account().getId()))
                .header(UserViewContextHolder.REASON_HEADER, "Support");
    }

    private Account account() {
        var account = new Account("Passkey administrator");
        account.setAppRole(Account.AppRole.APP_ADMIN);
        return accounts.save(account);
    }

    private String token(Account account, @Nullable Instant proof) {
        return issuer.issue(
                        Objects.requireNonNull(account.getId()),
                        new TokenConstraints(Instant.now().plus(Duration.ofHours(1)), Instant.now(), null, proof),
                        null)
                .value();
    }

    private String csrf() {
        var result = client.get()
                .uri("/identity-providers")
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        return Objects.requireNonNull(result.getResponseCookies().getFirst("__Host-XSRF-TOKEN"))
                .getValue();
    }

    private WebTestClient.RequestBodySpec post(String path, String token, String csrf) {
        return client.post()
                .uri(path)
                .header(HttpHeaders.COOKIE, cookie + "=" + token + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf);
    }

    @Test
    void shouldDenyOAuthOnlyInstanceAdminWhileKeepingEnrollmentAvailable() {
        String token = token(account(), null);
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + token)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        post("/user/passkeys/registration-options", token, csrf())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.optionsJson")
                .isNotEmpty();
    }

    @Test
    void shouldAcceptVerifiedSessionForInstanceAdminRead() {
        String token = token(account(), Instant.now());
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + token)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldConsumeEnrollmentChallengeEvenWhenCredentialIsInvalid() {
        var account = account();
        String token = token(account, null);
        String csrf = csrf();
        var options = post("/user/passkeys/registration-options", token, csrf())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PasskeyService.PasskeyOptionsDTO.class)
                .returnResult()
                .getResponseBody();
        Objects.requireNonNull(options);
        post("/user/passkeys", token, csrf)
                .bodyValue(
                        new PasskeyController.RegisterPasskeyRequestDTO(options.challengeId(), "{}", "Invalid device"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(challenges.findById(options.challengeId())).isEmpty();
        post("/user/passkeys", token, csrf)
                .bodyValue(new PasskeyController.RegisterPasskeyRequestDTO(options.challengeId(), "{}", "Replay"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldBindEnrollmentChallengeToAccountAndSession() {
        var account = account();
        String token = token(account, null);
        String csrf = csrf();
        var options = post("/user/passkeys/registration-options", token, csrf())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PasskeyService.PasskeyOptionsDTO.class)
                .returnResult()
                .getResponseBody();
        Objects.requireNonNull(options);
        post("/user/passkeys", token(account, null), csrf)
                .bodyValue(
                        new PasskeyController.RegisterPasskeyRequestDTO(options.challengeId(), "{}", "Wrong session"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(challenges.findById(options.challengeId())).isPresent();
        post("/user/passkeys", token(account(), null), csrf)
                .bodyValue(
                        new PasskeyController.RegisterPasskeyRequestDTO(options.challengeId(), "{}", "Wrong account"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(challenges.findById(options.challengeId())).isPresent();
    }

    @Test
    void shouldRestrictRecoveryAndConsumeCodesOnce() throws GeneralSecurityException {
        var account = account();
        String initiating = token(account, null);
        String csrf = csrf();
        var original = new PasskeyTestAuthenticator();
        enroll(initiating, csrf, original, "Original");
        String otherSession = token(account, Instant.now());
        assertThat(credentials.findByAccountId(Objects.requireNonNull(account.getId())))
                .hasSize(1);
        recoveryCodes.save(new PasskeyRecoveryCode(PasskeyService.hash("test-code"), account));
        var result = post("/user/passkeys/recovery", initiating, csrf)
                .bodyValue(new PasskeyController.RecoverPasskeyRequestDTO("test-code"))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        String recovered = Objects.requireNonNull(result.getResponseCookies().getFirst(cookie))
                .getValue();
        assertThat(accounts.findById(Objects.requireNonNull(account.getId()))
                        .orElseThrow()
                        .isPasskeyRecoveryRequired())
                .isTrue();
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + recovered)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        post("/user/passkeys/recovery", recovered, csrf)
                .bodyValue(new PasskeyController.RecoverPasskeyRequestDTO("test-code"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(credentials.findByAccountId(Objects.requireNonNull(account.getId())))
                .isEmpty();
        client.get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookie + "=" + otherSession)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
        assertThat(auditCount(account, "PASSKEY_CHANGED", "SUCCESS", null)).isEqualTo(2);
        assertThat(auditCount(account, "PASSKEY_CHANGED", "FAILURE", "invalid_recovery_code"))
                .isEqualTo(1);
        var replacement = new PasskeyTestAuthenticator();
        enroll(recovered, csrf, replacement, "Replacement");
        String userHandle = Objects.requireNonNull(
                accounts.findById(account.getId()).orElseThrow().getPasskeyUserHandle());
        var oldChallenge = verificationOptions(recovered, csrf);
        post("/user/passkeys/verification", recovered, csrf)
                .bodyValue(new PasskeyController.VerifyPasskeyRequestDTO(
                        oldChallenge.challengeId(),
                        original.assertCredential(
                                oldChallenge.optionsJson(), userHandle, true, "http://localhost:4200", 1)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        var challenge = verificationOptions(recovered, csrf);
        var verified = post("/user/passkeys/verification", recovered, csrf)
                .bodyValue(new PasskeyController.VerifyPasskeyRequestDTO(
                        challenge.challengeId(),
                        replacement.assertCredential(
                                challenge.optionsJson(), userHandle, true, "http://localhost:4200", 1)))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        String restored = Objects.requireNonNull(verified.getResponseCookies().getFirst(cookie))
                .getValue();
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + restored)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(accounts.findById(account.getId()).orElseThrow().isPasskeyRecoveryRequired())
                .isFalse();
        assertThat(auditCount(account, "PASSKEY_CHANGED", "SUCCESS", null)).isEqualTo(3);
    }

    private void enroll(String token, String csrf, PasskeyTestAuthenticator authenticator, String label)
            throws GeneralSecurityException {
        var options = Objects.requireNonNull(post("/user/passkeys/registration-options", token, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PasskeyService.PasskeyOptionsDTO.class)
                .returnResult()
                .getResponseBody());
        post("/user/passkeys", token, csrf)
                .bodyValue(new PasskeyController.RegisterPasskeyRequestDTO(
                        options.challengeId(), authenticator.register(options.optionsJson()), label))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    @Test
    void shouldPreservePasskeyTimeAndAbsoluteDeadlineAcrossRefresh() {
        String token = token(account(), Instant.now().minusSeconds(120));
        var before = decoder.decode(token);
        var result = post("/auth/refresh", token, csrf())
                .exchange()
                .expectStatus()
                .isNoContent()
                .returnResult(Void.class);
        String refreshed = Objects.requireNonNull(result.getResponseCookies().getFirst(cookie))
                .getValue();
        var after = decoder.decode(refreshed);
        assertThat(after.<Long>getClaim("passkey_time")).isEqualTo(before.<Long>getClaim("passkey_time"));
        assertThat(after.<Long>getClaim("session_exp")).isEqualTo(before.<Long>getClaim("session_exp"));
        client.get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookie + "=" + token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRejectExpiredEnrollmentChallenge() {
        var account = account();
        String token = token(account, null);
        UUID tokenId =
                UUID.fromString(Objects.requireNonNull(decoder.decode(token).getId()));
        var challenge = challenges.save(new PasskeyChallenge(
                account, tokenId, "REGISTER", "{}", Instant.now().minusSeconds(1)));
        post("/user/passkeys", token, csrf())
                .bodyValue(new PasskeyController.RegisterPasskeyRequestDTO(challenge.getId(), "{}", "Expired"))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldVerifyRealSignatureAndRejectMissingUserVerificationAndUntrustedOrigin() throws GeneralSecurityException {
        var account = account();
        String token = token(account, null);
        String csrf = csrf();
        var authenticator = new PasskeyTestAuthenticator();
        var enrollment = post("/user/passkeys/registration-options", token, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PasskeyService.PasskeyOptionsDTO.class)
                .returnResult()
                .getResponseBody();
        Objects.requireNonNull(enrollment);
        post("/user/passkeys", token, csrf)
                .bodyValue(new PasskeyController.RegisterPasskeyRequestDTO(
                        enrollment.challengeId(),
                        authenticator.register(enrollment.optionsJson()),
                        "Test authenticator"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        String userHandle = Objects.requireNonNull(accounts.findById(Objects.requireNonNull(account.getId()))
                .orElseThrow()
                .getPasskeyUserHandle());
        for (boolean verified : new boolean[] {false, true}) {
            var options = verificationOptions(token, csrf);
            String origin = verified ? "https://attacker.example" : "http://localhost:4200";
            post("/user/passkeys/verification", token, csrf)
                    .bodyValue(new PasskeyController.VerifyPasskeyRequestDTO(
                            options.challengeId(),
                            authenticator.assertCredential(options.optionsJson(), userHandle, verified, origin, 1)))
                    .exchange()
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
            assertThat(challenges.findById(options.challengeId())).isEmpty();
        }
        var forged = verificationOptions(token, csrf);
        post("/user/passkeys/verification", token, csrf)
                .bodyValue(new PasskeyController.VerifyPasskeyRequestDTO(
                        forged.challengeId(),
                        authenticator.assertCredential(
                                forged.optionsJson(), userHandle, true, "http://localhost:4200", 1, false)))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(challenges.findById(forged.challengeId())).isEmpty();
        var options = verificationOptions(token, csrf);
        var result = post("/user/passkeys/verification", token, csrf)
                .bodyValue(new PasskeyController.VerifyPasskeyRequestDTO(
                        options.challengeId(),
                        authenticator.assertCredential(
                                options.optionsJson(), userHandle, true, "http://localhost:4200", 1)))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        String verifiedToken = Objects.requireNonNull(
                        result.getResponseCookies().getFirst(cookie))
                .getValue();
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + verifiedToken)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(decoder.decode(verifiedToken).<Long>getClaim("passkey_time")).isPositive();
        assertThat(auditCount(account, "PASSKEY_CHANGED", "SUCCESS", null)).isEqualTo(1);
        assertThat(auditCount(account, "PASSKEY_VERIFIED", "SUCCESS", null)).isEqualTo(1);
        client.get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookie + "=" + token)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }

    @Test
    void shouldRemoveTheLastPasskeyOnlyOnceProtectionIsOffAndReturnToFirstEnrollment() throws GeneralSecurityException {
        var account = account();
        long accountId = Objects.requireNonNull(account.getId());
        String oauth = token(account, null);
        var signIn = decoder.decode(oauth);
        String csrf = csrf();
        var original = new PasskeyTestAuthenticator();
        enroll(oauth, csrf, original, "Laptop");
        String verified = verify(oauth, csrf, account, original, 1);
        post("/user/passkeys/recovery-codes", verified, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.codes.length()")
                .isEqualTo(10);
        String credentialId = credentials.findByAccountId(accountId).getFirst().getId();

        // Enrollment turned personal protection on: the last passkey stays while it is on.
        delete("/user/passkeys/" + credentialId, verified, csrf)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);
        assertThat(credentials.findByAccountId(accountId)).hasSize(1);
        assertThat(recoveryCodeCount(account)).isEqualTo(10);

        var protectionOff = patch("/user/passkeys/protection", verified, csrf)
                .bodyValue(new PasskeyController.PasskeyProtectionRequestDTO(false))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        String off = Objects.requireNonNull(protectionOff.getResponseCookies().getFirst(cookie))
                .getValue();
        String otherSession = token(account, null);
        var pending = verificationOptions(off, csrf);

        var removal = delete("/user/passkeys/" + credentialId, off, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        String removed = Objects.requireNonNull(removal.getResponseCookies().getFirst(cookie))
                .getValue();

        assertThat(credentials.findByAccountId(accountId)).isEmpty();
        assertThat(recoveryCodeCount(account)).isZero();
        assertThat(challenges.findById(pending.challengeId())).isEmpty();
        var stored = accounts.findById(accountId).orElseThrow();
        assertThat(stored.isPasskeyProtectionEnabled()).isFalse();
        assertThat(stored.isPasskeyRecoveryRequired()).isFalse();
        for (String revoked : new String[] {off, otherSession}) {
            client.get()
                    .uri("/user")
                    .header(HttpHeaders.COOKIE, cookie + "=" + revoked)
                    .exchange()
                    .expectStatus()
                    .isUnauthorized()
                    .expectBody(Void.class);
        }
        // The replacement session keeps the original sign-in and deadline, and no longer holds a passkey factor.
        var replacement = decoder.decode(removed);
        assertThat(replacement.hasClaim("passkey_time")).isFalse();
        assertThat(replacement.<Long>getClaim("auth_time")).isEqualTo(signIn.<Long>getClaim("auth_time"));
        assertThat(replacement.<Long>getClaim("session_exp")).isEqualTo(signIn.<Long>getClaim("session_exp"));
        client.get()
                .uri("/user/passkeys")
                .header(HttpHeaders.COOKIE, cookie + "=" + removed)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.credentials.length()")
                .isEqualTo(0)
                .jsonPath("$.protectionEnabled")
                .isEqualTo(false)
                .jsonPath("$.verified")
                .isEqualTo(false);
        client.get()
                .uri("/user")
                .header(HttpHeaders.COOKIE, cookie + "=" + removed)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        // The instance still requires a passkey for administration, so admin access waits for a new one.
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + removed)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");

        var next = new PasskeyTestAuthenticator();
        enroll(removed, csrf, next, "Phone");
        String restored = verify(removed, csrf, account, next, 1);
        client.get()
                .uri("/admin/users")
                .header(HttpHeaders.COOKIE, cookie + "=" + restored)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(accounts.findById(accountId).orElseThrow().isPasskeyProtectionEnabled())
                .isTrue();
    }

    @Test
    void shouldKeepTheLastPasskeyWithoutFreshOwnProofOrDuringRecovery() throws GeneralSecurityException {
        var account = account();
        long accountId = Objects.requireNonNull(account.getId());
        String csrf = csrf();
        enroll(token(account, null), csrf, new PasskeyTestAuthenticator(), "Laptop");
        var stored = accounts.findById(accountId).orElseThrow();
        stored.setPasskeyProtectionEnabled(false);
        accounts.saveAndFlush(stored);
        String credentialId = credentials.findByAccountId(accountId).getFirst().getId();

        delete("/user/passkeys/" + credentialId, token(account, Instant.now().minusSeconds(600)), csrf)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");

        var other = account();
        enroll(token(other, null), csrf, new PasskeyTestAuthenticator(), "Other laptop");
        String foreignId = credentials
                .findByAccountId(Objects.requireNonNull(other.getId()))
                .getFirst()
                .getId();
        delete("/user/passkeys/" + foreignId, token(account, Instant.now()), csrf)
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        assertThat(credentials.findByAccountId(other.getId())).hasSize(1);

        var recovering = accounts.findById(accountId).orElseThrow();
        recovering.setPasskeyRecoveryRequired(true);
        accounts.saveAndFlush(recovering);
        delete("/user/passkeys/" + credentialId, token(account, Instant.now()), csrf)
                .exchange()
                .expectStatus()
                .isEqualTo(409)
                .expectBody(Void.class);

        assertThat(credentials.findByAccountId(accountId)).hasSize(1);
    }

    @Test
    void shouldKeepRecoveryCodesWhenAnotherPasskeyRemains() throws GeneralSecurityException {
        var account = account();
        long accountId = Objects.requireNonNull(account.getId());
        String oauth = token(account, null);
        String csrf = csrf();
        var laptop = new PasskeyTestAuthenticator();
        enroll(oauth, csrf, laptop, "Laptop");
        String verified = verify(oauth, csrf, account, laptop, 1);
        String laptopId = credentials.findByAccountId(accountId).getFirst().getId();
        enroll(verified, csrf, new PasskeyTestAuthenticator(), "Phone");
        post("/user/passkeys/recovery-codes", verified, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        String phoneId = credentials.findByAccountId(accountId).stream()
                .map(PasskeyCredential::getId)
                .filter(id -> !id.equals(laptopId))
                .findFirst()
                .orElseThrow();

        delete("/user/passkeys/" + phoneId, verified, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);

        assertThat(credentials.findByAccountId(accountId))
                .extracting(PasskeyCredential::getId)
                .containsExactly(laptopId);
        assertThat(recoveryCodeCount(account)).isEqualTo(10);
        assertThat(accounts.findById(accountId).orElseThrow().isPasskeyProtectionEnabled())
                .isTrue();
    }

    private WebTestClient.RequestHeadersSpec<?> delete(String path, String token, String csrf) {
        return client.delete()
                .uri(path)
                .header(HttpHeaders.COOKIE, cookie + "=" + token + "; __Host-XSRF-TOKEN=" + csrf)
                .header("X-XSRF-TOKEN", csrf);
    }

    /** Verifies with {@code authenticator} and returns the session it rotates to. */
    private String verify(
            String token, String csrf, Account account, PasskeyTestAuthenticator authenticator, int counter)
            throws GeneralSecurityException {
        String userHandle = Objects.requireNonNull(accounts.findById(Objects.requireNonNull(account.getId()))
                .orElseThrow()
                .getPasskeyUserHandle());
        var options = verificationOptions(token, csrf);
        var result = post("/user/passkeys/verification", token, csrf)
                .bodyValue(new PasskeyController.VerifyPasskeyRequestDTO(
                        options.challengeId(),
                        authenticator.assertCredential(
                                options.optionsJson(), userHandle, true, "http://localhost:4200", counter)))
                .exchange()
                .expectStatus()
                .isOk()
                .returnResult(Void.class);
        return Objects.requireNonNull(result.getResponseCookies().getFirst(cookie))
                .getValue();
    }

    private long recoveryCodeCount(Account account) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM passkey_recovery_code WHERE account_id = ?", Long.class, account.getId()));
    }

    private PasskeyService.PasskeyOptionsDTO verificationOptions(String token, String csrf) {
        return Objects.requireNonNull(post("/user/passkeys/verification-options", token, csrf)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PasskeyService.PasskeyOptionsDTO.class)
                .returnResult()
                .getResponseBody());
    }
}
