package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.slack.events.SlackSignatureVerifier;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;

@Tag("unit")
class SlackInteractivityControllerTest extends BaseUnitTest {

    private static final String SIGNING_SECRET = "test-signing-secret";

    @Mock
    private SlackInteractivityHandler handler;

    @Mock
    private SlackRequestReplayGuard replayGuard;

    private SlackInteractivityController controller;

    @BeforeEach
    void setUp() {
        // Lenient: a request with a refused signature never reaches the claim.
        lenient().when(replayGuard.claim(anyString())).thenReturn(true);
        controller = new SlackInteractivityController(
                new SlackSignatureVerifier(SIGNING_SECRET),
                replayGuard,
                handler,
                JsonMapper.builder().build());
    }

    private static String formBody(String payloadJson) {
        return "payload=" + URLEncoder.encode(payloadJson, StandardCharsets.UTF_8);
    }

    private static String sign(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal(body);
            return "v0=" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void signedBlockActions_acks200_andDispatches() {
        byte[] body = formBody("{\"type\":\"block_actions\",\"actions\":[]}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Slack-Request-Timestamp", ts);
        headers.add("X-Slack-Signature", sign(ts, body));

        ResponseEntity<String> res = controller.interactivity(body, headers);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(handler).handleBlockActions(any());
    }

    @Test
    void signedBlockActions_usesCachedRawBodyWhenRequestBodyWasConsumed() {
        byte[] body = formBody("{\"type\":\"block_actions\",\"actions\":[]}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Slack-Request-Timestamp", ts);
        headers.add("X-Slack-Signature", sign(ts, body));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(SlackInteractivityRawBodyFilter.RAW_BODY_ATTRIBUTE, body);

        ResponseEntity<String> res = controller.interactivity(new byte[0], headers, request);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verify(handler).handleBlockActions(any());
    }

    @Test
    void badSignature_rejected401_noDispatch() {
        byte[] body = formBody("{\"type\":\"block_actions\",\"actions\":[]}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Slack-Request-Timestamp", ts);
        headers.add("X-Slack-Signature", "v0=deadbeef");

        ResponseEntity<String> res = controller.interactivity(body, headers);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(handler);
    }

    @Test
    void expiredTimestamp_rejected401_noDispatch() {
        byte[] body = formBody("{\"type\":\"block_actions\",\"actions\":[]}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond() - 301);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Slack-Request-Timestamp", ts);
        headers.add("X-Slack-Signature", sign(ts, body));

        ResponseEntity<String> res = controller.interactivity(body, headers);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(handler);
    }

    @Test
    void handlerFailure_returns500SoSlackCanRetryPrivacyAction() {
        doThrow(new IllegalStateException("db down")).when(handler).handleBlockActions(any());
        byte[] body = formBody("{\"type\":\"block_actions\",\"actions\":[]}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Slack-Request-Timestamp", ts);
        headers.add("X-Slack-Signature", sign(ts, body));

        ResponseEntity<String> res = controller.interactivity(body, headers);

        assertThat(res.getStatusCode().value()).isEqualTo(500);
        verify(handler).handleBlockActions(any());
    }

    /** Slack's signature stays valid for minutes, so a copy of a signed request must not repeat its action. */
    @Test
    void shouldAcknowledgeACopyOfAHandledRequestWithoutDispatchingIt() {
        byte[] body = formBody("{\"type\":\"block_actions\",\"actions\":[]}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(ts, body);
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Slack-Request-Timestamp", ts);
        headers.add("X-Slack-Signature", signature);
        when(replayGuard.claim(signature)).thenReturn(false);

        ResponseEntity<String> res = controller.interactivity(body, headers);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        verifyNoInteractions(handler);
    }
}
