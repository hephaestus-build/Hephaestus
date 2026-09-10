package de.tum.cit.aet.hephaestus.integration.directory;

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

import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.ClientCredentials;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncExecutionHandle;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class KeycloakDirectoryClientTest extends BaseUnitTest {
    private static final String ISSUER = "https://identity.example.com/auth/realms/team";
    private static final String ADMIN = "https://identity.example.com/auth/admin/realms/team";
    private final RestTemplate template = new RestTemplate();
    private final MockRestServiceServer server =
            MockRestServiceServer.bindTo(template).build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T10:00:00Z"), ZoneOffset.UTC);
    private final KeycloakDirectoryClient client = new KeycloakDirectoryClient(template.getRequestFactory(), clock);
    private final SyncExecutionHandle handle = mock(SyncExecutionHandle.class);

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void shouldReadUntilAnEmptyPageEvenWhenTheProviderReturnsShortPages() {
        token();
        group();
        members(0, "[{\"id\":\"first\",\"enabled\":true}]");
        members(1, "[{\"id\":\"second\",\"enabled\":true}]");
        members(2, "[]");

        var capture = client.capture(request(Set.of()));

        assertThat(capture.eligibleSubjects()).containsOnlyKeys("first", "second");
        assertThat(capture.eligibleSubjects().get("first")).containsExactly("approved");
        assertThat(capture.confirmedDepartures()).isEmpty();
        var groups = java.util.Objects.requireNonNull(capture.eligibleSubjects().get("first"));
        assertThatThrownBy(groups::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldConfirmAbsenceIndependentlyBeforeRecordingADeparture() {
        token();
        group();
        members(0, "[]");
        get("/users/departed", "{\"id\":\"departed\",\"enabled\":true}");
        get("/users/departed/groups?first=0&max=500&briefRepresentation=false", "[{\"id\":\"unmanaged\"}]");
        get("/users/departed/groups?first=1&max=500&briefRepresentation=false", "[]");

        assertThat(client.capture(request(Set.of("departed"))).confirmedDepartures())
                .containsExactly("departed");
    }

    @Test
    void shouldRecordAConfirmedDeletedUserWithoutGuessingFromAGroupPage() {
        token();
        group();
        members(0, "[]");
        server.expect(requestTo(ADMIN + "/users/deleted"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(client.capture(request(Set.of("deleted"))).confirmedDepartures())
                .containsExactly("deleted");
    }

    @Test
    void shouldRecordDisabledMembersAsDeparturesAndNeverAsEligible() {
        token();
        group();
        members(0, "[{\"id\":\"disabled\",\"enabled\":false}]");
        members(1, "[]");
        var capture = client.capture(request(Set.of("disabled")));
        assertThat(capture.eligibleSubjects()).isEmpty();
        assertThat(capture.confirmedDepartures()).containsExactly("disabled");
    }

    @Test
    void shouldRejectRepeatedPagesInsteadOfPublishingPartialEvidence() {
        token();
        group();
        members(0, "[{\"id\":\"first\",\"enabled\":true}]");
        members(1, "[{\"id\":\"first\",\"enabled\":true}]");
        assertThatThrownBy(() -> client.capture(request(Set.of("missing"))))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class)
                .hasMessageContaining("repeated");
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[null]", "[{\"id\":\"first\"}]", "[{\"enabled\":true}]"})
    void shouldRejectIncompleteMemberResponses(String body) {
        token();
        group();
        members(0, body);
        assertThatThrownBy(() -> client.capture(request(Set.of("missing"))))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class);
    }

    @Test
    void shouldRejectInconsistentGroupAndUserMembershipEvidence() {
        token();
        group();
        members(0, "[]");
        get("/users/still-member", "{\"id\":\"still-member\",\"enabled\":true}");
        get("/users/still-member/groups?first=0&max=500&briefRepresentation=false", "[{\"id\":\"approved\"}]");
        get("/users/still-member/groups?first=1&max=500&briefRepresentation=false", "[]");
        assertThatThrownBy(() -> client.capture(request(Set.of("still-member"))))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class)
                .hasMessageContaining("changed during capture");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 503})
    void shouldWithholdTheWholeCaptureWhenAPageCannotBeRead(int status) {
        token();
        group();
        members(0, "[{\"id\":\"first\",\"enabled\":true}]");
        server.expect(requestTo(ADMIN + "/groups/approved/members?first=1&max=500&briefRepresentation=false"))
                .andRespond(withStatus(HttpStatus.valueOf(status)).body("sensitive provider body"));
        assertThatThrownBy(() -> client.capture(request(Set.of("missing"))))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class)
                .hasMessageContaining("HTTP " + status)
                .hasMessageNotContaining("sensitive")
                .hasNoCause();
    }

    @Test
    void shouldWithholdEvidenceAfterATimeoutWithoutExposingProviderDetails() {
        token();
        group();
        server.expect(requestTo(ADMIN + "/groups/approved/members?first=0&max=500&briefRepresentation=false"))
                .andRespond(withException(new SocketTimeoutException("sensitive request details")));
        assertThatThrownBy(() -> client.capture(request(Set.of("missing"))))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class)
                .hasMessageContaining("completely")
                .hasMessageNotContaining("sensitive")
                .hasNoCause();
    }

    @Test
    void shouldStopBeforeNetworkIoWhenCancellationIsAlreadyRequested() {
        when(handle.isCancellationRequested()).thenReturn(true);
        assertThatThrownBy(() -> client.capture(request(Set.of())))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class)
                .hasMessageContaining("cancelled");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://identity.example.com/realms/team",
                "https://identity.example.com/",
                "https://identity.example.com/realms/team/users",
                "https://identity.example.com/realms/team?source=production",
                "https://operator@identity.example.com/realms/team"
            })
    void shouldRejectAnythingOtherThanOneExactHttpsRealm(String issuer) {
        assertThatThrownBy(() -> KeycloakDirectoryClient.adminRoot(issuer))
                .isInstanceOf(KeycloakDirectoryClient.ReadFailure.class);
    }

    private KeycloakDirectoryClient.Request request(Set<String> previous) {
        return new KeycloakDirectoryClient.Request(
                ISSUER, new ClientCredentials("readonly", "fixture-secret"), Set.of("approved"), previous, handle);
    }

    private void token() {
        server.expect(requestTo(ISSUER + "/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content()
                        .string("grant_type=client_credentials&client_id=readonly&client_secret=fixture-secret"))
                .andRespond(withSuccess("{\"access_token\":\"fixture-token\"}", MediaType.APPLICATION_JSON));
    }

    private void group() {
        get("/groups/approved", "{\"id\":\"approved\",\"name\":\"Approved group\"}");
    }

    private void members(int offset, String body) {
        get("/groups/approved/members?first=" + offset + "&max=500&briefRepresentation=false", body);
    }

    private void get(String path, String body) {
        server.expect(requestTo(ADMIN + path))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer fixture-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }
}
