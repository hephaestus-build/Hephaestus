package de.tum.cit.aet.hephaestus.integration.slack.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slack.api.Slack;
import com.slack.api.SlackConfig;
import com.slack.api.methods.MethodsCompletionException;
import com.slack.api.methods.request.chat.ChatPostMessageRequest;
import java.net.ProtocolException;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.FormBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class SlackSendFenceTest {

    private static final String TOKEN = "xoxb-test";
    private static final ChatPostMessageRequest POST =
            ChatPostMessageRequest.builder().channel("C123").text("message").build();
    private static final MockResponse OK = new MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("{\"ok\":true}")
            .build();

    private MockWebServer slackApi;
    private Slack slack;

    @BeforeEach
    void setUp() throws Exception {
        slackApi = new MockWebServer();
        slackApi.start();
        slackApi.enqueue(OK);
        SlackConfig config = new SlackConfig();
        config.setHttpClientCallTimeoutMillis(5000);
        config.setMethodsEndpointUrlPrefix(slackApi.url("/api/").toString());
        config.getMethodsConfig().setStatsEnabled(false);
        slack = SlackSendFence.client(config);
    }

    @AfterEach
    void tearDown() throws Exception {
        SlackSendFence.ADMISSION.remove();
        try {
            slack.close();
        } finally {
            slackApi.close();
        }
    }

    @Test
    void shouldRefuseSdkPostWithoutAdmission() {
        assertThatThrownBy(() -> slack.methods(TOKEN).chatPostMessage(POST))
                .isInstanceOf(SlackSendFence.NotAdmitted.class);
        assertThat(slackApi.getRequestCount()).isZero();
    }

    @Test
    void shouldRefuseAsyncPostWhenAdmissionIsOnTheCallingThread() throws InterruptedException {
        slackApi.enqueue(OK);
        SlackSendFence.ADMISSION.set(new SlackSendFence.Admission(() -> true));

        assertThatThrownBy(() -> slack.methodsAsync(TOKEN).chatPostMessage(POST).get(5, SECONDS))
                .cause()
                .isInstanceOfSatisfying(
                        MethodsCompletionException.class,
                        failure -> assertThat(failure.getIoException()).isInstanceOf(SlackSendFence.NotAdmitted.class));
        // The async SDK resolves the team with auth.test before it schedules the post.
        assertThat(slackApi.getRequestCount()).isOne();
        assertThat(slackApi.takeRequest().getUrl().encodedPath()).isEqualTo("/api/auth.test");
    }

    @ParameterizedTest
    @ValueSource(strings = {"chat.postMessage", "chat%2EpostMessage"})
    void shouldRefuseRawPostWithoutAdmission(String method) {
        var form = new FormBody.Builder().add("channel", "C123").add("text", "message");

        assertThatThrownBy(() -> slack.methods(TOKEN)
                        .runPostFormWithToken(form, method, TOKEN)
                        .close())
                .isInstanceOf(SlackSendFence.NotAdmitted.class);
        assertThat(slackApi.getRequestCount()).isZero();
    }

    @Test
    void shouldRefusePostWhenAdmissionIsDenied() {
        SlackSendFence.ADMISSION.set(new SlackSendFence.Admission(() -> false));

        assertThatThrownBy(() -> slack.methods(TOKEN).chatPostMessage(POST))
                .isInstanceOf(SlackSendFence.NotAdmitted.class);
        assertThat(slackApi.getRequestCount()).isZero();
    }

    @Test
    void shouldSendAtMostOnePostWithAnAdmission() throws Exception {
        SlackSendFence.ADMISSION.set(new SlackSendFence.Admission(() -> true));

        assertThat(slack.methods(TOKEN).chatPostMessage(POST).isOk()).isTrue();
        assertThatThrownBy(() -> slack.methods(TOKEN).chatPostMessage(POST)).isInstanceOf(ProtocolException.class);
        assertThat(slackApi.getRequestCount()).isOne();
    }

    @Test
    void shouldSendOtherSlackMethodsWithoutPostAdmission() throws Exception {
        assertThat(slack.methods(TOKEN).authTest(request -> request).isOk()).isTrue();
        assertThat(slackApi.getRequestCount()).isOne();
    }
}
