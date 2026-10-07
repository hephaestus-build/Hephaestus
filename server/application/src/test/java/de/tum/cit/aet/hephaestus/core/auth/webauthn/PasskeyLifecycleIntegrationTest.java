package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspacePasskeyPolicyController;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.dto.UpdateWorkspacePublicVisibilityRequestDTO;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@TestPropertySource(properties = "hephaestus.auth.passkeys.instance-admin-required=true")
class PasskeyLifecycleIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private JwtDecoder decoder;

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
    private WorkspaceMembershipRepository memberships;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private IdentityLinkRepository identities;

    @Autowired
    private UserRepository users;

    private record WorkspaceActor(Account account, Workspace workspace, Long userId) {}

    private WorkspaceActor workspaceActor(WorkspaceRole role) {
        String slug = "passkey-" + role.name().toLowerCase(Locale.ROOT);
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITLAB, "https://" + slug + ".example"));
        var user = users.saveAndFlush(TestUserFactory.createUser(42L, slug, provider));
        var account = accounts.saveAndFlush(new Account(slug));
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(provider.getId()));
        link.setSubject(user.getNativeId().toString());
        link.setExternalActorId(user.getId());
        identities.saveAndFlush(link);
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        workspace.setIsPubliclyViewable(false);
        workspace.setAdminPasskeyRequired(true);
        workspace = workspaces.saveAndFlush(workspace);
        var membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setUser(user);
        membership.setRole(role);
        memberships.saveAndFlush(membership);
        return new WorkspaceActor(account, workspace, Objects.requireNonNull(user.getId()));
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
        patch(base + "/public-visibility", oauth, csrf)
                .bodyValue(new UpdateWorkspacePublicVisibilityRequestDTO(true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
        assertThat(workspaces.findById(admin.workspace().getId()).orElseThrow().getIsPubliclyViewable())
                .isFalse();
        patch(base + "/public-visibility", token(admin.account(), Instant.now()), csrf)
                .bodyValue(new UpdateWorkspacePublicVisibilityRequestDTO(true))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(workspaces.findById(admin.workspace().getId()).orElseThrow().getIsPubliclyViewable())
                .isTrue();
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
        patch(memberBase + "/public-visibility", token(member.account(), Instant.now()), csrf)
                .bodyValue(new UpdateWorkspacePublicVisibilityRequestDTO(true))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
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
        client.get()
                .uri(base + "/user-view/users/" + member.userId())
                .header(
                        HttpHeaders.COOKIE,
                        cookie + "=" + token(admin, Instant.now().minusSeconds(600)))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody()
                .jsonPath("$.code")
                .isEqualTo("passkey_required");
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
