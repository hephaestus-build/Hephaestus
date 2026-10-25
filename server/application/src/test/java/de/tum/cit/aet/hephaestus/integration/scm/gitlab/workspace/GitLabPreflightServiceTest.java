package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.core.connection.identity.GitLabWorkspaceInstance;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryIdentityLookup.RepositoryIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.WorkspaceProviderAvailability;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabGroupDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightResponseDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabProjectDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabProjectPageDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GitLabPreflightServiceTest extends BaseUnitTest {

    private WebClient mockWebClient;
    private ScmServerEndpointPolicy endpoints;
    private GitLabPreflightService preflightService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockWebClient = mock(WebClient.class);
        endpoints = spy(new ScmServerEndpointPolicy(new MockEnvironment(), WebClient.builder()));
        lenient().doReturn(mockWebClient).when(endpoints).clientFor(anyString());
        WorkspaceProviderAvailability defaultInstance = new WorkspaceProviderAvailability() {
            @Override
            public IntegrationKind kind() {
                return IntegrationKind.GITLAB;
            }

            @Override
            public Optional<String> hintUrl() {
                return Optional.of("https://gitlab.lrz.de");
            }
        };
        preflightService = new GitLabPreflightService(endpoints, new GitLabWorkspaceInstance(List.of(defaultInstance)));
    }

    @SuppressWarnings("unchecked")
    private void mockGetRequest(Object response) {
        RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
        RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
        ResponseSpec responseSpec = mock(ResponseSpec.class);

        when(mockWebClient.get()).thenReturn((RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(anyString())).thenReturn((RequestHeadersSpec) headersSpec);
        lenient().when(uriSpec.uri(anyString(), any(Object.class))).thenReturn((RequestHeadersSpec) headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn((RequestHeadersSpec) headersSpec);
        lenient().doReturn(headersSpec).when(headersSpec).attribute(anyString(), any());
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(Class.class))).thenReturn(Mono.justOrEmpty(response));
        lenient().when(responseSpec.bodyToFlux(any(Class.class))).thenReturn(Flux.empty());
    }

    @SuppressWarnings("unchecked")
    private void mockGetRequestThrows(Exception exception) {
        RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
        RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
        ResponseSpec responseSpec = mock(ResponseSpec.class);

        when(mockWebClient.get()).thenReturn((RequestHeadersUriSpec) uriSpec);
        when(uriSpec.uri(anyString())).thenReturn((RequestHeadersSpec) headersSpec);
        lenient().when(uriSpec.uri(anyString(), any(Object.class))).thenReturn((RequestHeadersSpec) headersSpec);
        when(headersSpec.header(anyString(), anyString())).thenReturn((RequestHeadersSpec) headersSpec);
        lenient().doReturn(headersSpec).when(headersSpec).attribute(anyString(), any());
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.bodyToMono(any(Class.class))).thenReturn(Mono.error(exception));
        lenient().when(responseSpec.bodyToFlux(any(Class.class))).thenReturn(Flux.error(exception));
    }

    @Nested
    class ValidateToken {

        @Test
        void returnsValidForPersonalToken() {
            mockGetRequest(new GitLabPreflightService.GitLabUserResponse(42L, "testuser", "Test User", null, null));

            GitLabPreflightResponseDTO result = preflightService.validateToken("glpat-test", null, null);

            assertThat(result.valid()).isTrue();
            assertThat(result.username()).isEqualTo("testuser");
            assertThat(result.userId()).isEqualTo(42L);
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("returns failure for 401 without group fallback")
        void returnsInvalidFor401WithoutGroupPath() {
            mockGetRequestThrows(WebClientResponseException.create(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    HttpHeaders.EMPTY,
                    new byte[0],
                    StandardCharsets.UTF_8));

            GitLabPreflightResponseDTO result = preflightService.validateToken("glpat-bad", null, null);

            assertThat(result.valid()).isFalse();
            assertThat(result.error()).contains("groupFullPath");
        }

        @Test
        void returnsFailureForConnectionError() {
            mockGetRequestThrows(new RuntimeException("Connection refused"));

            GitLabPreflightResponseDTO result = preflightService.validateToken("glpat-test", null, null);

            assertThat(result.valid()).isFalse();
            assertThat(result.error()).contains("could not connect");
        }

        @Test
        void shouldValidateAgainstTheDefaultInstanceWhenItIsSpelledDifferently() {
            mockGetRequest(new GitLabPreflightService.GitLabUserResponse(99L, "lrz-user", "LRZ", null, null));

            GitLabPreflightResponseDTO result =
                    preflightService.validateToken("glpat-test", "HTTPS://GitLab.LRZ.de:443", null);

            assertThat(result.username()).isEqualTo("lrz-user");
            verify(endpoints).clientFor("https://gitlab.lrz.de");
        }

        @Test
        void shouldRefuseBeforeAnyRequestWhenInstanceIsNotTheDefault() {
            for (String serverUrl : List.of("https://gitlab.example.com", "https://gitlab.lrz.de:8443")) {
                assertThatThrownBy(() -> preflightService.validateToken("glpat-test", serverUrl, null))
                        .isInstanceOfSatisfying(
                                ResponseStatusException.class,
                                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
            }
            verify(endpoints, never()).clientFor(anyString());
        }
    }

    @Nested
    class ListAccessibleGroups {

        @Test
        @SuppressWarnings("unchecked")
        void returnsEmptyListWhenNoGroups() {
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            when(mockWebClient.get()).thenReturn((RequestHeadersUriSpec) uriSpec);
            when(uriSpec.uri(anyString())).thenReturn((RequestHeadersSpec) headersSpec);
            when(headersSpec.header(anyString(), anyString())).thenReturn((RequestHeadersSpec) headersSpec);
            lenient().doReturn(headersSpec).when(headersSpec).attribute(anyString(), any());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToFlux(eq(GitLabPreflightService.GitLabGroupListItem.class)))
                    .thenReturn(Flux.empty());

            List<GitLabGroupDTO> groups = preflightService.listAccessibleGroups("glpat-test", null);

            assertThat(groups).isEmpty();
        }

        @Test
        @SuppressWarnings("unchecked")
        void mapsGroupsCorrectly() {
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            when(mockWebClient.get()).thenReturn((RequestHeadersUriSpec) uriSpec);
            when(uriSpec.uri(anyString())).thenReturn((RequestHeadersSpec) headersSpec);
            when(headersSpec.header(anyString(), anyString())).thenReturn((RequestHeadersSpec) headersSpec);
            lenient().doReturn(headersSpec).when(headersSpec).attribute(anyString(), any());
            when(headersSpec.retrieve()).thenReturn(responseSpec);

            var group1 = new GitLabPreflightService.GitLabGroupListItem(
                    1L, "My Group", "my-org/my-group", null, "https://gitlab.com/my-org/my-group", "private");
            var group2 = new GitLabPreflightService.GitLabGroupListItem(
                    2L, "Public Group", "public-group", null, "https://gitlab.com/public-group", "public");

            when(responseSpec.bodyToFlux(eq(GitLabPreflightService.GitLabGroupListItem.class)))
                    .thenReturn(Flux.just(group1, group2));

            List<GitLabGroupDTO> groups = preflightService.listAccessibleGroups("glpat-test", null);

            assertThat(groups).hasSize(2);
            assertThat(groups.get(0).fullPath()).isEqualTo("my-org/my-group");
            assertThat(groups.get(0).name()).isEqualTo("My Group");
            assertThat(groups.get(1).fullPath()).isEqualTo("public-group");
            assertThat(groups.get(1).visibility()).isEqualTo("public");
        }

        @Test
        void shouldReportARefusedTokenWhenGitLabRejectsIt() {
            mockGetRequestThrows(WebClientResponseException.create(
                    HttpStatus.UNAUTHORIZED.value(),
                    "Unauthorized",
                    HttpHeaders.EMPTY,
                    new byte[0],
                    StandardCharsets.UTF_8));

            assertThatThrownBy(() -> preflightService.listAccessibleGroups("glpat-revoked", null))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
        }

        @Test
        void shouldFailInsteadOfReturningNoGroupsWhenGitLabIsUnavailable() {
            mockGetRequestThrows(WebClientResponseException.create(
                    HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Service Unavailable",
                    HttpHeaders.EMPTY,
                    new byte[0],
                    StandardCharsets.UTF_8));

            assertThatThrownBy(() -> preflightService.listAccessibleGroups("glpat-test", null))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        }

        @Test
        void shouldRefuseBeforeAnyRequestWhenInstanceIsNotTheDefault() {
            assertThatThrownBy(() -> preflightService.listAccessibleGroups("glpat-test", "https://gitlab.example.com"))
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT));
            verify(endpoints, never()).clientFor(anyString());
        }
    }

    private static GitLabPreflightService.GitLabProjectItem project(long id, String fullPath) {
        return new GitLabPreflightService.GitLabProjectItem(
                id, fullPath.substring(fullPath.lastIndexOf('/') + 1), fullPath, "https://gitlab.lrz.de/" + fullPath);
    }

    private static WebClientResponseException status(HttpStatus status) {
        return WebClientResponseException.create(
                status.value(), status.getReasonPhrase(), HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
    }

    private static void assertStatus(Throwable thrown, HttpStatus expected) {
        assertThat(thrown)
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(expected));
    }

    @Nested
    class VerifySelectedProjects {

        /** Answers each project read by the id in its path variables; also records the token each read carried. */
        @SuppressWarnings("unchecked")
        private void mockProjects(Map<Long, Mono<GitLabPreflightService.GitLabProjectItem>> byId) {
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            when(mockWebClient.get()).thenReturn((RequestHeadersUriSpec) uriSpec);
            when(uriSpec.uri(anyString(), anyMap())).thenAnswer(invocation -> {
                Map<String, ?> variables = invocation.getArgument(1);
                long id = ((Number) Objects.requireNonNull(variables.get("id"))).longValue();
                RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
                ResponseSpec responseSpec = mock(ResponseSpec.class);
                when(headersSpec.header(HttpHeaders.AUTHORIZATION, "Bearer glpat-test"))
                        .thenReturn((RequestHeadersSpec) headersSpec);
                when(headersSpec.retrieve()).thenReturn(responseSpec);
                when(responseSpec.bodyToMono(eq(GitLabPreflightService.GitLabProjectItem.class)))
                        .thenReturn(byId.getOrDefault(id, Mono.empty()));
                return headersSpec;
            });
        }

        @Test
        void shouldKeepGitLabsIdentityForEachProjectInsideTheGroupAndItsSubgroups() {
            mockProjects(Map.of(
                    11L, Mono.just(project(11L, "My-Group/direct")),
                    12L, Mono.just(project(12L, "my-group/sub/nested"))));

            List<RepositoryIdentity> verified =
                    preflightService.verify("glpat-test", null, "my-group", List.of(12L, 11L));

            assertThat(verified)
                    .containsExactly(
                            new RepositoryIdentity(12L, "my-group/sub/nested"),
                            new RepositoryIdentity(11L, "My-Group/direct"));
            verify(endpoints).clientFor("https://gitlab.lrz.de");
        }

        @Test
        void shouldRefuseAProjectWhoseNamespaceIsOutsideTheGroup() {
            // A project shared into the group keeps its own namespace; a sibling group may share the prefix.
            for (String foreign : List.of("other-group/shared", "my-group-two/lookalike", "my-group")) {
                mockProjects(Map.of(21L, Mono.just(project(21L, foreign))));

                assertStatus(
                        catchThrowable(() -> preflightService.verify("glpat-test", null, "my-group", List.of(21L))),
                        HttpStatus.UNPROCESSABLE_CONTENT);
            }
        }

        @Test
        void shouldRefuseTheWholeSelectionWhenOneProjectCannotBeRead() {
            for (HttpStatus refused : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)) {
                mockProjects(
                        Map.of(31L, Mono.just(project(31L, "my-group/readable")), 32L, Mono.error(status(refused))));

                assertStatus(
                        catchThrowable(
                                () -> preflightService.verify("glpat-test", null, "my-group", List.of(31L, 32L))),
                        HttpStatus.UNPROCESSABLE_CONTENT);
            }
        }

        @Test
        void shouldFailWithoutChoosingWhenGitLabMisreportsOrCannotAnswer() {
            List<Mono<GitLabPreflightService.GitLabProjectItem>> answers = List.of(
                    Mono.just(project(99L, "my-group/another-id")),
                    Mono.just(new GitLabPreflightService.GitLabProjectItem(41L, "no-path", null, null)),
                    Mono.empty(),
                    Mono.error(status(HttpStatus.SERVICE_UNAVAILABLE)),
                    Mono.error(new IllegalStateException("connection reset")));
            for (Mono<GitLabPreflightService.GitLabProjectItem> answer : answers) {
                mockProjects(Map.of(41L, answer));

                assertStatus(
                        catchThrowable(() -> preflightService.verify("glpat-test", null, "my-group", List.of(41L))),
                        HttpStatus.BAD_GATEWAY);
            }
        }

        @Test
        void shouldRefuseBeforeAnyRequestWhenInstanceIsNotTheDefault() {
            assertStatus(
                    catchThrowable(() -> preflightService.verify(
                            "glpat-test", "https://gitlab.example.com", "my-group", List.of(1L))),
                    HttpStatus.UNPROCESSABLE_CONTENT);
            verify(endpoints, never()).clientFor(anyString());
        }
    }

    @Nested
    class ListGroupProjects {

        @SuppressWarnings("unchecked")
        private RequestHeadersUriSpec<?> mockPage(
                Mono<ResponseEntity<List<GitLabPreflightService.GitLabProjectItem>>> page) {
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);
            when(mockWebClient.get()).thenReturn((RequestHeadersUriSpec) uriSpec);
            when(uriSpec.uri(anyString(), anyMap())).thenReturn((RequestHeadersSpec) headersSpec);
            when(headersSpec.header(HttpHeaders.AUTHORIZATION, "Bearer glpat-test"))
                    .thenReturn((RequestHeadersSpec) headersSpec);
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.toEntityList(eq(GitLabPreflightService.GitLabProjectItem.class)))
                    .thenReturn(page);
            return uriSpec;
        }

        private static Mono<ResponseEntity<List<GitLabPreflightService.GitLabProjectItem>>> page(
                String nextPage, GitLabPreflightService.GitLabProjectItem... projects) {
            return Mono.just(ResponseEntity.ok().header("X-Next-Page", nextPage).body(List.of(projects)));
        }

        @Test
        void shouldListSubgroupProjectsWithoutSharedOnesAndSayWhichPageFollows() {
            RequestHeadersUriSpec<?> uriSpec = mockPage(page(
                    "3",
                    project(1L, "my-group/direct"),
                    project(2L, "my-group/sub/nested"),
                    project(3L, "other-group/shared-in")));

            GitLabProjectPageDTO result =
                    preflightService.listGroupProjects("glpat-test", null, " my-group ", 2, "nest");

            assertThat(result.projects())
                    .extracting(GitLabProjectDTO::id, GitLabProjectDTO::fullPath)
                    .containsExactly(tuple(1L, "my-group/direct"), tuple(2L, "my-group/sub/nested"));
            assertThat(result.nextPage()).isEqualTo(3);
            ArgumentCaptor<String> template = ArgumentCaptor.captor();
            ArgumentCaptor<Map<String, Object>> variables = ArgumentCaptor.captor();
            verify(uriSpec).uri(template.capture(), variables.capture());
            assertThat(template.getValue())
                    .startsWith("https://gitlab.lrz.de/api/v4/groups/{group}/projects?")
                    .contains("include_subgroups=true", "with_shared=false", "search={search}")
                    .doesNotContain("glpat-test");
            assertThat(variables.getValue())
                    .containsEntry("group", "my-group")
                    .containsEntry("page", 2)
                    .containsEntry("perPage", 100)
                    .containsEntry("search", "nest")
                    .doesNotContainValue("glpat-test");
        }

        @Test
        void shouldReportTheLastPageWhenGitLabNamesNoNextPage() {
            mockPage(page("", project(1L, "my-group/last")));

            GitLabProjectPageDTO result = preflightService.listGroupProjects("glpat-test", null, "my-group", 4, null);

            assertThat(result.nextPage()).isNull();
            assertThat(result.projects()).extracting(GitLabProjectDTO::fullPath).containsExactly("my-group/last");
        }

        @Test
        void shouldFailInsteadOfCallingAPageCompleteWhenGitLabDoesNotSayWhatFollows() {
            List<Mono<ResponseEntity<List<GitLabPreflightService.GitLabProjectItem>>>> answers = List.of(
                    Mono.just(ResponseEntity.ok().body(List.of(project(1L, "my-group/a")))),
                    page("not-a-page", project(1L, "my-group/a")),
                    page("1", project(1L, "my-group/a")),
                    page("", new GitLabPreflightService.GitLabProjectItem(null, "nameless", null, null)),
                    page("", new GitLabPreflightService.GitLabProjectItem(0L, "zero", "my-group/zero", null)),
                    Mono.error(status(HttpStatus.SERVICE_UNAVAILABLE)),
                    Mono.error(new IllegalStateException("connection reset")));
            for (Mono<ResponseEntity<List<GitLabPreflightService.GitLabProjectItem>>> answer : answers) {
                mockPage(answer);

                assertStatus(
                        catchThrowable(
                                () -> preflightService.listGroupProjects("glpat-test", null, "my-group", 1, null)),
                        HttpStatus.BAD_GATEWAY);
            }
        }

        @Test
        void shouldRefuseWhenGitLabRefusesTheTokenOrTheGroup() {
            for (HttpStatus refused : List.of(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND)) {
                mockPage(Mono.error(status(refused)));

                assertStatus(
                        catchThrowable(
                                () -> preflightService.listGroupProjects("glpat-test", null, "my-group", 1, null)),
                        HttpStatus.UNPROCESSABLE_CONTENT);
            }
        }

        @Test
        void shouldRefuseWithoutAGroupOrOnAnotherInstanceBeforeAnyRequest() {
            assertStatus(
                    catchThrowable(() -> preflightService.listGroupProjects("glpat-test", null, " ", 1, null)),
                    HttpStatus.BAD_REQUEST);
            assertStatus(
                    catchThrowable(() -> preflightService.listGroupProjects(
                            "glpat-test", "https://gitlab.example.com", "my-group", 1, null)),
                    HttpStatus.UNPROCESSABLE_CONTENT);
            verify(endpoints, never()).clientFor(anyString());
        }
    }
}
