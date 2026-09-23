package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import org.jspecify.annotations.Nullable;

/**
 * Whether feedback may reach a channel without a person: the provenance must be entitled to the channel
 * ({@link ObservationOrigin#delivers}) and the practice's autonomy must release it there
 * ({@link PracticeAutonomy#delivers}). Every other delivery rule — attribution, corroboration, cooldown,
 * workspace controls — is the router's.
 */
public final class PracticeAutonomyPolicy {

    private PracticeAutonomyPolicy() {}

    public static boolean delivers(
            ObservationOrigin origin, @Nullable PracticeAutonomy autonomy, FeedbackChannel channel) {
        if (!origin.delivers(channel)) {
            return false;
        }
        return autonomy != null && autonomy.delivers(channel);
    }
}
