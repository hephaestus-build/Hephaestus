package de.tum.cit.aet.hephaestus.agent.mentor.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountIdentityQuery.IdentityLinkView;
import de.tum.cit.aet.hephaestus.core.runtime.hub.auth.TurnJwt;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.framework.IntegrationManifestRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorSituation;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class HephSituationTest extends BaseUnitTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    @Mock
    MentorSituation situations;

    @Mock
    UserRepository users;

    @Mock
    AccountIdentityQuery identities;

    @Mock
    IdentityProviderRepository providers;

    @Mock
    IntegrationManifestRegistry manifests;

    @Test
    void shouldNameTheDevelopersAccountsAndTheCalendarDaysInTheirZoneInsideTheSituation() {
        // Wednesday 23:30 UTC is already Thursday in Berlin.
        Clock clock = Clock.fixed(Instant.parse("2026-10-07T23:30:00Z"), ZoneOffset.UTC);
        HephSituation situation = new HephSituation(situations, users, identities, providers, manifests, clock);
        TurnJwt turn = turn();
        when(situations.render(1L, turn.turnId(), BERLIN)).thenReturn("<situation>\nWorkspace facts.\n</situation>");
        IdentityProvider gitLab = provider(10L, IdentityProviderType.GITLAB);
        IdentityProvider slack = provider(20L, IdentityProviderType.SLACK);
        User developer = new User();
        developer.setLogin("ada");
        developer.setName("Ada Lovelace");
        developer.setNativeId(77L);
        developer.setProvider(gitLab);
        when(users.findById(2L)).thenReturn(Optional.of(developer));
        when(identities.resolveActiveAccountId(10L, "77", null)).thenReturn(Optional.of(5L));
        when(identities.activeLinksForAccount(5L)).thenReturn(List.of(link(10L, "ada"), link(20L, "ada.l")));
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
        HephSituation situation = new HephSituation(situations, users, identities, providers, manifests, clock);
        TurnJwt turn = turn();
        when(situations.render(1L, turn.turnId(), BERLIN)).thenReturn("<situation>\n</situation>");
        when(users.findById(2L)).thenReturn(Optional.empty());

        String rendered = situation.render(turn, BERLIN);

        assertThat(rendered).doesNotContain("You are talking to").contains("today is Mon 2026-10-12");
    }

    private static TurnJwt turn() {
        Instant now = Instant.parse("2026-10-07T23:30:00Z");
        return new TurnJwt(UUID.randomUUID(), 1L, 2L, UUID.randomUUID(), 1, Set.of(), "jti", now, now.plusSeconds(60));
    }

    private static IdentityProvider provider(long id, IdentityProviderType type) {
        IdentityProvider provider =
                new IdentityProvider(type, "https://" + type.name().toLowerCase(Locale.ROOT));
        provider.setId(id);
        return provider;
    }

    private static IdentityLinkView link(long providerId, @Nullable String username) {
        return new IdentityLinkView(1L, providerId, "subject", username, null, null, null, null, null);
    }
}
