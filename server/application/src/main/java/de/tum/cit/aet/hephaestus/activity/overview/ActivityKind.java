package de.tum.cit.aet.hephaestus.activity.overview;

import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What someone did, as the Activity pages name it. Each kind is exactly one ledger event type, so a count
 * and the list behind it always agree. Pull request and issue kinds belong to the work's author; review
 * and comment kinds belong to the person who wrote them.
 */
public enum ActivityKind {
    PULL_REQUEST_OPENED(ActivityEventType.PULL_REQUEST_OPENED),
    PULL_REQUEST_MERGED(ActivityEventType.PULL_REQUEST_MERGED),
    PULL_REQUEST_CLOSED(ActivityEventType.PULL_REQUEST_CLOSED),
    REVIEW_APPROVED(ActivityEventType.REVIEW_APPROVED),
    REVIEW_CHANGES_REQUESTED(ActivityEventType.REVIEW_CHANGES_REQUESTED),
    REVIEW_COMMENTED(ActivityEventType.REVIEW_COMMENTED),
    COMMENTED(ActivityEventType.COMMENT_CREATED),
    CODE_COMMENTED(ActivityEventType.REVIEW_COMMENT_CREATED),
    ISSUE_OPENED(ActivityEventType.ISSUE_CREATED),
    ISSUE_CLOSED(ActivityEventType.ISSUE_CLOSED);

    private final ActivityEventType eventType;

    ActivityKind(ActivityEventType eventType) {
        this.eventType = eventType;
    }

    ActivityEventType eventType() {
        return eventType;
    }

    static Optional<ActivityKind> of(ActivityEventType eventType) {
        return Arrays.stream(values())
                .filter(kind -> kind.eventType == eventType)
                .findFirst();
    }

    /** The ledger event types of {@code kinds}; every kind when {@code kinds} is empty. */
    static Set<ActivityEventType> eventTypes(Collection<ActivityKind> kinds) {
        return (kinds.isEmpty() ? EnumSet.allOf(ActivityKind.class) : kinds)
                .stream().map(ActivityKind::eventType).collect(Collectors.toUnmodifiableSet());
    }
}
