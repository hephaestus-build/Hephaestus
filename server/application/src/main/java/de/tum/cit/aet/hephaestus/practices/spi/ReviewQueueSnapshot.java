package de.tum.cit.aet.hephaestus.practices.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

public record ReviewQueueSnapshot(
        Instant asOf,
        long running,
        long waiting,
        @Nullable Instant oldestWaitingSince,
        long heldForBudget,
        List<ReviewQueueJob> head,
        long finishedLastHour,
        long finishedLastDay) {
    public ReviewQueueSnapshot {
        head = List.copyOf(head);
    }
}
