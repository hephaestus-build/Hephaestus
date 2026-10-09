package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

public interface AccountPreferencesQuery {
    /** Preferences for the first-linked SCM actor; the account-level projection contains one preference set. */
    Optional<PreferencesView> preferencesForAccount(Long accountId);

    Optional<PreferencesView> preferencesForUserId(long userId);

    /** The time zone of a developer who chose none, or whose choice this runtime does not know. */
    ZoneId DEFAULT_TIME_ZONE = ZoneOffset.UTC;

    /** The time zone of the account that owns this SCM actor, when the account chose one this runtime knows. */
    Optional<ZoneId> timeZoneForUserId(long userId);

    /** The time zone the account chose, when this runtime knows it. */
    Optional<ZoneId> timeZoneForAccount(long accountId);

    default boolean practiceFeedbackDeliveryEnabled(long userId) {
        return preferencesForUserId(userId)
                .map(PreferencesView::practiceFeedbackDeliveryEnabled)
                .orElse(PreferencesView.PRACTICE_FEEDBACK_DELIVERY_ENABLED_BY_DEFAULT);
    }

    record PreferencesView(boolean participateInResearch, boolean practiceFeedbackDeliveryEnabled) {
        public static final boolean PRACTICE_FEEDBACK_DELIVERY_ENABLED_BY_DEFAULT = true;
    }
}
