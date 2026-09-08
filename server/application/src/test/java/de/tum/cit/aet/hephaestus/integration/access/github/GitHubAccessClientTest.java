package de.tum.cit.aet.hephaestus.integration.access.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.auth0.jwt.JWT;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient.Session;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessClient.State;
import de.tum.cit.aet.hephaestus.integration.access.github.GitHubAccessFailure.Reason;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.integration.scm.github.GitHubProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.SocketTimeoutException;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.web.client.RestTemplate;

class GitHubAccessClientTest extends BaseUnitTest {
    private static final String API = "https://api.github.com";
    private static final Instant NOW = Instant.parse("2026-09-01T10:00:00Z");
    private static final String PEM = key();
    private static final String USER = "{\"id\":7,\"login\":\"alice\",\"type\":\"User\"}";
    private static final String ORG = "{\"id\":50,\"login\":\"test-org\",\"type\":\"Organization\"}";
    private final RestTemplate template = new RestTemplate();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(template).build();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SilentModeQuery silentMode = mock(SilentModeQuery.class);
    private final GitHubAccessClient client = client(new GitHubAccessProperties(2, PEM, "test-access"));
    private final Session organization = new Session(
            100,
            50,
            "test-org",
            0,
            "test-org",
            Map.of("members", "write"),
            "fixture-token",
            NOW.plusSeconds(3600),
            NOW,
            null);
    private final Session team = new Session(
            100,
            50,
            "test-org",
            75,
            "Developers",
            Map.of("members", "write"),
            "fixture-token",
            NOW.plusSeconds(3600),
            NOW,
            null);

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void shouldRequireASeparateConfiguredApp() {
        assertThat(client.configured()).isTrue();
        assertThat(client.installationUrl()).contains("https://github.com/apps/test-access/installations/new");
        assertThat(client(new GitHubAccessProperties(1, PEM, "normal-app")).configured())
                .isFalse();
        assertThat(client(new GitHubAccessProperties(0, "", "")).installationUrl())
                .isEmpty();
        assertThatThrownBy(() -> client(new GitHubAccessProperties(0, "", "")).open(100, 50L, 0))
                .isInstanceOf(GitHubAccessFailure.class)
                .hasMessageContaining("separate Hephaestus Access App");
        assertThat(new GitHubAccessProperties(2, "secret-key", "test-access").toString())
                .doesNotContain("secret-key");
        assertThat(organization.toString()).doesNotContain("fixture-token");
    }

    @Test
    void shouldVerifyTheInstallationAndMintOnlyMembershipPermissions() {
        installation(installationBody(2, 50, "{\"members\":\"write\",\"metadata\":\"read\"}", "null"));
        expect(HttpMethod.POST, "/app/installations/100/access_tokens")
                .andExpect(content().json("{\"permissions\":{\"members\":\"write\"}}"))
                .andExpect(request -> {
                    String authorization =
                            Objects.requireNonNull(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
                    var jwt = JWT.decode(authorization.substring("Bearer ".length()));
                    assertThat(jwt.getIssuer()).isEqualTo("2");
                    assertThat(jwt.getIssuedAtAsInstant()).isEqualTo(NOW.minusSeconds(60));
                    assertThat(jwt.getExpiresAtAsInstant()).isEqualTo(NOW.plusSeconds(540));
                })
                .andRespond(withSuccess(
                        "{\"token\":\"access-token\",\"expires_at\":\"2026-09-01T11:00:00Z\"}",
                        MediaType.APPLICATION_JSON));
        Session opened = client.open(100, 50L, 0);
        assertThat(opened.organizationId()).isEqualTo(50);
        assertThat(opened.scopeId()).isZero();
        assertThat(opened.permissions()).containsEntry("members", "write");
        assertThat(opened.toString()).doesNotContain("access-token");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"members\":\"read\"}",
                "{\"members\":\"write\",\"contents\":\"read\"}",
                "{\"members\":\"write\",\"administration\":\"write\"}"
            })
    void shouldRefuseMissingOrBroaderInstallationPermissions(String permissions) {
        installation(installationBody(2, 50, permissions, "null"));
        assertReason(() -> client.open(100, 50L, 0), Reason.PERMISSIONS);
    }

    @Test
    void shouldRefuseTheNormalAppEvenIfItsInstallationLooksUsable() {
        installation(installationBody(1, 50, "{\"members\":\"write\"}", "null"));
        assertReason(() -> client.open(100, 50L, 0), Reason.TARGET_CHANGED);
    }

    @Test
    void shouldRefuseAnotherOrganizationRegardlessOfItsLogin() {
        installation(installationBody(2, 51, "{\"members\":\"write\"}", "null"));
        assertReason(() -> client.open(100, 50L, 0), Reason.TARGET_CHANGED);
    }

