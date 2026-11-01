package de.tum.cit.aet.hephaestus.agent.mentor.loop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.mentor.context.TurnAttempt;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.mentor.MentorSituation;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ConversationSituationTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    MentorSituation situations;

    @Mock
    AccountDirectory accounts;

    @Mock
    AccountIdentityQuery identities;

    @Mock
    GitProviderRegistry profiles;

    @Mock
    IdentityProviderRepository providers;

    @Mock
    IntegrationManifestRegistry manifests;

    @Test
    void shouldNameTheDevelopersAccountsAndTheCalendarDaysInTheirZoneInsideTheSituation() {
        // Wednesday 23:30 UTC is already Thursday in Berlin.
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T23:30:00Z"), ZoneOffset.UTC);
        ConversationSituation situation =
                new ConversationSituation(situations, accounts, identities, profiles, providers, manifests, clock);
        TurnAttempt turn = turn();
        when(situations.render(1L, turn.turnId(), BERLIN)).thenReturn("<situation>\nWorkspace facts.\n</situation>");
        IdentityProvider gitLab = provider(10L, IdentityProviderType.GITLAB);
        IdentityProvider slack = provider(20L, IdentityProviderType.SLACK);
        when(accounts.find(5L))
                .thenReturn(Optional.of(
                        new AccountDirectory.AccountView(5L, Account.Status.ACTIVE, "Ada Lovelace", null, null, true)));
        AccountIdentityQuery.IdentityView gitLabLink = new AccountIdentityQuery.IdentityView(1L, 10L, "77", "");
        AccountIdentityQuery.IdentityView slackLink = new AccountIdentityQuery.IdentityView(2L, 20L, "U77", "T1");
        when(identities.activeLinksForAccount(5L)).thenReturn(List.of(gitLabLink, slackLink));
        when(profiles.profiles(List.of(key(gitLabLink), key(slackLink))))
                .thenReturn(Map.of(key(gitLabLink), profile("ada"), key(slackLink), profile("ada.l")));
        when(providers.findById(10L)).thenReturn(Optional.of(gitLab));
        when(providers.findById(20L)).thenReturn(Optional.of(slack));
        when(manifests.displayName(any(IntegrationKind.class)))
                .thenAnswer(call -> call.getArgument(0) == IntegrationKind.SLACK ? "Slack" : "GitLab");

        String rendered = situation.render(turn, BERLIN);

        assertThat(rendered).startsWith("<situation>\nWorkspace facts.\n").endsWith("</situation>");
        assertThat(rendered)
                .contains("You are talking to Ada Lovelace. Their accounts: GitLab `ada`, Slack `ada.l`.")
                .contains("`byYou: true`")
                .contains("Calendar in Europe/Berlin (+02:00)")
                .contains("today is Thu 2026-10-08, yesterday was Wed 2026-10-07")
                .contains("This week runs Mon 2026-10-05 to Sun 2026-10-11")
                .contains("last week ran Mon 2026-09-28 to Sun 2026-10-04");
    }

    @Test
    void shouldStillGiveTheCalendarWhenTheDeveloperIsUnknown() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-12T08:00:00Z"), ZoneOffset.UTC);
        ConversationSituation situation =
                new ConversationSituation(situations, accounts, identities, profiles, providers, manifests, clock);
        TurnAttempt turn = turn();
        when(situations.render(1L, turn.turnId(), BERLIN)).thenReturn("<situation>\n</situation>");
        when(accounts.find(5L)).thenReturn(Optional.empty());

        String rendered = situation.render(turn, BERLIN);

        assertThat(rendered).doesNotContain("You are talking to").contains("today is Mon 2026-10-12");
    }

    private static TurnAttempt turn() {
        return new TurnAttempt(UUID.randomUUID(), 1L, 5L, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID());
    }

    private static IdentityProvider provider(long id, IdentityProviderType type) {
        IdentityProvider provider =
                new IdentityProvider(type, "https://" + type.name().toLowerCase(Locale.ROOT));
        provider.setId(id);
        return provider;
    }

    private static GitProviderRegistry.IdentityKey key(AccountIdentityQuery.IdentityView link) {
        return new GitProviderRegistry.IdentityKey(link.providerId(), link.providerUserKey(), link.teamKey());
    }

    private static GitProviderRegistry.IdentityProfile profile(String login) {
        return new GitProviderRegistry.IdentityProfile(login, "Ada Lovelace", null, null);
    }
}
