package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountContactQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import de.tum.cit.aet.hephaestus.notification.email.EmailDeliveryResult.Outcome;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Address;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Prepared;
import de.tum.cit.aet.hephaestus.notification.email.EmailTestSupport;
import de.tum.cit.aet.hephaestus.notification.email.Emails;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RoleChangeEmailListenerTest extends BaseUnitTest {
    private static final Instant NOW = Instant.parse("2026-11-10T14:05:00Z");
    private static final long AFFECTED = 2L;
    private static final long ACTOR = 3L;
    private static final long OWNER = 4L;
    private static final long WORKSPACE = 7L;

    private final AccountContactQuery contacts = mock(AccountContactQuery.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final AccountMembershipQuery memberships = mock(AccountMembershipQuery.class);
    private final RoleChangeEmailListener listener = new RoleChangeEmailListener(
            contacts,
            accounts,
            memberships,
            EmailTestSupport.renderer(),
            mock(Emails.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void activeAccountsInOneWorkspace() {
        account(AFFECTED, "Ada Lovelace", Account.Status.ACTIVE);
        account(ACTOR, "Grace Hopper", Account.Status.ACTIVE);
        when(memberships.workspace(WORKSPACE))
                .thenReturn(Optional.of(new AccountMembershipQuery.WorkspaceView("delivery-team", "Delivery team")));
        when(memberships.ownerAccountIds(WORKSPACE)).thenReturn(List.of(OWNER));
        when(contacts.activeVerifiedPrimaryEmail(AFFECTED)).thenReturn(Optional.of("ada@example.org"));
        when(contacts.activeVerifiedPrimaryEmail(OWNER)).thenReturn(Optional.of("owner@example.org"));
        when(accounts.timeZone(OWNER)).thenReturn(Optional.of(ZoneId.of("Europe/Berlin")));
    }

    @Test
    void shouldTellAnotherOwnerWhoChangedWhichRoleWhenAndWhereToReviewIt() {
        var sent = send(workspaceChange(RoleChangedEvent.Role.MEMBER, RoleChangedEvent.Role.OWNER), OWNER);

        assertThat(sent.message().to()).isEqualTo("owner@example.org");
        assertThat(sent.message().subject()).isEqualTo("An admin role changed in Hephaestus");
        assertThat(sent.message().text())
                .contains("Ada Lovelace’s role in the workspace “Delivery team” changed from member to owner.")
                .contains("Grace Hopper made this change on 10 November 2026 at 15:05 CET.")
                .contains("Review the workspace audit log: " + EmailTestSupport.WEBAPP_URL
                        + "/w/delivery-team/admin/audit");
        assertThat(sent.message().unsubscribeUrl()).isNull();
    }

    @Test
    void shouldTellTheAffectedPersonInTheSecondPerson() {
        var sent = send(workspaceChange(null, RoleChangedEvent.Role.ADMIN), AFFECTED);

        assertThat(sent.message().text())
                .contains("You became an admin of the workspace “Delivery team”.")
                .contains("Grace Hopper made this change on 10 November 2026 at 14:05 UTC.")
                .contains("If you did not expect this change, contact an owner of the workspace.")
                .contains("/w/delivery-team/admin/audit");
    }

    @Test
    void shouldNotLinkARemovedPersonToTheWorkspaceTheyLeft() {
        var sent = send(workspaceChange(RoleChangedEvent.Role.ADMIN, null), AFFECTED);

        assertThat(sent.message().text())
                .contains("You were removed from the workspace “Delivery team”, where you were an admin.")
                .contains("Open Hephaestus: " + EmailTestSupport.WEBAPP_URL)
                .doesNotContain("/w/delivery-team");
    }

    @Test
    void shouldLinkADemotedMemberToTheWorkspaceRatherThanItsAuditLog() {
        var sent = send(workspaceChange(RoleChangedEvent.Role.ADMIN, RoleChangedEvent.Role.MEMBER), AFFECTED);

        assertThat(sent.message().text())
                .contains("Your role in the workspace “Delivery team” changed from admin to member.")
                .contains("Open the workspace: " + EmailTestSupport.WEBAPP_URL + "/w/delivery-team\n")
                .doesNotContain("/admin/audit");
    }

    @Test
    void shouldTellOtherInstanceAdminsAboutAnInstanceAdminGrantedByTheBootstrapSetting() {
        when(contacts.activeVerifiedAdministratorEmail(OWNER)).thenReturn(Optional.of("owner@example.org"));
        var change = RoleChangedEvent.instanceAdmin(AFFECTED, false, true, null, NOW);

        var sent = send(change, OWNER);

        assertThat(sent.message().text())
                .contains("Ada Lovelace became an instance admin.")
                .contains("The instance’s bootstrap admin setting made this change on")
                .contains("Review the instance audit log: " + EmailTestSupport.WEBAPP_URL + "/admin/audit");
    }

    @Test
    void shouldNameTheProviderSyncWhenNoPersonMadeTheChange() {
        var change = new RoleChangedEvent(
                UUID.randomUUID(),
                AFFECTED,
                WORKSPACE,
                RoleChangedEvent.Role.MEMBER,
                RoleChangedEvent.Role.ADMIN,
                null,
                NOW,
                false);

        assertThat(send(change, OWNER).message().text())
                .contains("The sync with GitHub or GitLab made this change on 10 November 2026 at 15:05 CET.");
    }

    @Test
    void shouldNotSendToARecipientWhoNoLongerOwnsTheWorkspace() {
        when(memberships.ownerAccountIds(WORKSPACE)).thenReturn(List.of());

        assertThat(listener.prepare(new RoleChangeEmailRequested(
                        workspaceChange(RoleChangedEvent.Role.MEMBER, RoleChangedEvent.Role.ADMIN), OWNER)))
                .isEqualTo(Prepared.withhold(Outcome.NO_RECIPIENT));
    }

    @Test
    void shouldTellTheAffectedPersonTheirInstanceAdminRoleWasRemoved() {
        var sent = send(RoleChangedEvent.instanceAdmin(AFFECTED, true, false, ACTOR, NOW), AFFECTED);

        assertThat(sent.message().text())
                .contains("Your instance admin role was removed.")
                .contains("If you did not expect this change, contact another instance admin.")
                .contains("Open Hephaestus: " + EmailTestSupport.WEBAPP_URL + "\n");
    }

    @Test
    void shouldNotSendToAFormerInstanceAdmin() {
        when(contacts.activeVerifiedAdministratorEmail(OWNER)).thenReturn(Optional.empty());

        assertThat(listener.prepare(new RoleChangeEmailRequested(
                        RoleChangedEvent.instanceAdmin(AFFECTED, true, false, ACTOR, NOW), OWNER)))
                .isEqualTo(Prepared.withhold(Outcome.NO_RECIPIENT));
    }

    @ParameterizedTest
    @EnumSource(
            value = Account.Status.class,
            names = {"DELETING", "DELETED"})
    void shouldNeverNameAnAccountThatIsBeingErased(Account.Status status) {
        var change = workspaceChange(RoleChangedEvent.Role.MEMBER, RoleChangedEvent.Role.ADMIN);
        account(ACTOR, "Grace Hopper", status);
        assertThat(send(change, OWNER).message().text())
                .contains("An administrator made this change")
                .doesNotContain("Grace Hopper");

        account(ACTOR, "Grace Hopper", Account.Status.ACTIVE);
        account(AFFECTED, "Ada Lovelace", status);
        assertThat(send(change, OWNER).message().text())
                .contains("An account’s role")
                .doesNotContain("Ada Lovelace");
    }

    @Test
    void shouldKeepAnAnonymizedActorsNoticeAndItsRecipientAndChangeKey() {
        var change = workspaceChange(RoleChangedEvent.Role.MEMBER, RoleChangedEvent.Role.ADMIN);
        var anonymous = change.anonymize(ACTOR);
        assertThat(anonymous.actorAccountId()).isNull();
        assertThat(listener.address(new RoleChangeEmailRequested(anonymous, OWNER)))
                .isEqualTo(listener.address(new RoleChangeEmailRequested(change, OWNER)));
        assertThat(send(anonymous, OWNER).message().text()).contains("An administrator made this change");
    }

    @Test
    void shouldWithholdAChangeInAPurgedWorkspace() {
        when(memberships.workspace(WORKSPACE)).thenReturn(Optional.empty());

        assertThat(listener.prepare(new RoleChangeEmailRequested(
                        workspaceChange(RoleChangedEvent.Role.MEMBER, RoleChangedEvent.Role.ADMIN), OWNER)))
                .isEqualTo(Prepared.withhold(Outcome.EXPIRED));
    }

    @Test
    void shouldExpireAWeekAfterTheChange() {
        var change = new RoleChangedEvent(
                UUID.randomUUID(),
                AFFECTED,
                WORKSPACE,
                null,
                RoleChangedEvent.Role.ADMIN,
                ACTOR,
                NOW.minus(Duration.ofDays(7)),
                false);

        assertThat(listener.prepare(new RoleChangeEmailRequested(change, OWNER)))
                .isEqualTo(Prepared.withhold(Outcome.EXPIRED));
    }

    @Test
    void shouldAddressOneEmailPerChangeAndRecipient() {
        var change = workspaceChange(null, RoleChangedEvent.Role.ADMIN);

        assertThat(listener.address(new RoleChangeEmailRequested(change, OWNER)))
                .isEqualTo(new Address(change.changeId() + ":4", OWNER, WORKSPACE, NOW.plus(Duration.ofDays(7))));
        var instanceChange = RoleChangedEvent.instanceAdmin(AFFECTED, false, true, ACTOR, NOW);
        assertThat(listener.address(new RoleChangeEmailRequested(instanceChange, OWNER))
                        .key())
                .isEqualTo(instanceChange.changeId() + ":4");
    }

    @Test
    void shouldUseSeparateKeysForChangesAtTheSameTimeAndForDifferentRecipients() {
        var first = RoleChangedEvent.instanceAdmin(AFFECTED, false, true, ACTOR, NOW);
        var second = RoleChangedEvent.instanceAdmin(AFFECTED, true, false, ACTOR, NOW);
        var request = new RoleChangeEmailRequested(first, OWNER);

        assertThat(listener.address(new RoleChangeEmailRequested(second, OWNER)).key())
                .isNotEqualTo(listener.address(request).key());
        assertThat(listener.address(new RoleChangeEmailRequested(first, AFFECTED))
                        .key())
                .isNotEqualTo(listener.address(request).key());
    }

    private Prepared.Send send(RoleChangedEvent change, long recipient) {
        return (Prepared.Send) listener.prepare(new RoleChangeEmailRequested(change, recipient));
    }

    private static RoleChangedEvent workspaceChange(
            RoleChangedEvent.@Nullable Role previous, RoleChangedEvent.@Nullable Role role) {
        return new RoleChangedEvent(UUID.randomUUID(), AFFECTED, WORKSPACE, previous, role, ACTOR, NOW, false);
    }

    private void account(long id, String name, Account.Status status) {
        when(accounts.find(id))
                .thenReturn(Optional.of(new AccountDirectory.AccountView(id, status, name, null, null, true)));
    }
}
