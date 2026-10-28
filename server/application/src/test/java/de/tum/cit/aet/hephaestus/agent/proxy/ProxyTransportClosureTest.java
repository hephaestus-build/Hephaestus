package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.netty.Connection;

@Tag("unit")
class ProxyTransportClosureTest {
    @Test
    void shouldKeepCapacityUntilEveryCancelledTransportAcknowledgesClosure() {
        var first = mock(Connection.class);
        var second = mock(Connection.class);
        var firstChannel = mock(Channel.class);
        var secondChannel = mock(Channel.class);
        var firstClosed = new DefaultChannelPromise(firstChannel, ImmediateEventExecutor.INSTANCE);
        var secondClosed = new DefaultChannelPromise(secondChannel, ImmediateEventExecutor.INSTANCE);
        when(first.channel()).thenReturn(firstChannel);
        when(second.channel()).thenReturn(secondChannel);
        when(firstChannel.closeFuture()).thenReturn(firstClosed);
        when(secondChannel.closeFuture()).thenReturn(secondClosed);
        var transports = new ProxyTransportClosure();
        AtomicBoolean released = new AtomicBoolean();
        transports.closing(first);
        transports.closing(second);
        transports.releaseWhenClosed(() -> released.set(true));
        verify(first).dispose();
        verify(second).dispose();
        assertThat(released).isFalse();
        firstClosed.setSuccess();
        assertThat(released).isFalse();
        secondClosed.setSuccess();
        await().atMost(Duration.ofSeconds(2)).untilTrue(released);
    }

    @Test
    void shouldReleaseAnExchangeThatFinishedWithoutACancelledTransport() {
        AtomicBoolean released = new AtomicBoolean();
        new ProxyTransportClosure().releaseWhenClosed(() -> released.set(true));
        assertThat(released).isTrue();
    }
}
