package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

class GitHubAccessReaderTest extends BaseUnitTest {

    private record Answer(HttpStatus status, String body) {}

    /** GitHub answers in process, by request path, so no request leaves the test. */
    private GitHubAccessReader reader(Map<String, Answer> answers) {
        var tokens = mock(GitHubGraphQlClientProvider.class);
        when(tokens.getToken(7L)).thenReturn("installation-token");
        var properties = mock(GitHubSyncProperties.class);
        when(properties.graphqlTimeout()).thenReturn(Duration.ofSeconds(5));
        var webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    assertThat(request.headers().getFirst(HttpHeaders.AUTHORIZATION))
                            .isEqualTo("Bearer installation-token");
                    Answer answer = answers.getOrDefault(
                            request.url().getPath(), new Answer(HttpStatus.NOT_FOUND, "{\"message\":\"Not Found\"}"));
                    return Mono.just(ClientResponse.create(answer.status())
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(answer.body())
                            .build());
                })
                .build();
        return new GitHubAccessReader(tokens, properties, webClient);
    }

    @Test
    void shouldReportTheRoleOfAnActiveOrganizationMember() {
        var reader = reader(Map.of(
                "/orgs/acme/memberships/ada", new Answer(HttpStatus.OK, "{\"state\":\"active\",\"role\":\"admin\"}")));

        assertThat(reader.organizationRole(7L, "acme", "ada")).contains("admin");
    }

    @Test
    void shouldReportNoMembershipForAnOpenInvitationOrAPersonOutsideTheOrganization() {
        var reader = reader(Map.of(
                "/orgs/acme/memberships/ada",
                new Answer(HttpStatus.OK, "{\"state\":\"pending\",\"role\":\"member\"}")));

        assertThat(reader.organizationRole(7L, "acme", "ada")).isEmpty();
        assertThat(reader.organizationRole(7L, "acme", "grace")).isEmpty();
    }

    @Test
    void shouldReportACollaboratorsRoleOnlyWhenGitHubListsThemAsACollaborator() {
        var reader = reader(Map.of(
                "/repos/acme/widgets/collaborators/ada",
                new Answer(HttpStatus.NO_CONTENT, ""),
                "/repos/acme/widgets/collaborators/ada/permission",
                new Answer(HttpStatus.OK, "{\"permission\":\"write\",\"role_name\":\"maintain\"}"),
                "/repos/acme/widgets/collaborators/grace/permission",
                new Answer(HttpStatus.OK, "{\"permission\":\"read\",\"role_name\":\"read\"}")));

        assertThat(reader.collaboratorRole(7L, "acme", "widgets", "ada")).contains("maintain");
        assertThat(reader.collaboratorRole(7L, "acme", "widgets", "grace")).isEmpty();
    }

    @Test
    void shouldReportTheTeamsRoleOnARepositoryItCanAccess() {
        var reader = reader(Map.of(
                "/orgs/acme/teams/core/repos/acme/widgets",
                new Answer(HttpStatus.OK, "{\"full_name\":\"acme/widgets\",\"role_name\":\"triage\"}")));

        assertThat(reader.teamRepositoryRole(7L, "acme", "core", "acme", "widgets"))
                .contains("triage");
        assertThat(reader.teamRepositoryRole(7L, "acme", "core", "acme", "gadgets"))
                .isEmpty();
    }

    @Test
    void shouldFailWhenGitHubCannotAnswerRatherThanReportNoAccess() {
        var reader = reader(Map.of(
                "/orgs/acme/teams/core/memberships/ada",
                new Answer(HttpStatus.BAD_GATEWAY, "{\"message\":\"Server Error\"}")));

        assertThatThrownBy(() -> reader.teamRole(7L, "acme", "core", "ada"))
                .isInstanceOf(WebClientResponseException.class);
    }
}
