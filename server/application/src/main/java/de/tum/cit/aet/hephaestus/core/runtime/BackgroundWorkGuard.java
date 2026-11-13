package de.tum.cit.aet.hephaestus.core.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * {@value ConditionalOnBackgroundWork#PROPERTY} is a switch of the test and build profiles. A production server without
 * background work would schedule, claim and dispatch nothing, and say nothing about it, so it does not start.
 */
@Component
@Profile("prod")
@ConditionalOnBooleanProperty(name = ConditionalOnBackgroundWork.PROPERTY, havingValue = false)
public final class BackgroundWorkGuard {

    public BackgroundWorkGuard() {
        throw new IllegalStateException(ConditionalOnBackgroundWork.PROPERTY
                + " is for the test and build profiles only; a production server must leave it true.");
    }
}
