package de.tum.cit.aet.hephaestus.integration.slack.interactivity;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SlackInteractivityRawBodyFilterTest extends BaseUnitTest {

    @Test
    void preservesOriginalFormBodyWhenParametersAreReadFirst() throws Exception {
        byte[] body = "payload=%7B%22type%22%3A%22block_actions%22%2C%22text%22%3A%22a%2Bb%20c%22%7D"
                .getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/slack/interactivity");
        request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> cachedBody = new AtomicReference<>();
        AtomicReference<byte[]> streamBody = new AtomicReference<>();

        new SlackInteractivityRawBodyFilter().doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) throws IOException {
                request.getParameterMap();
                cachedBody.set((byte[]) request.getAttribute(SlackInteractivityRawBodyFilter.RAW_BODY_ATTRIBUTE));
                streamBody.set(request.getInputStream().readAllBytes());
            }
        });

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(cachedBody.get()).isEqualTo(body);
        assertThat(streamBody.get()).isEqualTo(body);
    }
}
