package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.slack.SlackProperties;
import de.tum.cit.aet.hephaestus.integration.slack.connect.SlackOAuthClient.OAuthV2Access;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.io.IOException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

class SlackOAuthClientTest extends BaseUnitTest {

    private MockWebServer slackMock;
    private SlackOAuthClient client;

    @BeforeEach
    void setUp() throws IOException {
        slackMock = new MockWebServer();
        slackMock.start();
        String base = slackMock.url("/").toString().replaceAll("/$", "");
        client = new SlackOAuthClient(
                "test-client-id", "test-client-secret", new SlackProperties(base), RestClient.builder());
    }

    @AfterEach
    void tearDown() throws IOException {
        slackMock.close();
    }

    @Test
    void exchangeCode_happyPath_returnsBoundAccessRecord() throws Exception {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":true,\"access_token\":\"xoxb-abc\",\"app_id\":\"A1\",\"bot_user_id\":\"U1\","
                        + "\"team\":{\"id\":\"T1\",\"name\":\"Acme\"},"
                        + "\"scope\":\"chat:write,users:read\"}")
                .build());

        OAuthV2Access r = client.exchangeCode("code-123", "https://example.test/cb");

        assertThat(r.ok()).isTrue();
        assertThat(r.accessToken()).isEqualTo("xoxb-abc");
        assertThat(required(r.team()).id()).isEqualTo("T1");

        RecordedRequest req = slackMock.takeRequest();
        assertThat(required(req.getBody()).utf8())
                .contains("client_id=test-client-id", "redirect_uri=https%3A%2F%2Fexample.test%2Fcb");
    }

    @Test
    void exchangeCode_slackOkFalse_throwsWithSlackErrorCode() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"invalid_code\"}")
                .build());

        assertThatThrownBy(() -> client.exchangeCode("bad", "https://example.test/api/oauth/callback/slack"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessageContaining("invalid_code");
    }

    @Test
    void exchangeCode_returnsRotationFields_passesThroughForStrategyToReject() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":true,\"access_token\":\"xoxe-abc\",\"team\":{\"id\":\"T1\",\"name\":\"Acme\"},"
                        + "\"expires_in\":43200,\"refresh_token\":\"xoxe-1-refresh\"}")
                .build());

        OAuthV2Access r = client.exchangeCode("code", "https://example.test/api/oauth/callback/slack");

        // The client surfaces rotation fields. The strategy decides whether to reject —
        // see SlackConnectionStrategyTest.finalize_rejectsRotationFields.
        assertThat(r.ok()).isTrue();
        assertThat(r.expiresIn()).isEqualTo(43200);
        assertThat(r.refreshToken()).isEqualTo("xoxe-1-refresh");
    }

    @Test
    void exchangeCode_blankClientCredentials_throwsBeforeHttp() throws IOException {
        SlackOAuthClient unconfigured = new SlackOAuthClient(
                "", "", new SlackProperties(slackMock.url("/").toString()), RestClient.builder());
        assertThatThrownBy(() -> unconfigured.exchangeCode("code", "https://example.test/api/oauth/callback/slack"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessageContaining("slack oauth client not configured");
        assertThat(slackMock.getRequestCount()).isEqualTo(0);
    }

    @Test
    void revokeStrict_doesNotTreatAnAlreadyRevokedTokenAsThisAttemptsTerminalReceipt() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"token_revoked\"}")
                .build());

        assertThatThrownBy(() -> client.revokeStrict("xoxb-revoked"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessage("token_revoked");
    }

    @Test
    void shouldRequirePositiveRevocationInTheSlackReceipt() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":true,\"revoked\":true}")
                .build());
        assertThatCode(() -> client.revokeStrict("xoxb-spent")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"ok\":true,\"revoked\":false}", "{\"ok\":true}"})
    void shouldRetainUncertaintyWhenSlackDidNotRevokeTheToken(String body) {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build());
        assertThatThrownBy(() -> client.revokeStrict("xoxb-live"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessage("revocation_unconfirmed");
    }

    @Test
    void shouldExposeARevokedIdentityAsAbsenceWithoutADeleteReceipt() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"token_revoked\"}")
                .build());
        assertThatThrownBy(() -> client.botIdentity("xoxb-revoked"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessage("token_revoked");
    }

    @Test
    void revokeStrict_propagatesProviderFailure() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"invalid_auth\"}")
                .build());

        assertThatThrownBy(() -> client.revokeStrict("xoxb-invalid"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessageContaining("invalid_auth");
    }

    @Test
    void shouldReadTheGrantedScopesFromTheHeaderOfAuthTest() throws Exception {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-OAuth-Scopes", "chat:write,assistant:write, im:history")
                .body("{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"U1\"}")
                .build());

        assertThat(client.grantedScopes("xoxb-abc"))
                .containsExactlyInAnyOrder("chat:write", "assistant:write", "im:history");

        RecordedRequest req = slackMock.takeRequest();
        assertThat(req.getUrl().encodedPath()).isEqualTo("/api/auth.test");
        assertThat(req.getHeaders().get("Authorization")).isEqualTo("Bearer xoxb-abc");
    }

    @Test
    void shouldRefuseToNameTheGrantedScopesWhenSlackSendsNoScopeHeader() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":true,\"team_id\":\"T1\",\"user_id\":\"U1\"}")
                .build());

        assertThatThrownBy(() -> client.grantedScopes("xoxb-abc"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessage("scopes_not_reported");
    }

    @Test
    void shouldRefuseToNameTheGrantedScopesWhenSlackRefusesTheToken() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-OAuth-Scopes", "")
                .body("{\"ok\":false,\"error\":\"invalid_auth\"}")
                .build());

        assertThatThrownBy(() -> client.grantedScopes("xoxb-invalid"))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessage("invalid_auth");
    }

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
