package de.tum.cit.aet.hephaestus.integration.core.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ProviderEventClaimersTest extends BaseUnitTest {

    private final ProviderEventConsumer consumer = mock(ProviderEventConsumer.class);
    private final ProviderEventNotifications notifications = new ProviderEventNotifications();
    private final ProviderEventClaimers claimers =
            new ProviderEventClaimers(consumer, notifications, ProviderEventConsumerPropertiesFixture.defaults());

    @Test
    void shouldClaimOnEachThreadUntilStoppedAndClaimAgainAfterARestart() throws InterruptedException {
        var claimed = new CountDownLatch(2);
        when(consumer.claimAndHandle()).thenAnswer(invocation -> {
            claimed.countDown();
            return false;
        });

        claimers.start();
        assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(claimers.isRunning()).isTrue();
        assertThat(claimers.alive()).isEqualTo(2);

        claimers.stop();
        assertThat(claimers.isRunning()).isFalse();
        assertThat(claimers.alive()).isZero();
        verify(consumer).stopping();

        var claimedAgain = new CountDownLatch(1);
        when(consumer.claimAndHandle()).thenAnswer(invocation -> {
            claimedAgain.countDown();
            return false;
        });
        claimers.start();
        try {
            assertThat(claimedAgain.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            claimers.stop();
        }
    }

    @Test
    void shouldWakeAnIdleClaimerWhenThisServerQueuesAnEvent() throws InterruptedException {
        var looks = new AtomicInteger();
        when(consumer.claimAndHandle()).thenAnswer(invocation -> {
            looks.incrementAndGet();
            return false;
        });

        claimers.start();
        try {
            // Both claimers found nothing and wait for the idle poll.
            await().atMost(Duration.ofSeconds(5))
                    .until(() -> claimerThreads().size() == 2
                            && claimerThreads().stream()
                                    .allMatch(thread -> thread.getState() == Thread.State.TIMED_WAITING));
            int idle = looks.get();
            notifications.queued();
            // The notification ends both waits before the poll would.
            await().atMost(ProviderEventClaimers.IDLE_POLL.minusSeconds(1)).until(() -> looks.get() >= idle + 2);
        } finally {
            claimers.stop();
        }
    }

    private static List<Thread> claimerThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith("provider-event-claimer-"))
                .toList();
    }
}
