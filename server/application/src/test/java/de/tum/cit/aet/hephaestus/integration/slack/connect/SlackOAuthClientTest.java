package de.tum.cit.aet.hephaestus.integration.slack.connect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import org.springframework.web.client.RestClient;

class SlackOAuthClientTest extends BaseUnitTest {

    private MockWebServer slackMock;
    private SlackOAuthClient client;

    @BeforeEach
    void setUp() throws IOException {
        slackMock = new MockWebServer();
        slackMock.start();
        String base = slackMock.url("/").toString().replaceAll("/$", "");
        client = new SlackOAuthClient("test-client-id", "test-client-secret", base, RestClient.builder());
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
        assertThat(required(req.getBody()).utf8()).contains("client_id=test-client-id");
    }

    @Test
    void exchangeCode_slackOkFalse_throwsWithSlackErrorCode() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"invalid_code\"}")
                .build());

        assertThatThrownBy(() -> client.exchangeCode("bad", null))
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

        OAuthV2Access r = client.exchangeCode("code", null);

        // The client surfaces rotation fields. The strategy decides whether to reject —
        // see SlackConnectionStrategyTest.finalize_rejectsRotationFields.
        assertThat(r.ok()).isTrue();
        assertThat(r.expiresIn()).isEqualTo(43200);
        assertThat(r.refreshToken()).isEqualTo("xoxe-1-refresh");
    }

    @Test
    void exchangeCode_blankClientCredentials_throwsBeforeHttp() throws IOException {
        SlackOAuthClient unconfigured =
                new SlackOAuthClient("", "", slackMock.url("/").toString().replaceAll("/$", ""), RestClient.builder());
        assertThatThrownBy(() -> unconfigured.exchangeCode("code", null))
                .isInstanceOf(SlackOAuthException.class)
                .hasMessageContaining("slack oauth client not configured");
        assertThat(slackMock.getRequestCount()).isEqualTo(0);
    }

    @Test
    void revokeStrict_treatsAnAlreadyRevokedTokenAsSuccess() {
        slackMock.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"ok\":false,\"error\":\"token_revoked\"}")
                .build());

        assertThatCode(() -> client.revokeStrict("xoxb-revoked")).doesNotThrowAnyException();
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

    private static <T> T required(@Nullable T value) {
        assertNotNull(value);
        return value;
    }
}
