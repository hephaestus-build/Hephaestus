package de.tum.cit.aet.hephaestus.agent.handler;

import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which producer a sealed review hands off to (ADR 0160). Both settings stay off in production. The mentor writes
 * lasting advice, so it may produce only in a release later than the one that registers the advice stores in the
 * erasure inventory, and later than the first release that knows the producer on every replica. A release after both
 * removes {@link #allowMentorBeforeAdviceErasure}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hephaestus.practice-review.feedback-producer")
public class FeedbackProducerProperties {
    /**
     * Lets a seal choose the mentor before the release that may open it. For a synthetic proof environment only: the
     * server logs a warning at start while it is set.
     */
    private boolean allowMentorBeforeAdviceErasure = false;

    /** The workspaces whose sealed reviews go to the mentor, when the author qualifies. */
    private Set<Long> mentorWorkspaceIds = new LinkedHashSet<>();
}
