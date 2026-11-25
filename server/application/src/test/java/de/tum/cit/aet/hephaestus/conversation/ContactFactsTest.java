package de.tum.cit.aet.hephaestus.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Attention;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Boundary;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Qualification;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Reason;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Route;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Routing;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Transport;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Unprompted;
import de.tum.cit.aet.hephaestus.conversation.ContactPolicy.Update;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.privacy.spi.OwnerScope;
import de.tum.cit.aet.hephaestus.core.settings.spi.SilentModeQuery;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceStartedMessages;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ContactFactsTest {

    private final WorkspaceStartedMessages workspaces = mock(WorkspaceStartedMessages.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final AccountMeterRepository meters = mock(AccountMeterRepository.class);

    @Test
    void shouldStopStartedMessagesEverywhereWhenTheInstanceTurnsThemOff() {
        when(workspaces.startedMessagesEnabled(anyLong())).thenReturn(true);
        var facts = facts(false);

        assertThat(facts.controls().instanceAllowsStartedMessages()).isFalse();
        assertThat(facts.controls().workspaceAllowsStartedMessages()).isTrue();
        assertThat(ContactPolicy.decide(facts).message().reason()).isEqualTo(Reason.STARTED_MESSAGES_DISABLED);
    }

    @Test
    void shouldKeepQuietHoursInTheDefaultTimeZoneForAPersonWhoChoseNone() {
        var facts = facts(true);

        assertThat(facts.now().getZone()).isEqualTo(AccountDirectory.DEFAULT_TIME_ZONE);
        assertThat(facts.controls().quietHours()).isEqualTo(ContactPolicy.QuietHours.DEFAULT);
    }

    private ContactPolicy.Facts facts(boolean instanceAllows) {
        when(accounts.timeZone(anyLong())).thenReturn(Optional.empty());
        when(meters.findReading(anyLong(), any())).thenReturn(Optional.empty());
        var contactFacts = new ContactFacts(
                new ContactProperties(instanceAllows),
                workspaces,
                mock(SilentModeQuery.class),
                accounts,
                mock(CheckInRepository.class),
                meters,
                mock(OwnerScope.class));
        return contactFacts.facts(
                1L,
                2L,
                new ContactFacts.Occasion(
                        new Unprompted(Update.NEW_ADMITTED_PROBLEM, Qualification.CURRENT, false, false, null, null),
                        Boundary.DISPATCH,
                        new Routing(Set.of(Route.APP), null, Route.APP, Transport.PUSH),
                        new Attention(false, null)),
                Instant.parse("2026-11-25T12:00:00Z"));
    }
}