    @Test
    void shouldExposeSuspensionWithoutAttemptingToMintAToken() {
        installation(installationBody(2, 50, "{\"members\":\"write\"}", "\"2026-09-01T09:00:00Z\""));
        assertReason(() -> client.open(100, 50L, 0), Reason.SUSPENDED);
    }

    @Test
    void shouldKeepAnUninstalledAppAsAManualRecoveryObligation() {
        expect(HttpMethod.GET, "/app/installations/100").andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertReason(() -> client.open(100, 50L, 0), Reason.UNINSTALLED);
    }

    @Test
    void shouldVerifyAnOrganizationOwnerThroughTheirImmutableLinkedIdentity() {
        userById();
        orgMembership("active", "admin");
        client.requireOrganizationOwner(organization, 7);
    }

    @Test
    void shouldNotAcceptAnOrdinaryMemberAsTheOrganizationAuthorizer() {
        userById();
        orgMembership("active", "member");
        assertReason(
                () -> {
                    client.requireOrganizationOwner(organization, 7);
                    return true;
                },
                Reason.AUTHORITY_LOST);
    }

    @Test
    void shouldRefuseUsernameReuseBeforeReadingOrChangingMembership() {
        userById();
        json(HttpMethod.GET, "/users/alice", USER.replace("7", "8"));
        assertReason(() -> client.inspect(organization, 7), Reason.IDENTITY_CHANGED);
    }

    @Test
    void shouldRefuseMembershipDataForADifferentNativeIdentity() {
        userById();
        username();
        json(
                HttpMethod.GET,
                "/orgs/test-org/memberships/alice",
                membership("active", "member").replace("\"id\":7", "\"id\":8"));
        assertReason(() -> client.inspect(organization, 7), Reason.IDENTITY_CHANGED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "billing_manager"})
    void shouldProtectOwnersAndPrivilegedMemberships(String role) {
        userById();
        username();
        orgMembership("active", role);
        assertThat(client.inspect(organization, 7).state()).isEqualTo(State.PROTECTED);
    }

    @Test
    void shouldProtectOutsideCollaboratorsRatherThanConvertingTheirAccess() {
        userById();
        username();
        expect(HttpMethod.GET, "/orgs/test-org/memberships/alice").andRespond(withStatus(HttpStatus.NOT_FOUND));
        page("/orgs/test-org/outside_collaborators", 1, "[" + USER + "]");
        page("/orgs/test-org/outside_collaborators", 2, "[]");
        assertThat(client.inspect(organization, 7).state()).isEqualTo(State.PROTECTED);
    }

    @Test
    void shouldProtectAccessInheritedFromEnterpriseTeams() {
        userById();
        username();
        json(
                HttpMethod.GET,
                "/orgs/test-org/memberships/alice",
                membership("active", "member").replace("\"direct_membership\":true", "\"direct_membership\":false"));
        assertThat(client.inspect(organization, 7).state()).isEqualTo(State.PROTECTED);
    }

