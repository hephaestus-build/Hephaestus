package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.util.Optional;

public interface AccountPreferencesQuery {
    /** Preferences for the first-linked SCM actor; the account-level projection contains one preference set. */
    Optional<PreferencesView> preferencesForAccount(Long accountId);

    Optional<PreferencesView> preferencesForUserId(long userId);

    default boolean practiceFeedbackDeliveryEnabled(long userId) {
        return preferencesForUserId(userId)
                .map(PreferencesView::practiceFeedbackDeliveryEnabled)
                .orElse(PreferencesView.PRACTICE_FEEDBACK_DELIVERY_ENABLED_BY_DEFAULT);
    }

    record PreferencesView(boolean participateInResearch, boolean practiceFeedbackDeliveryEnabled) {
        public static final boolean PRACTICE_FEEDBACK_DELIVERY_ENABLED_BY_DEFAULT = true;
    }
}
