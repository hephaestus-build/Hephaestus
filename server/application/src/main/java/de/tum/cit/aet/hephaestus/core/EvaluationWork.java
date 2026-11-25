package de.tum.cit.aet.hephaestus.core;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Each queue supplies its own evaluation state using the same rules as its claimer. */
public interface EvaluationWork {

    List<Item> items(List<Long> workspaceIds);

    /** The tenant roots visible in the same snapshot as the queues. */
    interface Workspaces {
        List<Long> ids();
    }

    interface Row {
        String getId();

        String getState();

        @Nullable
        Instant getFutureDueAt();
    }

    record Item(
            String queue, String id, String state, @Nullable Instant futureDueAt) {}
}
