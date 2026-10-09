package de.tum.cit.aet.hephaestus.core.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class SseEventBoundaryTest extends BaseUnitTest {

    @Test
    void shouldRelayWholeEventsAndHoldTheRestUntilItsEventEnds() {
        ProxyStreamingUtils.EventBoundary events = new ProxyStreamingUtils.EventBoundary();

        assertThat(text(events.complete(bytes("data: a\n\ndata: b")))).isEqualTo("data: a\n\n");
        assertThat(text(events.complete(bytes("c\n")))).isEmpty();
        assertThat(text(events.complete(bytes("\ndata: d\r\n\r\n")))).isEqualTo("data: bc\n\ndata: d\r\n\r\n");
        assertThat(text(events.complete(bytes("data: [DONE]")))).isEmpty();
        assertThat(text(events.rest())).isEqualTo("data: [DONE]");
    }

    @Test
    void shouldPassAKeepAliveCommentThrough() {
        ProxyStreamingUtils.EventBoundary events = new ProxyStreamingUtils.EventBoundary();

        assertThat(text(events.complete(bytes(": ping\n\n")))).isEqualTo(": ping\n\n");
    }

    @Test
    void shouldEndEventsAtCarriageReturnsAlsoAcrossChunks() {
        ProxyStreamingUtils.EventBoundary events = new ProxyStreamingUtils.EventBoundary();

        assertThat(text(events.complete(bytes("data: a\r")))).isEmpty();
        assertThat(text(events.complete(bytes("\rdata: b")))).isEqualTo("data: a\r\r");
        assertThat(text(events.complete(bytes("\r\n\r\n")))).isEqualTo("data: b\r\n\r\n");
    }

    @Test
    void shouldFailAnEventThatNeverEndsPastTheBound() {
        ProxyStreamingUtils.EventBoundary events = new ProxyStreamingUtils.EventBoundary();
        events.complete(bytes("data: "));

        assertThatThrownBy(() -> events.complete(new byte[ProxyStreamingUtils.MAX_PENDING_EVENT_BYTES]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("without ending");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
