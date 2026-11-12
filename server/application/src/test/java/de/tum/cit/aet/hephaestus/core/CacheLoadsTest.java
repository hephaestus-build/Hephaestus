package de.tum.cit.aet.hephaestus.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class CacheLoadsTest extends BaseUnitTest {

    private final AsyncCache<String, String> cache = Caffeine.newBuilder().buildAsync();
    private final AtomicInteger loads = new AtomicInteger();
    private final CountDownLatch loading = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private String slowLoad(String key) {
        int load = loads.incrementAndGet();
        loading.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return key + "-" + load;
    }

    @Test
    void shouldLoadAKeyOnceForTheCallersThatAskWhileItLoads() throws Exception {
        AtomicReference<@Nullable String> first = new AtomicReference<>();
        Thread loader = Thread.ofVirtual().start(() -> first.set(CacheLoads.get(cache, "k", this::slowLoad)));
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();
        AtomicReference<@Nullable String> second = new AtomicReference<>();
        Thread waiter = Thread.ofVirtual().start(() -> second.set(CacheLoads.get(cache, "k", this::slowLoad)));

        release.countDown();
        loader.join();
        waiter.join();

        assertThat(first.get()).isEqualTo("k-1");
        assertThat(second.get()).isEqualTo("k-1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldNotPutBackAnEntryThatWasInvalidatedWhileItLoaded() throws Exception {
        Thread loader = Thread.ofVirtual().start(() -> CacheLoads.get(cache, "k", this::slowLoad));
        assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();

        cache.synchronous().invalidate("k");
        release.countDown();
        loader.join();

        assertThat(cache.synchronous().getIfPresent("k")).isNull();
    }

    @Test
    void shouldThrowToTheCallerWhoseLoadFailedAndLoadAgainForTheNextCaller() {
        assertThatThrownBy(() -> CacheLoads.get(cache, "k", key -> {
                    throw new IllegalStateException("unavailable");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unavailable");

        assertThat(CacheLoads.get(cache, "k", key -> "loaded")).isEqualTo("loaded");
    }
}
