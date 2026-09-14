package de.tum.cit.aet.hephaestus.core.auth.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.account.UserPreferences;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery.PreferencesView;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class AccountPreferencesQueryTest extends BaseUnitTest {

    private static final long USER_ID = 11L;

    @Test
    void anAbsentRowUsesTheSameDeliveryDefaultAsANewRow() {
        boolean defaultWithoutRow = preferences(null).practiceFeedbackDeliveryEnabled(USER_ID);

        assertThat(defaultWithoutRow)
                .isTrue()
                .isEqualTo(new UserPreferences().isPracticeFeedbackDeliveryEnabled())
                .isEqualTo(PreferencesView.PRACTICE_FEEDBACK_DELIVERY_ENABLED_BY_DEFAULT);
    }

    @Test
    void anExplicitOptOutIsHonoured() {
        assertThat(preferences(false).practiceFeedbackDeliveryEnabled(USER_ID)).isFalse();
    }

    @Test
    void anExplicitOptInIsHonoured() {
        assertThat(preferences(true).practiceFeedbackDeliveryEnabled(USER_ID)).isTrue();
    }

    private static AccountPreferencesQuery preferences(@Nullable Boolean stored) {
        return new AccountPreferencesQuery() {
            @Override
            public Optional<PreferencesView> preferencesForAccount(Long accountId) {
                return Optional.empty();
            }

            @Override
            public Optional<PreferencesView> preferencesForUserId(long userId) {
                return stored == null ? Optional.empty() : Optional.of(new PreferencesView(true, stored));
            }
        };
    }
}
