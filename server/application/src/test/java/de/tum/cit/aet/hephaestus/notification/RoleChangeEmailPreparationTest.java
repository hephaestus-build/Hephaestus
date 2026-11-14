package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent.Role;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.context.ApplicationEventPublisher;

class RoleChangeEmailPreparationTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-10T14:05:00Z");
    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final List<Object> published = new ArrayList<>();
    private final ApplicationEventPublisher events = published::add;
    private final RoleChangeEmailPreparation preparation =
            new RoleChangeEmailPreparation(contacts, memberships, events);

    @Test
    void shouldTellTheAffectedPersonAndEveryOtherOwnerButNotTheActor() {
        when(memberships.ownerAccountIds(7L)).thenReturn(List.of(3L, 4L, 5L));
        var change = new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, Role.MEMBER, Role.OWNER, 3L, NOW, false);

        preparation.on(change);

        assertThat(published)
                .containsExactly(
                        new RoleChangeEmailRequested(change, 2L),
                        new RoleChangeEmailRequested(change, 4L),
                        new RoleChangeEmailRequested(change, 5L));
    }

    @Test
    void shouldTellTheOtherInstanceAdminsAboutAnInstanceAdminRemoval() {
        when(contacts.activeVerifiedAdministratorIds()).thenReturn(List.of(3L, 6L));
        var change = RoleChangedEvent.instanceAdmin(2L, true, false, 3L, NOW);

        preparation.on(change);

        assertThat(published)
                .containsExactly(new RoleChangeEmailRequested(change, 2L), new RoleChangeEmailRequested(change, 6L));
    }

    @ParameterizedTest
    @CsvSource(
            nullValues = "none",
            value = {
                "none,MEMBER,false",
                "MEMBER,none,false",
                "MEMBER,MEMBER,false",
                "ADMIN,ADMIN,false",
                "none,ADMIN,true",
                "MEMBER,ADMIN,true",
                "ADMIN,MEMBER,true",
                "ADMIN,none,true",
                "ADMIN,OWNER,true",
                "OWNER,ADMIN,true",
                "none,OWNER,true"
            })
    void shouldNotifyOnlyWhenAdminOrOwnerIsGrantedOrRemoved(
            @Nullable Role previous, @Nullable Role role, boolean notifies) {
        assertThat(RoleChangeEmailPreparation.notifies(
                        new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, previous, role, 3L, NOW, false)))
                .isEqualTo(notifies);
    }

    @Test
    void shouldNotifyProviderRoleChangesIncludingAReturningAdmin() {
        assertThat(RoleChangeEmailPreparation.notifies(
                        new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, null, Role.ADMIN, null, NOW, false)))
                .as("a returning admin is granted the role again")
                .isTrue();
        assertThat(RoleChangeEmailPreparation.notifies(
                        new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, Role.MEMBER, Role.ADMIN, null, NOW, false)))
                .isTrue();
        assertThat(RoleChangeEmailPreparation.notifies(
                        new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, Role.ADMIN, null, null, NOW, false)))
                .isTrue();
        assertThat(RoleChangeEmailPreparation.notifies(RoleChangedEvent.instanceAdmin(2L, false, true, null, NOW)))
                .as("the bootstrap admin setting is no provider sync")
                .isTrue();
    }

    @Test
    void shouldSendNothingWhenAPersonLowersTheirOwnRole() {
        preparation.on(new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, Role.OWNER, Role.ADMIN, 2L, NOW, false));

        assertThat(published).isEmpty();
        verify(memberships, never()).ownerAccountIds(anyLong());
    }

    @Test
    void shouldTellTheAffectedPersonAndOtherOwnersAboutSelfElevation() {
        when(memberships.ownerAccountIds(7L)).thenReturn(List.of(4L));
        var change = new RoleChangedEvent(UUID.randomUUID(), 2L, 7L, Role.MEMBER, Role.ADMIN, 2L, NOW, false);
        preparation.on(change);
        assertThat(published)
                .containsExactly(new RoleChangeEmailRequested(change, 2L), new RoleChangeEmailRequested(change, 4L));
    }

    @Test
    void shouldTellTheAffectedPersonAndOtherAdminsAboutSelfElevationToInstanceAdmin() {
        when(contacts.activeVerifiedAdministratorIds()).thenReturn(List.of(2L, 6L));
        var change = RoleChangedEvent.instanceAdmin(2L, false, true, 2L, NOW);
        preparation.on(change);
        assertThat(published)
                .containsExactly(new RoleChangeEmailRequested(change, 2L), new RoleChangeEmailRequested(change, 6L));
    }
}