    @Test
    void shouldRequireOrganizationMembershipBeforeAddingATeamMember() {
        userById();
        username();
        expect(HttpMethod.GET, "/orgs/test-org/memberships/alice").andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(HttpMethod.GET, "/organizations/50/team/75/memberships/alice")
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.inspect(team, 7).state()).isEqualTo(State.WAITING_ORGANIZATION);
    }

    @Test
    void shouldProtectTeamMaintainers() {
        userById();
        username();
        orgMembership("active", "member");
        json(
                HttpMethod.GET,
                "/organizations/50/team/75/memberships/alice",
                "{\"state\":\"active\",\"role\":\"maintainer\"}");
        assertThat(client.inspect(team, 7).state()).isEqualTo(State.PROTECTED);
    }

    @Test
    void shouldNeverAdoptPreExistingAccessAsAConsequenceOfRetryingAGrant() {
        inspectActiveOrganization();
        assertReason(() -> client.grant(organization, 7), Reason.WRITE_UNCONFIRMED);
    }

    @Test
    void shouldInviteByNativeIdAndConfirmThePendingInvitation() {
        inspectAbsentOrganization();
        userById();
        username();
        expect(HttpMethod.POST, "/orgs/test-org/invitations")
                .andExpect(content().json("{\"invitee_id\":7,\"role\":\"direct_member\",\"team_ids\":[]}"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer fixture-token"))
                .andRespond(withSuccess(invitation(), MediaType.APPLICATION_JSON));
        username();
        inspectPendingOrganization();
        var membership = client.grant(organization, 7);
        assertThat(membership.state()).isEqualTo(State.PENDING);
        assertThat(membership.invitationId()).isEqualTo(90L);
    }

    @Test
    void shouldConfirmOrganizationDepartureOnlyAfterReadBack() {
        inspectActiveOrganization();
        userById();
        username();
        page("/orgs/test-org/teams", 1, "[]");
        expect(HttpMethod.DELETE, "/orgs/test-org/members/alice").andRespond(withStatus(HttpStatus.NO_CONTENT));
        username();
        inspectAbsentOrganization();
        assertThat(client.revoke(organization, 7).state()).isEqualTo(State.ABSENT);
    }

    @Test
    void shouldCancelOnlyTheBoundPendingInvitation() {
        inspectPendingOrganization();
        userById();
        username();
        expect(HttpMethod.DELETE, "/orgs/test-org/invitations/90").andRespond(withStatus(HttpStatus.NO_CONTENT));
        username();
        inspectAbsentOrganization();
        assertThat(client.revoke(organization, 7).state()).isEqualTo(State.ABSENT);
    }

    @Test
    void shouldNotCallARequestedRemovalCompleteWhileGitHubStillReportsAccess() {
        inspectActiveOrganization();
        userById();
        username();
        page("/orgs/test-org/teams", 1, "[]");
        expect(HttpMethod.DELETE, "/orgs/test-org/members/alice").andRespond(withStatus(HttpStatus.NO_CONTENT));
        username();
        inspectActiveOrganization();
        assertReason(() -> client.revoke(organization, 7), Reason.WRITE_UNCONFIRMED);
    }

    @Test
    void shouldNotRepeatAnAlreadyConfirmedRemoval() {
        inspectAbsentOrganization();
        assertThat(client.revoke(organization, 7).state()).isEqualTo(State.ABSENT);
    }

    @Test
    void shouldRespectSilentModeAtTheFinalWriteBoundary() {
        when(silentMode.isSilentModeEngaged()).thenReturn(true);
        inspectAbsentOrganization();
        userById();
        username();
        assertReason(() -> client.grant(organization, 7), Reason.SILENT_MODE);
    }

    @Test
    void shouldHonorCancellationWithoutIssuingAnotherProviderRequest() {
        SyncExecutionHandle handle = mock(SyncExecutionHandle.class);
        when(handle.isCancellationRequested()).thenReturn(true);
        assertReason(() -> client.inspect(organization.withProgress(handle), 7), Reason.CANCELLED);
    }

    @Test
    void shouldKeepTimeoutsUnconfirmedAndDropProviderExceptionBodies() {
        expect(HttpMethod.GET, "/user/7").andRespond(withException(new SocketTimeoutException("credential-secret")));
        assertThatThrownBy(() -> client.inspect(organization, 7))
                .isInstanceOf(GitHubAccessFailure.class)
                .hasNoCause()
                .hasMessageNotContaining("credential-secret");
    }

    @Test
    void shouldRecordRateLimitBackoffWithoutBlockingOtherTargets() {
        expect(HttpMethod.GET, "/user/7")
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .header("X-RateLimit-Remaining", "0")
                        .header("Retry-After", "120")
                        .body("credential-secret"));
        assertThatThrownBy(() -> client.inspect(organization, 7))
                .isInstanceOfSatisfying(GitHubAccessFailure.class, failure -> {
                    assertThat(failure.reason()).isEqualTo(Reason.RATE_LIMITED);
                    assertThat(failure.retryAt()).isEqualTo(NOW.plusSeconds(120));
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.getMessage()).doesNotContain("credential-secret");
                });
    }

    @Test
    void shouldConfirmTeamAbsenceAfterOrganizationDepartureWithoutRepeatingADelete() {
        userById();
        username();
        expect(HttpMethod.GET, "/orgs/test-org/memberships/alice").andRespond(withStatus(HttpStatus.NOT_FOUND));
        expect(HttpMethod.GET, "/organizations/50/team/75/memberships/alice")
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.revoke(team, 7).state()).isEqualTo(State.ABSENT);
    }

    @Test
    void shouldNotInferTeamAbsenceFromAPendingOrganizationInvitation() {
        userById();
        username();
        orgMembership("pending", "member");
        json(
                HttpMethod.GET,
                "/organizations/50/team/75/memberships/alice",
                "{\"state\":\"pending\",\"role\":\"member\"}");
        assertThat(client.inspect(team, 7).state()).isEqualTo(State.PENDING);
    }

    @Test
    void shouldGrantOnlyTheApprovedTeamAndReadBackItsMembership() {
        inspectTeam(null);
        userById();
        username();
        expect(HttpMethod.PUT, "/organizations/50/team/75/memberships/alice")
                .andExpect(content().json("{\"role\":\"member\"}"))
                .andRespond(withSuccess("{\"state\":\"active\",\"role\":\"member\"}", MediaType.APPLICATION_JSON));
        username();
        inspectTeam("active");
        assertThat(client.grant(team, 7).state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void shouldRemoveOnlyTheApprovedTeamWhilePreservingOrganizationMembership() {
        inspectTeam("active");
        userById();
        username();
        expect(HttpMethod.DELETE, "/organizations/50/team/75/memberships/alice")
                .andRespond(withStatus(HttpStatus.NO_CONTENT));
        username();
        inspectTeam(null);
        assertThat(client.revoke(team, 7).state()).isEqualTo(State.ABSENT);
    }

    @Test
    void shouldInventoryAllPagesAndReportEmailOnlyInvitationsWithoutMatchingAccounts() {
        page("/orgs/test-org/members", 1, "[" + USER + "]");
        page("/orgs/test-org/members", 2, "[]");
        username();
        orgMembership("active", "member");
        page(
                "/orgs/test-org/invitations",
                1,
                "[{\"id\":91,\"login\":null,\"role\":\"direct_member\",\"team_count\":0}]");
        page("/orgs/test-org/invitations", 2, "[]");
        var inventory = client.inventory(organization, mock(SyncExecutionHandle.class));
        assertThat(inventory.members()).singleElement().satisfies(member -> {
            assertThat(member.userId()).isEqualTo(7);
            assertThat(member.state()).isEqualTo(State.ACTIVE);
        });
        assertThat(inventory.unlinkedInvitations()).isEqualTo(1);
    }

    @Test
    void shouldRejectAnIncompleteInventoryRatherThanPublishingItsFirstPage() {
        page("/orgs/test-org/members", 1, "[" + USER + "]");
        expect(HttpMethod.GET, "/orgs/test-org/members?per_page=100&page=2")
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertReason(() -> client.inventory(organization, mock(SyncExecutionHandle.class)), Reason.UNAVAILABLE);
    }

    private void inspectTeam(@org.jspecify.annotations.Nullable String state) {
        userById();
        username();
        orgMembership("active", "member");
        if (state == null)
            expect(HttpMethod.GET, "/organizations/50/team/75/memberships/alice")
                    .andRespond(withStatus(HttpStatus.NOT_FOUND));
        else
            json(
                    HttpMethod.GET,
                    "/organizations/50/team/75/memberships/alice",
                    "{\"state\":\"" + state + "\",\"role\":\"member\"}");
    }

    private GitHubAccessClient client(GitHubAccessProperties properties) {
        return new GitHubAccessClient(
                properties,
                new GitHubProperties(new GitHubProperties.App(1, null, null, null), new GitHubProperties.Meta(null)),
                template.getRequestFactory(),
                clock,
                new OutboundEgressGuard(silentMode));
    }

    private ResponseActions expect(HttpMethod method, String path) {
        return server.expect(requestTo(API + path)).andExpect(method(method));
    }

    private void json(HttpMethod method, String path, String body) {
        expect(method, path).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void installation(String body) {
        json(HttpMethod.GET, "/app/installations/100", body);
    }

    private String installationBody(long app, long org, String permissions, String suspended) {
        return """
                {"id":100,"account":{"id":%d,"login":"test-org","type":"Organization"},
                 "app_id":%d,"target_id":%d,"target_type":"Organization","permissions":%s,"suspended_at":%s}
                """.formatted(org, app, org, permissions, suspended);
    }

    private void userById() {
        json(HttpMethod.GET, "/user/7", USER);
    }

    private void username() {
        json(HttpMethod.GET, "/users/alice", USER);
    }

    private String membership(String state, String role) {
        return "{\"state\":\"" + state + "\",\"role\":\"" + role + "\",\"user\":" + USER + ",\"organization\":" + ORG
                + ",\"direct_membership\":true}";
    }

    private void orgMembership(String state, String role) {
        json(HttpMethod.GET, "/orgs/test-org/memberships/alice", membership(state, role));
    }

    private void page(String path, int page, String body) {
        json(HttpMethod.GET, path + "?per_page=100&page=" + page, body);
    }

    private void inspectActiveOrganization() {
        userById();
        username();
        orgMembership("active", "member");
    }

    private void inspectAbsentOrganization() {
        userById();
        username();
        expect(HttpMethod.GET, "/orgs/test-org/memberships/alice").andRespond(withStatus(HttpStatus.NOT_FOUND));
        page("/orgs/test-org/outside_collaborators", 1, "[]");
    }

    private void inspectPendingOrganization() {
        userById();
        username();
        orgMembership("pending", "member");
        page("/orgs/test-org/invitations", 1, "[" + invitation() + "]");
        page("/orgs/test-org/invitations", 2, "[]");
    }

    private String invitation() {
        return "{\"id\":90,\"login\":\"alice\",\"role\":\"direct_member\",\"team_count\":0}";
    }

    private static void assertReason(java.util.function.Supplier<?> action, Reason reason) {
        assertThatThrownBy(action::get)
                .isInstanceOfSatisfying(
                        GitHubAccessFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static String key() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getEncoder()
                            .encodeToString(
                                    generator.generateKeyPair().getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----";
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
