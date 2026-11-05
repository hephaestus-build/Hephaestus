package de.tum.cit.aet.hephaestus.integration.slack.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.core.auth.spi.PeopleRegistry;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.slack.messaging.SlackMessageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipRoster;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SlackRosterIdentityResolverTest extends BaseUnitTest {
    private final GitProviderRegistry providers = mock(GitProviderRegistry.class);
    private final PeopleRegistry people = mock(PeopleRegistry.class);
    private final MembershipRoster memberships = mock(MembershipRoster.class);
    private final SlackMessageService slack = mock(SlackMessageService.class);
    private final PersonProcessingSuppression suppression = mock(PersonProcessingSuppression.class);
    private final SlackRosterIdentityResolver resolver =
            new SlackRosterIdentityResolver(providers, people, memberships, slack, suppression);

    @Test
    void humanRosterUsesTheExactNativeTeamAndKnownNameBeforeAttributingTheAccount() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(3L);
        when(slack.humanDisplayName(7L, "T1", "U1")).thenReturn(Optional.of("Ada"));
        when(people.ensureHuman(new PersonIdentity(3L, "U1", "T1"), "Ada")).thenReturn(55L);
        when(memberships.ensureMember(7L, 55L)).thenReturn(true);
        assertThat(resolver.displayName(7L, "T1", "U1")).contains("Ada");
        assertThat(resolver.humanAccountId(7L, "T1", "U1", "Ada")).contains(55L);
    }

    @Test
    void aBotOrUnavailableHumanProfileHasNoName() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(3L);
        when(slack.humanDisplayName(7L, "T1", "B1")).thenReturn(Optional.empty());
        assertThat(resolver.displayName(7L, "T1", "B1")).isEmpty();
        verify(people, never()).ensureHuman(any(), anyString());
    }

    @Test
    void aDepartedMembershipCannotRejoinThroughMessageIngestion() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(3L);
        when(people.ensureHuman(new PersonIdentity(3L, "U1", "T1"), "Ada")).thenReturn(55L);
        when(memberships.ensureMember(7L, 55L)).thenReturn(false);
        assertThat(resolver.humanAccountId(7L, "T1", "U1", "Ada")).isEmpty();
    }

    @Test
    void anErasedNativeIdentityIsNeitherLookedUpNorRecreated() {
        when(providers.resolveProviderId("SLACK", "https://slack.com")).thenReturn(3L);
        when(suppression.isSuppressed(3L, "U1", "T1")).thenReturn(true);
        assertThat(resolver.displayName(7L, "T1", "U1")).isEmpty();
        assertThat(resolver.humanAccountId(7L, "T1", "U1", "Ada")).isEmpty();
        verify(slack, never()).humanDisplayName(7L, "T1", "U1");
        verify(people, never()).ensureHuman(any(), anyString());
    }
}
