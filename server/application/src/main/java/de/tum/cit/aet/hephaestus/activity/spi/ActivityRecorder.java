package de.tum.cit.aet.hephaestus.activity.spi;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Cross-module write API for the activity ledger; callers supply authenticated event context. */
public interface ActivityRecorder {
    /**
     * @see de.tum.cit.aet.hephaestus.activity.ActivityEventService#record(Long, ActivityEventType, Instant, User, Repository, ActivityTargetType, Long, double)
     */
    boolean record(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            @Nullable User actor,
            @Nullable Repository repository,
            ActivityTargetType targetType,
            Long targetId,
            double xp);

    /**
     * @see de.tum.cit.aet.hephaestus.activity.ActivityEventService#recordDeleted(Long, ActivityEventType, Instant, ActivityTargetType, Long)
     */
    boolean recordDeleted(
            Long workspaceId,
            ActivityEventType eventType,
            Instant occurredAt,
            ActivityTargetType targetType,
            Long targetId);
}
