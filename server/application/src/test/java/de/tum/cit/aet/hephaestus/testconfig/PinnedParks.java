package de.tum.cit.aet.hephaestus.testconfig;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import jdk.jfr.consumer.RecordedFrame;
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
                if (!ownedByJdk(event.getStackTrace())) pinned.add(String.valueOf(event.getStackTrace()));
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
     * A pinned park that the JDK causes, not the work under test. Only two are left out:
     *
     * <ul>
     *   <li>On Linux, JDK 25 runs its socket sub-pollers as virtual threads, and JFR reports their loop as a pinned park.
     *   <li>The VM registers each new object of a class with a finalizer, Jakarta Mail's {@code Service} among them,
     *       through an upcall into {@code Finalizer.register}. The registration takes the lock of the JDK's finalizer
     *       list, which the finalizer thread also takes. A thread that registers while another thread holds that lock
     *       waits pinned for one insert or removal.
     * </ul>
     *
     * Work that parks pinned in a socket read or a class initializer, or waits for another thread's class initializer,
     * still counts.
     */
    public static boolean ownedByJdk(@Nullable RecordedStackTrace stack) {
        if (stack == null) return false;
        List<RecordedFrame> frames = stack.getFrames();
        return frames.stream().anyMatch(frame -> is(frame, "sun.nio.ch.Poller", "subPollerLoop"))
                || (!frames.isEmpty() && is(frames.getFirst(), "java.lang.ref.Finalizer", "<init>"));
    }

    private static boolean is(RecordedFrame frame, String type, String method) {
        return frame.getMethod().getType().getName().equals(type)
                && frame.getMethod().getName().equals(method);
    }
}
