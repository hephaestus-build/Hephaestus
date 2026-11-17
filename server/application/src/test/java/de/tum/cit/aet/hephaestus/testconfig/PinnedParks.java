package de.tum.cit.aet.hephaestus.testconfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingStream;
import org.jspecify.annotations.Nullable;

/**
 * The parks that pinned a virtual thread to its carrier, as the JDK records them ({@code jdk.VirtualThreadPinned}). Since
 * JDK 24 a monitor no longer pins (JEP 491), but a park in a native frame or a class initializer still does, and enough
 * of them stall every virtual thread.
 */
public final class PinnedParks {

    private PinnedParks() {}

    /** Work that may throw, run on a virtual thread. */
    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /** The stack of each pinned park while {@code work} ran on a virtual thread. */
    public static List<String> during(Work work) throws Exception {
        List<String> pinned = new CopyOnWriteArrayList<>();
        AtomicReference<@Nullable Exception> failure = new AtomicReference<>();
        try (var events = new RecordingStream()) {
            events.enable("jdk.VirtualThreadPinned")
                    .withThreshold(Duration.ZERO)
                    .withStackTrace();
            events.onEvent("jdk.VirtualThreadPinned", event -> {
                if (!onJdkSubPoller(event.getStackTrace())) pinned.add(String.valueOf(event.getStackTrace()));
            });
            events.startAsync();
            Thread.ofVirtual()
                    .start(() -> {
                        try {
                            work.run();
                        } catch (Exception e) {
                            failure.set(e);
                        }
                    })
                    .join();
            events.stop();
        }
        Exception thrown = failure.get();
        if (thrown != null) throw thrown;
        return List.copyOf(pinned);
    }

    /**
     * On Linux, JDK 25 runs its socket sub-pollers as virtual threads, and JFR reports their loop as a pinned park. That
     * loop belongs to the JDK, not to the work under test. Only that loop is left out: work that parks pinned in a
     * socket read still counts.
     */
    public static boolean onJdkSubPoller(@Nullable RecordedStackTrace stack) {
        return stack != null
                && stack.getFrames().stream()
                        .anyMatch(frame -> frame.getMethod().getType().getName().equals("sun.nio.ch.Poller")
                                && frame.getMethod().getName().equals("subPollerLoop"));
    }
}
