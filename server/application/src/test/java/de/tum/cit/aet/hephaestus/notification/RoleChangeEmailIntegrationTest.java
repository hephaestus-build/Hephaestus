package de.tum.cit.aet.hephaestus.notification;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.action.ActionExecutor;
import de.tum.cit.aet.hephaestus.core.action.ActionPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.action.ActionResult;
import de.tum.cit.aet.hephaestus.core.auth.AccountService;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.event.RoleChangedEvent;
import de.tum.cit.aet.hephaestus.core.privacy.CorePrivacyPersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.notification.email.CapturingJavaMailSender;
import de.tum.cit.aet.hephaestus.notification.email.CapturingMailTestConfiguration;
import de.tum.cit.aet.hephaestus.notification.email.EmailNotice.Prepared;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.dto.AssignRoleRequestDTO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * A committed role change queues one email per recipient on the outbox. The emails' text is proven by
 * {@code RoleChangeEmailListenerTest}; this class proves who gets one, through the real transactions.
 */
@Import(CapturingMailTestConfiguration.class)
@TestPropertySource(properties = "hephaestus.email.from=noreply@hephaestus.test")
class RoleChangeEmailIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private AccountService accountService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private ActionPersonDataCatalog actionErasure;

    @Autowired
    private CorePrivacyPersonDataCatalog corePrivacy;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RoleChangeEmailListener roleNotices;

    @Autowired
    private ActionExecutor executor;

    @Autowired
    private CapturingJavaMailSender mailSender;

    @Test
    void shouldClaimAndSendTheRoleChangedVariantThroughTheRegisteredEmailAdapter() throws Exception {
        long actor = verified(
                Objects.requireNonNull(persistInstanceAdmin("Admission actor").getId()));
        long target = verified(
                Objects.requireNonNull(persistAccount("Admission recipient").getId()));
        accountService.adminSetRole(target, "APP_ADMIN", actor);
        UUID action = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT id FROM agent_action WHERE kind='EMAIL' AND payload ->> 'kind'='ROLE_CHANGED'"
                        + " AND subject_account_id=?",
                UUID.class,
                target));

        assertThat(executor.runNow(action))
                .hasValueSatisfying(result -> assertThat(result).isInstanceOf(ActionResult.Done.class));

        assertThat(jdbc.queryForObject("SELECT state FROM agent_action WHERE id=?", String.class, action))
                .isEqualTo("DONE");
        assertThat(mailSender.sent()).isNotEmpty();
        assertThat(mailSender.sent().getLast().getAllRecipients())
                .extracting(Object::toString)
                .containsExactly(
                        accountRepository.findById(target).orElseThrow().getPrimaryEmail());
    }

    @Test
    @WithMentorUser
    void shouldQueueAnEmailForTheAffectedPersonAndEachOtherOwnerWhenAWorkspaceAdminIsGrantedAndRemoved() {
        User owner = persistUser("role-notice-owner");
        Workspace workspace = createWorkspace("role-notice", "Role notice", "role-notice", AccountType.ORG, owner);
        User other = persistUser("role-notice-second-owner");
        workspaceMembershipService.createMembership(workspace, accountId(other), WorkspaceRole.OWNER);
        User mentor = persistUser("mentor");
        ensureMembership(workspace, mentor, WorkspaceRole.ADMIN);
        User target = persistUser("role-notice-target");
        workspaceMembershipService.createMembership(workspace, accountId(target), WorkspaceRole.MEMBER);
        for (User user : List.of(owner, other, mentor, target)) verified(accountId(user));

        assign(workspace, target, WorkspaceRole.ADMIN);
        assign(workspace, target, WorkspaceRole.ADMIN);

        assertThat(recipients(accountId(target)))
                .as("one email each; the actor and a no-op replay add none")
                .containsExactlyInAnyOrder(accountId(target), accountId(owner), accountId(other));

        webTestClient
                .delete()
                .uri("/workspaces/{slug}/members/{accountId}", workspace.getWorkspaceSlug(), accountId(target))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(recipients(accountId(target)))
                .as("the removal is a change of its own")
                .containsExactlyInAnyOrder(
                        accountId(target),
                        accountId(owner),
                        accountId(other),
                        accountId(target),
                        accountId(owner),
                        accountId(other));

        assign(workspace, target, WorkspaceRole.ADMIN);

        assertThat(recipients(accountId(target)))
                .as("a former admin who rejoins as an admin is granted the role again")
                .hasSize(9);
    }

    @Test
    @WithMentorUser
    void shouldQueueNothingWhenOnlyAMemberJoinsOrLeaves() {
        User owner = persistUser("member-notice-owner");
        Workspace workspace =
                createWorkspace("member-notice", "Member notice", "member-notice", AccountType.ORG, owner);
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.ADMIN);
        User joining = persistUser("member-notice-joining");
        verified(accountId(owner));
        verified(accountId(joining));

        assign(workspace, joining, WorkspaceRole.MEMBER);
        webTestClient
                .delete()
                .uri("/workspaces/{slug}/members/{accountId}", workspace.getWorkspaceSlug(), accountId(joining))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isNoContent()
                .expectBody(Void.class);

        assertThat(recipients(accountId(joining))).isEmpty();
    }

    @Test
    @WithMentorUser
    void shouldQueueOneEmailWhenAResetRoleChangesAtTheNextSyncButNoneForTheResetItself() {
        User owner = persistUser("reset-notice-owner");
        Workspace workspace = createWorkspace("reset-notice", "Reset notice", "reset-notice", AccountType.ORG, owner);
        ensureMembership(workspace, persistUser("mentor"), WorkspaceRole.ADMIN);
        User target = persistUser("reset-notice-target");
        workspaceMembershipService.createMembership(workspace, accountId(target), WorkspaceRole.MEMBER);
        verified(accountId(owner));
        verified(accountId(target));

        assign(workspace, target, WorkspaceRole.ADMIN);
        assertThat(recipients(accountId(target))).containsExactlyInAnyOrder(accountId(target), accountId(owner));

        webTestClient
                .delete()
                .uri(
                        "/workspaces/{slug}/members/{accountId}/role-override",
                        workspace.getWorkspaceSlug(),
                        accountId(target))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
        assertThat(recipients(accountId(target)))
                .as("the reset leaves the role as it is until the next sync")
                .hasSize(2);

        workspaceMembershipService.syncWorkspaceMembers(
                workspace, Map.of(Objects.requireNonNull(target.getId()), WorkspaceRole.MEMBER));

        assertThat(recipients(accountId(target)))
                .as("the sync that applies the provider's role notifies once")
                .containsExactlyInAnyOrder(accountId(target), accountId(owner), accountId(target), accountId(owner));
    }

    @Test
    void shouldQueueAnEmailWhenTheProviderSyncChangesARoleButNotWhenItAddsAMember() {
        User owner = persistUser("sync-notice-owner");
        Workspace workspace = createWorkspace("sync-notice", "Sync notice", "sync-notice", AccountType.ORG, owner);
        User member = persistUser("sync-notice-member");
        workspaceMembershipService.createMembership(workspace, accountId(member), WorkspaceRole.MEMBER);
        User newcomer = persistUser("sync-notice-newcomer");
        verified(accountId(owner));
        verified(accountId(member));
        verified(accountId(newcomer));

        workspaceMembershipService.syncWorkspaceMembers(
                workspace,
                Map.of(
                        Objects.requireNonNull(member.getId()), WorkspaceRole.ADMIN,
                        Objects.requireNonNull(newcomer.getId()), WorkspaceRole.ADMIN));

        assertThat(recipients(accountId(member))).containsExactlyInAnyOrder(accountId(member), accountId(owner));
        assertThat(recipients(accountId(newcomer)))
                .as("the connection's initial roster import stays silent for a newly discovered member")
                .isEmpty();

        User later = persistUser("sync-notice-later");
        verified(accountId(later));
        workspaceMembershipService.syncWorkspaceMembers(
                workspace,
                Map.of(
                        Objects.requireNonNull(member.getId()), WorkspaceRole.ADMIN,
                        Objects.requireNonNull(newcomer.getId()), WorkspaceRole.ADMIN,
                        Objects.requireNonNull(later.getId()), WorkspaceRole.ADMIN));
        assertThat(recipients(accountId(later))).containsExactlyInAnyOrder(accountId(later), accountId(owner));

        workspaceMembershipService.syncWorkspaceMembers(
                workspace, Map.of(Objects.requireNonNull(newcomer.getId()), WorkspaceRole.ADMIN));

        assertThat(recipients(accountId(member)))
                .as("an admin who left the provider leaves the workspace")
                .containsExactlyInAnyOrder(accountId(member), accountId(owner), accountId(member), accountId(owner));

        workspaceMembershipService.applyProviderGrant(
                workspace, Objects.requireNonNull(member.getId()), WorkspaceRole.ADMIN);

        assertThat(recipients(accountId(member)))
                .as("a returning admin is granted the role again")
                .containsExactlyInAnyOrder(
                        accountId(member),
                        accountId(owner),
                        accountId(member),
                        accountId(owner),
                        accountId(member),
                        accountId(owner));
    }

    @Test
    @WithAdminUser
    void shouldNotifyAnInstanceAdminWhoAssignsThemselvesAWorkspaceAdminRole() {
        User owner = persistUser("self-grant-owner");
        Workspace workspace = createWorkspace("self-grant", "Self grant", "self-grant", AccountType.ORG, owner);
        User actor = persistUser("admin");
        // The instance administrator may assign the role before they belong to this workspace.
        var actorAccount = accountRepository.findById(accountId(actor)).orElseThrow();
        actorAccount.setAppRole(Account.AppRole.APP_ADMIN);
        accountRepository.save(actorAccount);
        verified(accountId(owner));
        verified(accountId(actor));

        assign(workspace, actor, WorkspaceRole.ADMIN);

        assertThat(recipients(accountId(actor))).containsExactlyInAnyOrder(accountId(actor), accountId(owner));
    }

    @Test
    void shouldQueueAnEmailForTheAffectedPersonAndTheOtherInstanceAdminsButNotTheActor() {
        long actor = verified(
                Objects.requireNonNull(persistInstanceAdmin("Instance actor").getId()));
        long other = verified(
                Objects.requireNonNull(persistInstanceAdmin("Instance witness").getId()));
        long target = verified(
                Objects.requireNonNull(persistAccount("Instance candidate").getId()));

        accountService.adminSetRole(target, "APP_ADMIN", actor);
        accountService.adminSetRole(target, "APP_ADMIN", actor);

        assertThat(recipients(target)).containsExactlyInAnyOrder(target, other);

        accountService.adminSetRole(target, "USER", actor);

        assertThat(recipients(target)).containsExactlyInAnyOrder(target, other, target, other);
    }

    @Test
    void shouldTellASelfElevatedInstanceAdminAndTheOtherAdministrator() {
        long self =
                verified(Objects.requireNonNull(persistAccount("Self promoted").getId()));
        long other = verified(
                Objects.requireNonNull(persistInstanceAdmin("Bystander").getId()));

        accountService.adminSetRole(self, "APP_ADMIN", self);

        assertThat(recipients(self)).containsExactlyInAnyOrder(self, other);
    }

    @Test
    void shouldQueueNothingWhenTheRoleChangeRollsBack() {
        long actor = verified(
                Objects.requireNonNull(persistInstanceAdmin("Rolled back actor").getId()));
        long target = verified(
                Objects.requireNonNull(persistAccount("Rolled back candidate").getId()));

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            accountService.adminSetRole(target, "APP_ADMIN", actor);
            tx.setRollbackOnly();
        });

        assertThat(recipients(target)).isEmpty();
    }

    @Test
    void shouldQueueSeparateChangesAtTheSameTimeOnceEachWhenTheirPublicationsAreReplayed() {
        long actor = verified(
                Objects.requireNonNull(persistInstanceAdmin("Replay actor").getId()));
        long other = verified(
                Objects.requireNonNull(persistInstanceAdmin("Replay witness").getId()));
        long target = verified(
                Objects.requireNonNull(persistAccount("Replay candidate").getId()));
        Instant when = Instant.now();
        var grant = RoleChangedEvent.instanceAdmin(target, false, true, actor, when);
        var removal = RoleChangedEvent.instanceAdmin(target, true, false, actor, when);

        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.publishEvent(grant);
            events.publishEvent(removal);
        });
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            events.publishEvent(grant);
            events.publishEvent(removal);
        });

        assertThat(recipients(target)).containsExactlyInAnyOrder(target, other, target, other);
    }

    private void assign(Workspace workspace, User user, WorkspaceRole role) {
        webTestClient
                .post()
                .uri("/workspaces/{slug}/members/assign", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new AssignRoleRequestDTO(Long.toString(accountId(user)), role))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(Void.class);
    }

    /** Gives the account an active status and a verified address, as a person who signed in has. */
    private long verified(long accountId) {
        Account account = accountRepository.findById(accountId).orElseThrow();
        account.setStatus(Account.Status.ACTIVE);
        account.setPrimaryEmail("role-" + UUID.randomUUID() + "@hephaestus.test");
        account.setPrimaryEmailVerifiedAt(Instant.now());
        accountRepository.save(account);
        return accountId;
    }

    /** The recipient of every role-change email queued about one account, one entry per email. */
    private List<Long> recipients(long affectedAccountId) {
        return jdbc.query("""
                SELECT subject_account_id FROM agent_action
                WHERE kind = 'EMAIL' AND payload ->> 'kind' = 'ROLE_CHANGED'
                  AND (payload -> 'event' -> 'change' ->> 'accountId')::bigint = ?
                """, (row, index) -> row.getLong(1), affectedAccountId);
    }

    @Test
    void shouldKeepOtherPeoplesNoticesWhenTheActorIsErasedAndDeleteTheErasedRecipientsCopies() {
        long actor = verified(
                Objects.requireNonNull(persistInstanceAdmin("Role actor").getId()));
        long witness = verified(
                Objects.requireNonNull(persistInstanceAdmin("Role witness").getId()));
        long target =
                verified(Objects.requireNonNull(persistAccount("Role target").getId()));
        var change = RoleChangedEvent.instanceAdmin(target, false, true, actor, Instant.now());
        var ownPublication = UUID.randomUUID();
        var witnessPublication = UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(change));
        for (var entry :
                Map.of(ownPublication, target, witnessPublication, witness).entrySet()) {
            jdbc.update(
                    """
                    INSERT INTO event_publication(id,publication_date,listener_id,serialized_event,event_type)
                    VALUES (?,now(),'role-notice-test',?,'de.tum.cit.aet.hephaestus.notification.RoleChangeEmailRequested')
                    """,
                    entry.getKey(),
                    mapper.writeValueAsString(new RoleChangeEmailRequested(change, entry.getValue())));
        }

        eraseNoticeReferences(actor);

        assertThat(preparedPublication(ownPublication).message().text())
                .contains("An administrator made this change")
                .doesNotContain("Role actor");
        var payloads = jdbc.query(
                """
                SELECT payload -> 'event' FROM agent_action WHERE kind='EMAIL'
                  AND payload #>> '{event,change,changeId}' = ?
                """,
                (row, index) ->
                        mapper.readValue(Objects.requireNonNull(row.getString(1)), RoleChangeEmailRequested.class),
                change.changeId().toString());
        assertThat(payloads).hasSize(2).allSatisfy(notice -> {
            assertThat(notice.change().actorAccountId()).isNull();
            assertThat(notice.change().actorAnonymized()).isTrue();
            assertThat(roleNotices.prepare(notice)).isInstanceOf(Prepared.Send.class);
        });

        eraseNoticeReferences(target);

        assertThat(jdbc.queryForList("SELECT id FROM event_publication WHERE id=?", ownPublication))
                .isEmpty();
        assertThat(preparedPublication(witnessPublication).message().text())
                .contains("An account became an instance admin")
                .doesNotContain("Role target");
        assertThat(jdbc.queryForList("""
                SELECT id FROM agent_action WHERE subject_account_id=?
                  AND payload #>> '{event,change,changeId}' = ?
                """, target, change.changeId().toString())).isEmpty();
        assertThat(jdbc.queryForObject(
                        """
                SELECT payload #>> '{event,change,accountId}' FROM agent_action
                WHERE subject_account_id=? AND payload #>> '{event,change,changeId}' = ?
                """, String.class, witness, change.changeId().toString()))
                .isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldNameBlockedActorsAndAffectedAccountsInAnotherAdministratorsNotice(boolean blockActor) {
        long actor = verified(Objects.requireNonNull(
                persistInstanceAdmin("Blocked role actor").getId()));
        long target = verified(Objects.requireNonNull(
                persistInstanceAdmin("Blocked role subject").getId()));
        long witness = verified(
                Objects.requireNonNull(persistInstanceAdmin("Role witness").getId()));
        var blocked = accountRepository.findById(blockActor ? actor : target).orElseThrow();
        blocked.setStatus(Account.Status.BLOCKED);
        accountRepository.save(blocked);
        var change = RoleChangedEvent.instanceAdmin(target, true, false, actor, Instant.now());

        new TransactionTemplate(transactions).executeWithoutResult(tx -> events.publishEvent(change));

        String serialized = Objects.requireNonNull(jdbc.queryForObject(
                """
                SELECT payload -> 'event' FROM agent_action
                WHERE subject_account_id=? AND payload #>> '{event,change,changeId}'=?
                """, String.class, witness, change.changeId().toString()));
        var notice = mapper.readValue(serialized, RoleChangeEmailRequested.class);
        assertThat(notice.change().actorAccountId()).isEqualTo(actor);
        assertThat(notice.change().accountId()).isEqualTo(target);
        assertThat(notice.change().actorAnonymized()).isFalse();
        assertThat(((Prepared.Send) roleNotices.prepare(notice)).message().text())
                .contains(
                        "Blocked role actor made this change",
                        "Blocked role subject’s instance admin role was removed");
        if (blockActor) assertThat(recipients(target)).containsExactlyInAnyOrder(target, witness);
        else assertThat(recipients(target)).containsExactly(witness);
    }

    private void eraseNoticeReferences(long accountId) {
        var scope = new PersonScope(accountId, List.of(), List.of());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            actionErasure.eraseAccount(accountId);
            var publications = corePrivacy.contributors().stream()
                    .filter(store -> store.store().equals("event_publication"))
                    .findFirst()
                    .orElseThrow();
            publications.erase(publications.select(scope), scope);
        });
    }

    private Prepared.Send preparedPublication(UUID id) {
        String serialized = Objects.requireNonNull(
                jdbc.queryForObject("SELECT serialized_event FROM event_publication WHERE id=?", String.class, id));
        return (Prepared.Send) roleNotices.prepare(mapper.readValue(serialized, RoleChangeEmailRequested.class));
    }
}
