package de.tum.cit.aet.hephaestus.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PayloadSizeFilterTest extends BaseUnitTest {

    private final AtomicInteger served = new AtomicInteger();
    private final PayloadSizeFilter filter = new PayloadSizeFilter(16);

    @Test
    void shouldServeARequestWithinTheLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestOf("{\"model\":1}"), response, this::serve);

        assertThat(served).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRefuseARequestLargerThanTheConfiguredLimit() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(requestOf("{\"prompt\":\"far too many bytes\"}"), response, this::serve);

        assertThat(served).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(413);
    }

    /** A body whose size is only knowable after reading it is the case the limit exists to avoid. */
    @Test
    void shouldRefuseAChunkedBodyThatDeclaresNoLength() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/any");
        request.addHeader("Transfer-Encoding", "chunked");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, this::serve);

        assertThat(served).hasValue(0);
        assertThat(response.getStatus()).isEqualTo(411);
    }

    @Test
    void shouldServeARequestWithoutABodyWhenItDeclaresNoLength() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest("GET", "/any"), response, this::serve);

        assertThat(served).hasValue(1);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest requestOf(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/any");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private void serve(ServletRequest request, ServletResponse response) {
        served.incrementAndGet();
    }
}
