package de.tum.cit.aet.hephaestus.core.lock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/** Unit tests run work under a lock that no other process holds, or one that another always holds. */
public final class ClusterLocks {

    private ClusterLocks() {}

    /** Takes every lock at once. */
    public static ClusterLock free() {
        ClusterLock locks = mock(ClusterLock.class);
        lenient()
                .when(locks.call(anyString(), any(Duration.class), any()))
                .thenAnswer(call -> Optional.of(call.<Supplier<?>>getArgument(2).get()));
        lenient().when(locks.run(anyString(), any(Duration.class), any())).thenAnswer(call -> {
            call.<Runnable>getArgument(2).run();
            return true;
        });
        return locks;
    }

    /** Another process holds every lock for longer than any wait. */
    public static ClusterLock held() {
        ClusterLock locks = mock(ClusterLock.class);
        lenient().when(locks.call(anyString(), any(Duration.class), any())).thenReturn(Optional.empty());
        lenient().when(locks.run(anyString(), any(Duration.class), any())).thenReturn(false);
        return locks;
    }
}
