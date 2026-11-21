package de.tum.cit.aet.hephaestus.core.database.backup;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** The backup schedules, read where they run. */
public interface BackupSchedules {

    /** A backup schedule: when it was created, and when a backup of it last succeeded, if one has. */
    record Schedule(Instant created, @Nullable Instant lastSuccess) {}

    /**
     * The schedule of that name, or empty when it does not exist.
     *
     * @throws RuntimeException when the read fails
     */
    Optional<Schedule> read(String name);
}
