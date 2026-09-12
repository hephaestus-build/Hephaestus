package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.ConsentSource;
import de.tum.cit.aet.hephaestus.core.auth.spi.ResearchParticipationCommand;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AccountPreferencesServiceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private UserPreferencesRepository preferences;

    @Autowired
    private ResearchParticipationCommand participation;

    @Autowired
    private AccountPreferencesQueryAdapter query;

    @Test
    void shouldChangeAndReadOnlyVerifiedActorsConsentWhenProvidersShareALogin() {
        var github =
                providers.save(new IdentityProvider(IdentityProviderType.GITHUB, "https://consent-github.example.com"));
        var gitlab =
                providers.save(new IdentityProvider(IdentityProviderType.GITLAB, "https://consent-gitlab.example.com"));
        var namesake = users.save(TestUserFactory.createUser(123L, "shared-consent-login", github));
        var actor = users.save(TestUserFactory.createUser(123L, "shared-consent-login", gitlab));
        var namesakeId = namesake.getId();
        var actorId = actor.getId();
        assertNotNull(namesakeId);
        assertNotNull(actorId);
        preferences.save(new UserPreferences(namesake));
        preferences.save(new UserPreferences(actor));

        participation.setForUserId(actorId, true, ConsentSource.SLACK_APP_HOME);

        assertThat(query.preferencesForUserId(actorId))
                .get()
                .extracting(AccountPreferencesQuery.PreferencesView::participateInResearch)
                .isEqualTo(true);
        assertThat(query.preferencesForUserId(namesakeId))
                .get()
                .extracting(AccountPreferencesQuery.PreferencesView::participateInResearch)
                .isEqualTo(false);

        participation.setForUserId(actorId, false, ConsentSource.SLACK_APP_HOME);
        assertThat(query.preferencesForUserId(actorId))
                .get()
                .extracting(AccountPreferencesQuery.PreferencesView::participateInResearch)
                .isEqualTo(false);
    }
}
