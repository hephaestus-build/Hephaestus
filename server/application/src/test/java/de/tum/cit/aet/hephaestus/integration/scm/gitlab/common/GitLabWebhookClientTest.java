package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.GroupInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookConfig;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookClient.WebhookInfo;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.graphql.GitLabGroupResponse;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.graphql.client.GraphQlClient;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitLabWebhookClient}.
 */
@Tag("unit")
class GitLabWebhookClientTest extends BaseUnitTest {

    @Mock
    private GitLabGraphQlClientProvider graphQlClientProvider;

    @Mock
    private GitLabTokenService tokenService;

    private WebClient mockWebClient;
    private GitLabWebhookClient webhookClient;

    private static final Long SCOPE_ID = 1L;
    private static final long GROUP_ID = 42L;
    private static final String SERVER_URL = "https://gitlab.com";
    private static final String TOKEN = "glpat-test-token";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mockWebClient = mock(WebClient.class);
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(mockWebClient);

        webhookClient = new GitLabWebhookClient(graphQlClientProvider, tokenService, builder);
    }

    /** Sets up token service mocks for REST API tests. */
    private void stubTokenService() {
        when(tokenService.resolveServerUrl(SCOPE_ID)).thenReturn(SERVER_URL);
        when(tokenService.getAccessToken(SCOPE_ID)).thenReturn(TOKEN);
    }

    @Nested
    class LookupGroup {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnGroupInfo() {
            HttpGraphQlClient mockGraphQlClient = mock(HttpGraphQlClient.class);
            GraphQlClient.RequestSpec requestSpec = mock(GraphQlClient.RequestSpec.class);
            GraphQlClient.RetrieveSpec retrieveSpec = mock(GraphQlClient.RetrieveSpec.class);

            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(mockGraphQlClient);
            when(mockGraphQlClient.documentName("GetGroup")).thenReturn(requestSpec);
            when(requestSpec.variable("fullPath", "my-org")).thenReturn(requestSpec);
            when(requestSpec.retrieve("group")).thenReturn(retrieveSpec);

            GitLabGroupResponse groupResponse = new GitLabGroupResponse(
                    "gid://gitlab/Group/42",
                    "my-org",
                    "My Organization",
                    null,
                    "https://gitlab.com/my-org",
                    null,
                    "public",
                    null);
            when(retrieveSpec.toEntity(GitLabGroupResponse.class)).thenReturn(Mono.just(groupResponse));

            GroupInfo result = webhookClient.lookupGroup(SCOPE_ID, "my-org");

            assertThat(result.id()).isEqualTo(42L);
            assertThat(result.name()).isEqualTo("My Organization");
            assertThat(result.fullPath()).isEqualTo("my-org");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldThrowWhenGroupNotFound() {
            HttpGraphQlClient mockGraphQlClient = mock(HttpGraphQlClient.class);
            GraphQlClient.RequestSpec requestSpec = mock(GraphQlClient.RequestSpec.class);
            GraphQlClient.RetrieveSpec retrieveSpec = mock(GraphQlClient.RetrieveSpec.class);

            when(graphQlClientProvider.forScope(SCOPE_ID)).thenReturn(mockGraphQlClient);
            when(mockGraphQlClient.documentName("GetGroup")).thenReturn(requestSpec);
            when(requestSpec.variable("fullPath", "nonexistent")).thenReturn(requestSpec);
            when(requestSpec.retrieve("group")).thenReturn(retrieveSpec);
            when(retrieveSpec.toEntity(GitLabGroupResponse.class)).thenReturn(Mono.empty());

            assertThatThrownBy(() -> webhookClient.lookupGroup(SCOPE_ID, "nonexistent"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not found");
        }
    }

    @Nested
    class RegisterGroupWebhook {

        @Test
        @SuppressWarnings("unchecked")
        void shouldRegisterWebhook() {
            stubTokenService();
            RequestBodyUriSpec bodyUriSpec = mock(RequestBodyUriSpec.class);
            RequestBodySpec bodySpec = mock(RequestBodySpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            when(mockWebClient.post()).thenReturn(bodyUriSpec);
            Mockito.doReturn(bodySpec).when(bodyUriSpec).uri(anyString(), eq(GROUP_ID));
            Mockito.doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).bodyValue(any());
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                    .thenReturn(Mono.just(Map.of("id", 99, "url", "https://example.com/webhooks/gitlab")));

            WebhookConfig config = new WebhookConfig(
                    "https://example.com/webhooks/gitlab",
                    "secret123",
                    true, // mergeRequestsEvents
                    true, // issuesEvents
                    true, // confidentialIssuesEvents
                    true, // noteEvents
                    false, // confidentialNoteEvents
                    true, // pushEvents
                    true, // tagPushEvents
                    false, // pipelineEvents
                    true, // milestoneEvents
                    true, // memberEvents
                    true, // subgroupEvents
                    true, // projectEvents
                    true // enableSslVerification
                    );

            WebhookInfo result = webhookClient.registerGroupWebhook(SCOPE_ID, GROUP_ID, config);

            assertThat(result.id()).isEqualTo(99L);
            assertThat(result.url()).isEqualTo("https://example.com/webhooks/gitlab");
        }
    }

    @Nested
    class GetGroupWebhook {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnWebhookWhenFound() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID), eq(99L));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                    .thenReturn(Mono.just(Map.of("id", 99, "url", "https://example.com/webhooks/gitlab")));

            Optional<WebhookInfo> result = webhookClient.getGroupWebhook(SCOPE_ID, GROUP_ID, 99L);

            assertThat(result).isPresent();
            assertThat(result.get().id()).isEqualTo(99L);
        }

        @Test
        @DisplayName("should return empty when webhook not found (404)")
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyWhen404() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID), eq(999L));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve())
                    .thenThrow(WebClientResponseException.create(
                            404, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            Optional<WebhookInfo> result = webhookClient.getGroupWebhook(SCOPE_ID, GROUP_ID, 999L);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class ListGroupWebhooks {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnListOfWebhooks() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                    .thenReturn(Mono.just(List.of(
                            Map.of("id", 1, "url", "https://a.com/hooks"),
                            Map.of("id", 2, "url", "https://b.com/hooks"))));

            List<WebhookInfo> result = webhookClient.listGroupWebhooks(SCOPE_ID, GROUP_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).id()).isEqualTo(1L);
            assertThat(result.get(1).url()).isEqualTo("https://b.com/hooks");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnEmptyList() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.just(List.of()));

            List<WebhookInfo> result = webhookClient.listGroupWebhooks(SCOPE_ID, GROUP_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    class DeregisterGroupWebhook {

        @Test
        @SuppressWarnings("unchecked")
        void shouldDeregisterWebhook() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).delete();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID), eq(99L));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.toBodilessEntity()).thenReturn(Mono.empty());

            // Should complete without exception
            webhookClient.deregisterGroupWebhook(SCOPE_ID, GROUP_ID, 99L);
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldTreat404AsSuccess() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).delete();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID), eq(999L));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve())
                    .thenThrow(WebClientResponseException.create(
                            404, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            // Should complete without exception (404 is treated as already deleted)
            webhookClient.deregisterGroupWebhook(SCOPE_ID, GROUP_ID, 999L);
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldRethrowNon404Errors() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).delete();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString(), eq(GROUP_ID), eq(99L));
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve())
                    .thenThrow(WebClientResponseException.create(
                            500, "Internal Server Error", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            assertThatThrownBy(() -> webhookClient.deregisterGroupWebhook(SCOPE_ID, GROUP_ID, 99L))
                    .isInstanceOf(WebClientResponseException.class);
        }
    }

    @Nested
    class IsPermissionOrNotFoundError {

        @Test
        void shouldReturnTrueFor401() {
            assertThat(GitLabWebhookClient.isPermissionOrNotFoundError(HttpStatus.UNAUTHORIZED))
                    .isTrue();
        }

        @Test
        void shouldReturnTrueFor403() {
            assertThat(GitLabWebhookClient.isPermissionOrNotFoundError(HttpStatus.FORBIDDEN))
                    .isTrue();
        }

        @Test
        void shouldReturnTrueFor404() {
            assertThat(GitLabWebhookClient.isPermissionOrNotFoundError(HttpStatus.NOT_FOUND))
                    .isTrue();
        }

        @Test
        void shouldReturnFalseFor500() {
            assertThat(GitLabWebhookClient.isPermissionOrNotFoundError(HttpStatus.INTERNAL_SERVER_ERROR))
                    .isFalse();
        }
    }
}
