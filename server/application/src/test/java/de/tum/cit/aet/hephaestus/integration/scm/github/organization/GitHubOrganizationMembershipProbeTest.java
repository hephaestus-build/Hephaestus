package de.tum.cit.aet.hephaestus.integration.scm.github.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipProbe;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubTokenService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitHubOrganizationMembershipProbeTest {
    private final GitHubTokenService tokens = mock(GitHubTokenService.class);
    private final List<String> paths = new ArrayList<>();
    private static final OrganizationMembershipProbe.Target TARGET =
            new OrganizationMembershipProbe.Target(10L, "https://github.com", 20, 30);

    @Test
    void shouldResolveRenamedHandlesAndValidateBothNativeIdsWhenCheckingMembership() {
        var probe = probe(HttpStatus.OK, """
            {"state":"active","user":{"id":30,"login":"new-user"},"organization":{"id":20,"login":"new-org"}}
            """);
        assertThat(probe.check(TARGET)).isEqualTo(OrganizationMembershipProbe.Status.CONFIRMED);
        assertThat(paths).containsExactly("/user/30", "/organizations/20", "/orgs/new-org/memberships/new-user");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"state\":\"active\",\"user\":{\"id\":31},\"organization\":{\"id\":20}}",
                "{\"state\":\"active\",\"user\":{\"id\":30},\"organization\":{\"id\":21}}",
                "{\"state\":\"active\"}",
                "not-json"
            })
    void shouldNotAdmitWhenTheMembershipResponseCannotProveBothIdentities(String body) {
        assertThat(probe(HttpStatus.OK, body).check(TARGET)).isEqualTo(OrganizationMembershipProbe.Status.UNAVAILABLE);
    }

    @Test
    void shouldKeepPendingInvitationsOutOfExistingMemberAdmission() {
        assertThat(probe(HttpStatus.OK, "{\"state\":\"pending\",\"user\":{\"id\":30},\"organization\":{\"id\":20}}")
                        .check(TARGET))
                .isEqualTo(OrganizationMembershipProbe.Status.NOT_CONFIRMED);
    }

    @Test
    void shouldNotCallAHiddenMembershipDefinitelyAbsentWhenTheProviderReturnsNotFound() {
        assertThat(probe(HttpStatus.NOT_FOUND, "{}").check(TARGET))
                .isEqualTo(OrganizationMembershipProbe.Status.NOT_CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 429, 500})
    void shouldReportUnavailableRatherThanNonMembershipWhenTheProviderFails(int status) {
        assertThat(probe(HttpStatus.valueOf(status), "{}").check(TARGET))
                .isEqualTo(OrganizationMembershipProbe.Status.UNAVAILABLE);
    }

    private GitHubOrganizationMembershipProbe probe(HttpStatus membershipStatus, String membershipBody) {
        when(tokens.getAccessToken(10L)).thenReturn("fixture-token");
        var builder = WebClient.builder().exchangeFunction(request -> {
            paths.add(request.url().getPath());
            var path = request.url().getPath();
            String body = path.equals("/user/30")
                    ? "{\"id\":30,\"login\":\"new-user\"}"
                    : path.equals("/organizations/20") ? "{\"id\":20,\"login\":\"new-org\"}" : membershipBody;
            var status = path.startsWith("/orgs/") ? membershipStatus : HttpStatus.OK;
            return Mono.just(ClientResponse.create(status)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        });
        return new GitHubOrganizationMembershipProbe(tokens, builder);
    }
}
