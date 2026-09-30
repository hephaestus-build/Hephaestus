package de.tum.cit.aet.hephaestus.practices.spi;

import de.tum.cit.aet.hephaestus.practices.model.Observation;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Compares the work behind observations with a pull request as it stands, without exposing agent-job persistence. */
public interface RevisedWorkLookup {

    /**
     * The observations among {@code observations} whose producing review captured this pull request's title,
     * description or head differently from the given values. A capture that is missing, malformed, about other work,
     * from another workspace or not usable for automated review shows no difference, and neither does an equal one.
     */
    Set<UUID> recordedOnOtherWork(
            long workspaceId,
            long pullRequestId,
            @Nullable String title,
            @Nullable String body,
            @Nullable String head,
            Collection<Observation> observations);
}
