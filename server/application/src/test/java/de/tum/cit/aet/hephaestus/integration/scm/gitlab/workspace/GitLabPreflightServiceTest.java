package de.tum.cit.aet.hephaestus.integration.scm.gitlab.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabGroupDTO;
import de.tum.cit.aet.hephaestus.workspace.dto.GitLabPreflightResponseDTO;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class GitLabPreflightServiceTest extends BaseUnitTest {

    private WebClient mockWebClient;
    private GitLabPreflightService preflightService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockWebClient = mock(WebClient.class);
        GitLabProperties properties = new GitLabProperties(
                "https://gitlab.com",
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofMillis(200),
                Duration.ofMinutes(5));
        var endpoints = spy(new ScmServerEndpointPolicy(new MockEnvironment()));
        lenient().doReturn(mockWebClient).when(endpoints).clientFor(anyString());
        preflightService = new GitLabPreflightService(properties, endpoints);
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
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(any(Class.class))).thenReturn(Mono.error(exception));
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
            assertThat(result.error()).contains("group/project token");
        }

        @Test
        void returnsFailureForConnectionError() {
            mockGetRequestThrows(new RuntimeException("Connection refused"));

            GitLabPreflightResponseDTO result = preflightService.validateToken("glpat-test", null, null);

            assertThat(result.valid()).isFalse();
            assertThat(result.error()).contains("Failed to connect");
        }

        @Test
        void validatesWithCustomServerUrl() {
            // Uses custom server URL — the mock intercepts regardless
            mockGetRequest(new GitLabPreflightService.GitLabUserResponse(99L, "custom-user", "Custom", null, null));

            // Note: actual server URL validation (SSRF) is tested in ServerUrlValidatorTest
            GitLabPreflightResponseDTO result =
                    preflightService.validateToken("glpat-test", "https://gitlab.example.com", null);

            assertThat(result.valid()).isTrue();
            assertThat(result.username()).isEqualTo("custom-user");
        }

        @Test
        void rejectsUnsafeServerUrl() {
            assertThatThrownBy(() -> preflightService.validateToken("glpat-test", "http://evil.com", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
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
        void rejectsUnsafeServerUrl() {
            assertThatThrownBy(() -> preflightService.listAccessibleGroups("glpat-test", "http://evil.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("HTTPS");
        }
    }
}
