package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PracticesAcrossWorkspaceCacheTest extends BaseUnitTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T10:00:00Z"));

    private final PracticesAcrossWorkspaceCache cache = new PracticesAcrossWorkspaceCache(
            new SimpleMeterRegistry(), clock, Duration.ofMinutes(2), Duration.ofMinutes(10), Duration.ofMillis(50));

    @AfterEach
    void stopRecounts() {
        cache.destroy();
    }

    @Test
    void shouldCountOnceWhenReadersAskWhileTheFirstCount() throws Exception {
        AtomicInteger counts = new AtomicInteger();
        CountDownLatch counting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService readers = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> answers = new ArrayList<>();
            answers.add(readers.submit(() -> cache.get(7L, "overview", () -> {
                counts.incrementAndGet();
                counting.countDown();
                await(release);
                return "counted";
            })));
            assertThat(counting.await(10, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 7; index++) {
                answers.add(readers.submit(() -> cache.get(7L, "overview", () -> {
                    counts.incrementAndGet();
                    return "counted again";
                })));
            }
            release.countDown();
            for (Future<String> answer : answers) {
                assertThat(answer.get(10, TimeUnit.SECONDS)).isEqualTo("counted");
            }
            assertThat(counts).hasValue(1);
        } finally {
            readers.shutdownNow();
        }
    }

    @Test
    void shouldCountAgainWhenTheWorkspaceIsDroppedButKeepOtherWorkspacesAndViews() {
        assertThat(cache.get(7L, "overview", () -> "first")).isEqualTo("first");
        assertThat(cache.get(7L, "tiles:DAYS_30", () -> "tiles")).isEqualTo("tiles");
        assertThat(cache.get(8L, "overview", () -> "other")).isEqualTo("other");
        assertThat(cache.get(7L, "overview", () -> "second")).isEqualTo("first");

        cache.invalidate(7L);

        assertThat(cache.get(7L, "overview", () -> "second")).isEqualTo("second");
        assertThat(cache.get(7L, "tiles:DAYS_30", () -> "tiles again")).isEqualTo("tiles again");
        assertThat(cache.get(8L, "overview", () -> "other again")).isEqualTo("other");

        cache.invalidateAll();

        assertThat(cache.get(8L, "overview", () -> "other again")).isEqualTo("other again");
    }

    @Test
    void shouldKeepNoCountThatFailed() {
        assertThatThrownBy(() -> cache.get(7L, "overview", () -> {
                    throw new IllegalStateException("database gone");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(cache.get(7L, "overview", () -> "counted")).isEqualTo("counted");
    }

    @Test
    void shouldNotKeepACountThatWasDroppedWhileItRan() throws Exception {
        CountDownLatch counting = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<String> stale = reader.submit(() -> cache.get(7L, "overview", () -> {
                counting.countDown();
                await(release);
                return "before the change";
            }));
            assertThat(counting.await(10, TimeUnit.SECONDS)).isTrue();
            cache.invalidate(7L);
            release.countDown();
            assertThat(stale.get(10, TimeUnit.SECONDS)).isEqualTo("before the change");

            assertThat(cache.get(7L, "overview", () -> "after the change")).isEqualTo("after the change");
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void shouldKeepReadingTheCountWhileNewResultsAreCountedAgainInTheBackground() throws Exception {
        AtomicInteger version = new AtomicInteger();
        assertThat(cache.get(7L, "overview", () -> "count " + version.incrementAndGet()))
                .isEqualTo("count 1");

        cache.recountLater(7L);
        cache.recountLater(7L);

        // Readable at once, the earlier count, while the recount waits for the burst to end.
        assertThat(cache.get(7L, "overview", () -> "counted on the reader's thread"))
                .isEqualTo("count 1");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cache.get(7L, "overview", () -> "counted on the reader's thread")
                .equals("count 2")) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
        Thread.sleep(200);
        // Two notices of one burst, one recount.
        assertThat(version).hasValue(2);
    }

    @Test
    void shouldCountAgainInTheBackgroundWhenAReaderFindsACountNoLongerFresh() throws Exception {
        AtomicInteger version = new AtomicInteger();
        cache.get(7L, "overview", () -> "count " + version.incrementAndGet());

        clock.advance(Duration.ofMinutes(3));

        assertThat(cache.get(7L, "overview", () -> "counted on the reader's thread"))
                .isEqualTo("count 1");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (version.get() < 2) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(10);
        }
        assertThat(cache.get(7L, "overview", () -> "counted on the reader's thread"))
                .isEqualTo("count 2");
    }

    @Test
    void shouldKeepNoRecountOfACountDroppedWhileItWasCountedAgain() {
        AtomicInteger version = new AtomicInteger();
        cache.get(7L, "overview", () -> {
            if (version.incrementAndGet() == 2) {
                // A hidden member drops the counts while the background recount runs.
                cache.invalidate(7L);
            }
            return "count " + version.get();
        });

        cache.recountNow(7L);

        assertThat(cache.get(7L, "overview", () -> "counted after the drop")).isEqualTo("counted after the drop");
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
