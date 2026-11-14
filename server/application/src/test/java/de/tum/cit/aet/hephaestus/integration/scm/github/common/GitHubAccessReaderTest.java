package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncTargetProvider;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

class GitHubAccessReaderTest extends BaseUnitTest {

    private static final long ADA = 11L;
    private static final long GRACE = 12L;
    private static final String REPOSITORY_MEDIA_TYPE = "application/vnd.github.v3.repository+json";

    /** GitHub's answer to one path; {@code accept} limits it to requests that send that media type. */
    private record Answer(
            HttpStatus status, String body, @Nullable String accept) {
        Answer(HttpStatus status, String body) {
            this(status, body, null);
        }
    }

    /** GitHub answers in process, by request path, so no request leaves the test. Ada and Grace exist. */
    private GitHubAccessReader reader(Map<String, Answer> answers) {
        var known = new HashMap<>(answers);
        known.putIfAbsent("/user/" + ADA, new Answer(HttpStatus.OK, "{\"id\":11,\"login\":\"ada\"}"));
        known.putIfAbsent("/user/" + GRACE, new Answer(HttpStatus.OK, "{\"id\":12,\"login\":\"grace\"}"));
        var tokens = mock(GitHubGraphQlClientProvider.class);
        when(tokens.getToken(7L)).thenReturn("installation-token");
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(5));
        var webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION))
                            .isEqualTo("Bearer installation-token");
                    Answer answer = known.get(request.url().getPath());
                    if (answer == null
                            || (answer.accept() != null
                                    && !answer.accept().equals(request.headers().getFirst(HttpHeaders.ACCEPT)))) {
                        answer = new Answer(HttpStatus.NOT_FOUND, "{\"message\":\"Not Found\"}");
                    }
                    // A 204 carries no body and no content type, as GitHub sends it.
                    return Mono.just(
                            answer.body().isEmpty()
                                    ? ClientResponse.create(answer.status()).build()
                                    : ClientResponse.create(answer.status())
                                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                                            .body(answer.body())
                                            .build());
                })
                .build();
        return new GitHubAccessReader(tokens, properties, mock(SyncTargetProvider.class), webClient);
    }

    @Test
    void shouldReportTheRoleOfAnActiveOrganizationMemberFoundByTheirId() {
        var reader = reader(Map.of(
                "/orgs/acme/memberships/ada", new Answer(HttpStatus.OK, "{\"state\":\"active\",\"role\":\"admin\"}")));

        assertThat(reader.organizationRole(7L, "acme", ADA)).contains("admin");
    }

    @Test
    void shouldReadThePersonUnderTheirCurrentLoginAfterARename() {
        var reader = reader(Map.of(
                "/user/" + ADA,
                new Answer(HttpStatus.OK, "{\"id\":11,\"login\":\"ada-renamed\"}"),
                "/orgs/acme/memberships/ada",
                new Answer(HttpStatus.OK, "{\"state\":\"active\",\"role\":\"member\"}")));

        assertThat(reader.organizationRole(7L, "acme", ADA)).isEmpty();
    }

    @Test
    void shouldReportNoMembershipForAnInvitationABillingManagerOrSomeoneOutside() {
        var reader = reader(Map.of(
                "/orgs/acme/memberships/ada",
                new Answer(HttpStatus.OK, "{\"state\":\"pending\",\"role\":\"member\"}"),
                "/orgs/acme/memberships/grace",
                new Answer(HttpStatus.OK, "{\"state\":\"active\",\"role\":\"billing_manager\"}")));

        assertThat(reader.organizationRole(7L, "acme", ADA)).isEmpty();
        assertThat(reader.organizationRole(7L, "acme", GRACE)).isEmpty();
        assertThat(reader.organizationRole(7L, "other", ADA)).isEmpty();
    }

    @Test
    void shouldReportNoAccessForAnAccountThatNoLongerExists() {
        var reader = reader(Map.of("/user/" + ADA, new Answer(HttpStatus.NOT_FOUND, "{\"message\":\"Not Found\"}")));

        assertThat(reader.teamRole(7L, "acme", "core", ADA)).isEmpty();
    }

    @Test
    void shouldReportACollaboratorsBaseRoleOnlyWhenGitHubListsThemAsACollaborator() {
        var reader = reader(Map.of(
                "/repos/acme/widgets/collaborators/ada",
                new Answer(HttpStatus.NO_CONTENT, ""),
                "/repos/acme/widgets/collaborators/ada/permission",
                new Answer(
                        HttpStatus.OK,
                        "{\"role_name\":\"release-manager\",\"user\":{\"permissions\":"
                                + "{\"pull\":true,\"triage\":true,\"push\":true,\"maintain\":false}}}"),
                "/repos/acme/widgets/collaborators/grace/permission",
                new Answer(HttpStatus.OK, "{\"permission\":\"read\",\"role_name\":\"read\"}")));

        assertThat(reader.collaboratorRole(7L, "acme", "widgets", ADA)).contains("write");
        assertThat(reader.collaboratorRole(7L, "acme", "widgets", GRACE)).isEmpty();
    }

    @Test
    void shouldReportTheTeamsRoleOnARepositoryItCanAccess() {
        var reader = reader(Map.of(
                "/orgs/acme/teams/core/repos/acme/widgets",
                new Answer(HttpStatus.OK, "{\"role_name\":\"triage\"}", REPOSITORY_MEDIA_TYPE),
                "/orgs/acme/teams/core/repos/acme/tools",
                new Answer(
                        HttpStatus.OK,
                        "{\"role_name\":\"deployer\",\"permissions\":{\"pull\":true,\"push\":true,\"admin\":false}}",
                        REPOSITORY_MEDIA_TYPE)));

        assertThat(reader.teamRepositoryRole(7L, "acme", "core", "acme", "widgets"))
                .contains("triage");
        assertThat(reader.teamRepositoryRole(7L, "acme", "core", "acme", "tools"))
                .contains("write");
        assertThat(reader.teamRepositoryRole(7L, "acme", "core", "acme", "gadgets"))
                .isEmpty();
    }

    @Test
    void shouldFailRatherThanReportNoAccessWhenTheTeamCheckComesBackWithoutTheRepository() {
        var reader = reader(Map.of("/orgs/acme/teams/core/repos/acme/widgets", new Answer(HttpStatus.NO_CONTENT, "")));

        assertThatThrownBy(() -> reader.teamRepositoryRole(7L, "acme", "core", "acme", "widgets"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldFailWhenGitHubCannotAnswerRatherThanReportNoAccess() {
        var reader = reader(Map.of(
                "/orgs/acme/teams/core/memberships/ada",
                new Answer(HttpStatus.BAD_GATEWAY, "{\"message\":\"Server Error\"}")));

        assertThatThrownBy(() -> reader.teamRole(7L, "acme", "core", ADA))
                .isInstanceOf(WebClientResponseException.class);
    }
}
