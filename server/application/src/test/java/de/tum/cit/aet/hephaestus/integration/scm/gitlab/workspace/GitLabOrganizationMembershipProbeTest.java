package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.integration.core.spi.OrganizationMembershipProbe;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Tag("unit")
class GitLabOrganizationMembershipProbeTest {
    private final GitLabTokenService tokens = mock(GitLabTokenService.class);
    private static final OrganizationMembershipProbe.Target TARGET =
            new OrganizationMembershipProbe.Target(10L, "https://gitlab.lrz.de", 20, 30);

    @Test
    void shouldFilterActiveMembershipsOnTheExactInstanceAndNativeUser() {
        assertThat(probe("[{\"id\":30,\"state\":\"active\",\"access_level\":30,\"expires_at\":\"2026-10-01\"}]")
                        .check(TARGET))
                .isEqualTo(OrganizationMembershipProbe.Status.CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "[{\"id\":30,\"state\":\"blocked\",\"access_level\":30}]",
                "[{\"id\":30,\"state\":\"active\",\"access_level\":0}]",
                "[{\"id\":30,\"state\":\"active\",\"access_level\":30,\"expires_at\":\"2026-09-09\"}]"
            })
    void shouldNotAdmitWhenMembershipIsNotActive(String body) {
        assertThat(probe(body).check(TARGET)).isEqualTo(OrganizationMembershipProbe.Status.NOT_CONFIRMED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "not-json",
                "[{}]",
                "[{\"id\":31,\"state\":\"active\",\"access_level\":30}]",
                "[{\"id\":30,\"state\":\"active\",\"access_level\":30,\"expires_at\":\"invalid\"}]"
            })
    void shouldReturnUnavailableWhenTheProviderCannotSupplyValidIdentityEvidence(String body) {
        assertThat(probe(body).check(TARGET)).isEqualTo(OrganizationMembershipProbe.Status.UNAVAILABLE);
    }

    @Test
    void shouldRejectADifferentInstanceEvenWhenItsNumericIdsMatch() {
        var probe = probe("[]");
        when(tokens.resolveServerUrl(10L)).thenReturn("https://gitlab.other.example");
        assertThat(probe.check(TARGET)).isEqualTo(OrganizationMembershipProbe.Status.UNAVAILABLE);
        verify(tokens, never()).getAccessToken(any());
    }

    private GitLabOrganizationMembershipProbe probe(String body) {
        when(tokens.resolveServerUrl(10L)).thenReturn("https://gitlab.lrz.de");
        when(tokens.getAccessToken(10L)).thenReturn("fixture-token");
        var builder = WebClient.builder().exchangeFunction(request -> {
            assertThat(request.url().getHost()).isEqualTo("gitlab.lrz.de");
            assertThat(request.url().getPath()).isEqualTo("/api/v4/groups/20/members/all");
            assertThat(request.url().getQuery()).contains("state=active", "user_ids[]=30");
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(body)
                    .build());
        });
        return new GitLabOrganizationMembershipProbe(
                tokens, builder, Clock.fixed(Instant.parse("2026-09-09T12:00:00Z"), ZoneOffset.UTC));
    }
}
