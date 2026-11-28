package de.tum.cit.aet.hephaestus.testconfig;

import java.lang.reflect.Method;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Lets a test run a scheduled method now. A call on the bean goes through ShedLock, which returns without running the
 * method while an earlier run's {@code lockAtLeastFor} still holds the lock. Expiring the lock row, instead of calling
 * the unproxied bean, keeps the bean's other proxies: its transaction and its tenancy bypass.
 */
public final class ScheduledLocks {
    private ScheduledLocks() {}

    /** Expires the lock of the {@code @SchedulerLock} method {@code method} of {@code type}, so its next call runs. */
    public static void free(JdbcTemplate jdbc, Class<?> type, String method) {
        for (Method candidate : type.getDeclaredMethods()) {
            SchedulerLock lock = candidate.getAnnotation(SchedulerLock.class);
            if (lock != null && candidate.getName().equals(method)) {
                jdbc.update(
                        "UPDATE shedlock SET lock_until = timezone('utc', CURRENT_TIMESTAMP) - INTERVAL '1 second'"
                                + " WHERE name = ?",
                        lock.name());
                return;
            }
        }
        throw new IllegalArgumentException(type.getSimpleName() + "." + method + " has no @SchedulerLock");
    }
}
