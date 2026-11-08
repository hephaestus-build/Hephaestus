package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReadback;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Durable delivery facts for the on-work composition of one sealed review. */
public record FeedbackRepairStatus(@Nullable UUID feedbackId, State state, int edits, List<CopyReadback> copies) {
    public enum State {
        WAITING,
        AWAITING_APPROVAL,
        READBACK,
        STOPPED
    }

    public FeedbackRepairStatus {
        copies = List.copyOf(copies);
    }
}
