package de.tum.cit.aet.hephaestus.integration.scm.gitlab.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.RotatedToken;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenRotationClient.TokenInfo;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodySpec;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

/**
 * Unit tests for {@link GitLabTokenRotationClient}.
 */
@Tag("unit")
class GitLabTokenRotationClientTest extends BaseUnitTest {

    @Mock
    private GitLabTokenService tokenService;

    private WebClient mockWebClient;
    private GitLabTokenRotationClient rotationClient;

    private static final Long SCOPE_ID = 1L;
    private static final String SERVER_URL = "https://gitlab.com";
    private static final String TOKEN = "glpat-test-token";

    @BeforeEach
    void setUp() {
        mockWebClient = mock(WebClient.class);
        WebClient.Builder builder = mock(WebClient.Builder.class);
        when(builder.build()).thenReturn(mockWebClient);

        rotationClient = new GitLabTokenRotationClient(tokenService, builder);
    }

    /** Sets up token service mocks for REST API tests. */
    private void stubTokenService() {
        when(tokenService.resolveServerUrl(SCOPE_ID)).thenReturn(SERVER_URL);
        when(tokenService.getAccessToken(SCOPE_ID)).thenReturn(TOKEN);
    }

    @Nested
    class GetTokenInfo {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnTokenInfoWithExpiry() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString());
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                    .thenReturn(Mono.just(Map.of("id", 123, "name", "my-token", "expires_at", "2026-06-01")));

            TokenInfo result = rotationClient.getTokenInfo(SCOPE_ID);

            assertThat(result.id()).isEqualTo(123L);
            assertThat(result.name()).isEqualTo("my-token");
            assertThat(result.expiresAt()).isEqualTo(LocalDate.of(2026, 6, 1));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldHandleNullExpiry() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString());
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve()).thenReturn(responseSpec);

            // expires_at can be null for non-expiring tokens
            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("id", 123);
            responseMap.put("name", "my-token");
            responseMap.put("expires_at", null);

            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.just(responseMap));

            TokenInfo result = rotationClient.getTokenInfo(SCOPE_ID);

            assertThat(result.expiresAt()).isNull();
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldThrowOn401() {
            stubTokenService();
            RequestHeadersUriSpec<?> uriSpec = mock(RequestHeadersUriSpec.class);
            RequestHeadersSpec<?> headersSpec = mock(RequestHeadersSpec.class);

            org.mockito.Mockito.doReturn(uriSpec).when(mockWebClient).get();
            org.mockito.Mockito.doReturn(headersSpec).when(uriSpec).uri(anyString());
            org.mockito.Mockito.doReturn(headersSpec).when(headersSpec).header(anyString(), anyString());
            when(headersSpec.retrieve())
                    .thenThrow(WebClientResponseException.create(
                            401, "Unauthorized", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            assertThatThrownBy(() -> rotationClient.getTokenInfo(SCOPE_ID))
                    .isInstanceOf(WebClientResponseException.class);
        }
    }

    @Nested
    class RotateToken {

        @Test
        @SuppressWarnings("unchecked")
        void shouldRotateToken() {
            stubTokenService();
            RequestBodyUriSpec bodyUriSpec = mock(RequestBodyUriSpec.class);
            RequestBodySpec bodySpec = mock(RequestBodySpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            when(mockWebClient.post()).thenReturn(bodyUriSpec);
            Mockito.doReturn(bodySpec).when(bodyUriSpec).uri(anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).bodyValue(any());
            when(bodySpec.retrieve()).thenReturn(responseSpec);
            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class)))
                    .thenReturn(Mono.just(Map.of("token", "glpat-new-rotated-token", "expires_at", "2026-09-01")));

            RotatedToken result = rotationClient.rotateToken(SCOPE_ID, LocalDate.of(2026, 9, 1));

            assertThat(result.token()).isEqualTo("glpat-new-rotated-token");
            assertThat(result.expiresAt()).isEqualTo(LocalDate.of(2026, 9, 1));
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldThrowWhenResponseMissingToken() {
            stubTokenService();
            RequestBodyUriSpec bodyUriSpec = mock(RequestBodyUriSpec.class);
            RequestBodySpec bodySpec = mock(RequestBodySpec.class);
            ResponseSpec responseSpec = mock(ResponseSpec.class);

            when(mockWebClient.post()).thenReturn(bodyUriSpec);
            Mockito.doReturn(bodySpec).when(bodyUriSpec).uri(anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).bodyValue(any());
            when(bodySpec.retrieve()).thenReturn(responseSpec);

            Map<String, Object> responseMap = new HashMap<>();
            responseMap.put("token", null);
            responseMap.put("expires_at", "2026-09-01");

            when(responseSpec.bodyToMono(any(ParameterizedTypeReference.class))).thenReturn(Mono.just(responseMap));

            assertThatThrownBy(() -> rotationClient.rotateToken(SCOPE_ID, LocalDate.of(2026, 9, 1)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing 'token' field");
        }

        @Test
        @DisplayName("should throw on 403 (insufficient scope)")
        @SuppressWarnings("unchecked")
        void shouldThrowOn403() {
            stubTokenService();
            RequestBodyUriSpec bodyUriSpec = mock(RequestBodyUriSpec.class);
            RequestBodySpec bodySpec = mock(RequestBodySpec.class);

            when(mockWebClient.post()).thenReturn(bodyUriSpec);
            Mockito.doReturn(bodySpec).when(bodyUriSpec).uri(anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).header(anyString(), anyString());
            Mockito.doReturn(bodySpec).when(bodySpec).bodyValue(any());
            when(bodySpec.retrieve())
                    .thenThrow(WebClientResponseException.create(
                            403, "Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));

            assertThatThrownBy(() -> rotationClient.rotateToken(SCOPE_ID, LocalDate.of(2026, 9, 1)))
                    .isInstanceOf(WebClientResponseException.class);
        }
    }

    @Nested
    class RecordToString {

        @Test
        void tokenInfoToString() {
            TokenInfo info = new TokenInfo(1L, "test", LocalDate.of(2026, 1, 1));
            assertThat(info.toString()).doesNotContain("glpat");
        }

        @Test
        void rotatedTokenToString() {
            RotatedToken token = new RotatedToken("glpat-secret", LocalDate.of(2026, 1, 1));
            assertThat(token.toString()).doesNotContain("glpat-secret");
        }
    }
}
