package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry.ActorProfile;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** An account's SCM actors come from one read of its links, whatever the number of links. */
class AccountActorsIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private GitProviderRegistry providers;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private QueryPlans plans;

    @Test
    void shouldResolveEnabledLinksInLinkOrderWhenTheAccountHasSeveral() {
        Account account = persistAccount("actors-owner");
        User gitlab = gitLabUser("actors-gitlab");
        User github = persistUser("actors-github");
        User disabled = persistUser("actors-disabled");
        link(account, disabled, "", Instant.now());
        link(account, gitlab, "", null);
        link(account, github, "", null);
        link(account, github, "T123", null);

        var read = reads.measure(() -> providers.accountActors(Objects.requireNonNull(account.getId())));

        assertThat(read.value()).extracting(ActorProfile::login).containsExactly("actors-gitlab", "actors-github");
        assertThat(read.value().getFirst().providerType()).isEqualTo("GITLAB");
        assertThat(read.value().getFirst().providerUserKey())
                .isEqualTo(gitlab.getNativeId().toString());
        assertThat(read.cost().statements()).isEqualTo(1);
        assertThat(plans.fullScans(read.sql())).isEmpty();
    }

    @Test
    void shouldResolveNoActorWhenTheLinkIsABotOrHasNoNumericSubject() {
        User bot = persistUser("actors-bot");
        bot.setType(User.Type.BOT);
        userRepository.save(bot);
        Account botOwner = persistAccount("actors-bot-owner");
        link(botOwner, bot, "", null);
        Account textOwner = persistAccount("actors-text-owner");
        linkSubject(textOwner, Objects.requireNonNull(ensureGitHubProvider().getId()), "not-a-number");

        assertThat(providers.accountActors(Objects.requireNonNull(botOwner.getId())))
                .isEmpty();
        assertThat(providers.accountActors(Objects.requireNonNull(textOwner.getId())))
                .isEmpty();
    }

    private User gitLabUser(String login) {
        User user = persistUser(login);
        user.setProvider(ensureGitLabProvider());
        return userRepository.save(user);
    }

    private void link(Account account, User user, String teamKey, @Nullable Instant disabledAt) {
        Identity identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(user.getProvider().getId()));
        identity.setProviderUserKey(user.getNativeId().toString());
        identity.setTeamKey(teamKey);
        identity.setDisabledAt(disabledAt);
        identities.saveAndFlush(identity);
    }

    private void linkSubject(Account account, long providerId, String subject) {
        Identity identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(providerId);
        identity.setProviderUserKey(subject);
        identities.saveAndFlush(identity);
    }
}
