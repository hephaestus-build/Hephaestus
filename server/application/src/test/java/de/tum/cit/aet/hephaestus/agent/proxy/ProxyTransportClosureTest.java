package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
        var firstRequest = transports.begin();
        var secondRequest = transports.begin();
        firstRequest.connected(first);
        secondRequest.connected(second);
        firstRequest.finish(false);
        secondRequest.finish(false);
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
        var transports = new ProxyTransportClosure();
        var connection = mock(Connection.class);
        var request = transports.begin();
        request.connected(connection);
        request.finish(true);
        transports.releaseWhenClosed(() -> released.set(true));
        assertThat(released).isTrue();
        verify(connection, never()).dispose();
    }

    @Test
    void shouldPreventADelayedConnectionFromDispatchingAfterThePermitReleased() {
        var transports = new ProxyTransportClosure();
        var request = transports.begin();
        request.finish(false);
        AtomicBoolean released = new AtomicBoolean();
        transports.releaseWhenClosed(() -> released.set(true));
        assertThat(released).isTrue();
        var late = mock(Connection.class);
        assertThatThrownBy(() -> request.connected(late)).isInstanceOf(IllegalStateException.class);
        verify(late).dispose();
    }
}
