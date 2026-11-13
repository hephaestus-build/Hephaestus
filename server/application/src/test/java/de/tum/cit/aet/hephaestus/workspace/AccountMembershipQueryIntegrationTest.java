package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.account.AccountPreferencesQueryAdapter;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestriction;
import de.tum.cit.aet.hephaestus.core.privacy.IdentityRestrictionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewCoverageService;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AccountMembershipQueryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private AccountMembershipQuery query;

    @Autowired
    private AccountPreferencesQueryAdapter preferences;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private MembershipRepository memberships;

    @Autowired
    private PracticeReviewCoverageService coverage;

    @Autowired
    private MembershipReviewSelection selections;

    @Autowired
    private IdentityRestrictionRepository restrictions;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldAuthorizeOnlyActiveVisibleMembersInTheRequestedWorkspace() {
        var account = accounts.saveAndFlush(Account.unclaimed("History member"));
        var member = membership(account, "history-membership");
        long workspaceId = member.getWorkspace().getId();
        long accountId = Objects.requireNonNull(account.getId());
        assertThat(query.isActiveVisibleMember(workspaceId, accountId)).isTrue();
        assertThat(query.isActiveVisibleMember(workspaceId + 1, accountId)).isFalse();
        member.setHidden(true);
        memberships.flush();
        assertThat(query.isActiveVisibleMember(workspaceId, accountId)).isFalse();
        member.setHidden(false);
        member.setLeftAt(Instant.now());
        memberships.flush();
        assertThat(query.isActiveVisibleMember(workspaceId, accountId)).isFalse();
        member.setLeftAt(null);
        account.claim();
        accounts.flush();
        assertThat(query.isActiveVisibleMember(workspaceId, accountId)).isTrue();
        account.block();
        accounts.flush();
        assertThat(query.isActiveVisibleMember(workspaceId, accountId)).isFalse();
    }

    @Test
    void shouldApplyExactErasureSuppressionToBothEligibilityReaders() {
        var erased = accounts.saveAndFlush(new Account("Erased identity"));
        var member = membership(erased, "suppressed-eligibility");
        var visible = accounts.saveAndFlush(new Account("Public visibility only"));
        addMember(member.getWorkspace(), visible, Membership.WorkspaceRole.MEMBER, null);
        var foreign = accounts.saveAndFlush(new Account("Different provider origin"));
        addMember(member.getWorkspace(), foreign, Membership.WorkspaceRole.MEMBER, null);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> providers.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var alias = providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com/"));
        var other =
                providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITHUB, "https://git.example.org"));
        for (var account : List.of(erased, visible, foreign)) {
            var identity = new Identity();
            identity.setAccount(account);
            identity.setProviderId(Objects.requireNonNull(account.equals(foreign) ? other.getId() : provider.getId()));
            identity.setProviderUserKey(account.equals(visible) ? "visible-key" : "erased-key");
            identities.saveAndFlush(identity);
        }
        var erasedRestriction = new IdentityRestriction();
        erasedRestriction.setProviderId(Objects.requireNonNull(alias.getId()));
        erasedRestriction.setProviderUserKey("erased-key");
        erasedRestriction.setKind(IdentityRestriction.Kind.ERASED);
        restrictions.saveAndFlush(erasedRestriction);
        var hiddenRestriction = new IdentityRestriction();
        hiddenRestriction.setProviderId(Objects.requireNonNull(provider.getId()));
        hiddenRestriction.setProviderUserKey("visible-key");
        hiddenRestriction.setKind(IdentityRestriction.Kind.HIDE_PUBLIC);
        restrictions.saveAndFlush(hiddenRestriction);
        List<Long> ids = List.of(
                Objects.requireNonNull(erased.getId()),
                Objects.requireNonNull(visible.getId()),
                Objects.requireNonNull(foreign.getId()));
        assertThat(selections.eligibleAccountIds(member.getWorkspace().getId(), ids))
                .containsExactlyInAnyOrder(visible.getId(), foreign.getId());
        assertThat(coverage.eligibleAccountIds(member.getWorkspace().getId()))
                .containsExactlyInAnyOrder(visible.getId(), foreign.getId());
    }

    @Test
    void shouldKeepLoadedSelectionCurrentWhenReviewScopeIsReplaced() {
        var account = accounts.saveAndFlush(new Account("Selection cache"));
        var member = membership(account, "selection-cache");
        var foreign = membership(account, "selection-cache-foreign");
        foreign.setReviewSelected(true);
        memberships.flush();
        long accountId = Objects.requireNonNull(account.getId());
        selections.replaceSelection(member.getWorkspace().getId(), List.of(accountId));
        assertThat(member.isReviewSelected()).isTrue();
        assertThat(foreign.isReviewSelected()).isTrue();
        assertThat(coverage.scope(member.getWorkspace()).accountIds()).containsExactly(accountId);
        selections.replaceSelection(member.getWorkspace().getId(), List.of());
        assertThat(member.isReviewSelected()).isFalse();
        var memberId = member.getId();
        var foreignId = foreign.getId();
        entityManager.flush();
        entityManager.clear();
        assertThat(memberships.findById(memberId))
                .get()
                .extracting(Membership::isReviewSelected)
                .isEqualTo(false);
        assertThat(memberships.findById(foreignId))
                .get()
                .extracting(Membership::isReviewSelected)
                .isEqualTo(true);
    }

    @Test
    void shouldReturnOneMembershipWhenAnAccountHasTwoProviderIdentities() {
        var account = accounts.saveAndFlush(new Account("Two identities"));
        account.setFeedbackDeliveryEnabled(false);
        var accountId = Objects.requireNonNull(account.getId());
        link(account, IdentityProviderType.GITHUB, "https://identity-github.example.com", "123");
        link(account, IdentityProviderType.GITLAB, "https://identity-gitlab.example.com", "456");
        var member = membership(account, "identity-owner");
        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.workspaceId()).isEqualTo(member.getWorkspace().getId());
        });
        assertThat(preferences.preferencesForAccount(accountId))
                .get()
                .extracting(view -> view.practiceFeedbackDeliveryEnabled())
                .isEqualTo(false);
    }

    @Test
    void shouldExcludeLeftMembershipWhenAccountMembershipsAreRead() {
        var account = accounts.saveAndFlush(new Account("Former member"));
        var member = membership(account, "identity-left");
        member.setLeftAt(Instant.now());
        memberships.saveAndFlush(member);
        assertThat(query.membershipsForAccount(Objects.requireNonNull(account.getId())))
                .isEmpty();
        assertThat(memberships.findById(member.getId())).isPresent();
    }

    @Test
    void shouldPageOnlyCurrentViewableAccountsInDisplayNameOrder() {
        var first = accounts.saveAndFlush(new Account("Alpha developer"));
        var firstMember = membership(first, "bounded-account-page");
        var workspace = firstMember.getWorkspace();
        var second = accounts.saveAndFlush(new Account("Bravo developer"));
        var third = accounts.saveAndFlush(new Account("Charlie developer"));
        var deleting = accounts.saveAndFlush(new Account("Deleted developer"));
        deleting.startDeletion(Instant.now());
        accounts.saveAndFlush(deleting);
        var former = accounts.saveAndFlush(new Account("Former developer"));
        addMember(workspace, second, Membership.WorkspaceRole.MEMBER, null);
        addMember(workspace, third, Membership.WorkspaceRole.MEMBER, null);
        addMember(workspace, deleting, Membership.WorkspaceRole.MEMBER, null);
        addMember(workspace, former, Membership.WorkspaceRole.MEMBER, Instant.now());
        membership(accounts.saveAndFlush(new Account("Foreign developer")), "foreign-bounded-page");
        var page = memberships.findViewableAccountIds(workspace.getId(), PageRequest.of(1, 1));
        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getContent()).containsExactly(second.getId());
    }

    @Test
    void shouldRequireActiveCurrentAdministratorMembershipInTheRequestedWorkspace() {
        var active = accounts.saveAndFlush(new Account("Active administrator"));
        var member = membership(active, "bounded-administrators");
        var workspace = member.getWorkspace();
        member.setRole(Membership.WorkspaceRole.ADMIN);
        memberships.saveAndFlush(member);
        var suspended = accounts.saveAndFlush(new Account("Suspended administrator"));
        suspended.block();
        accounts.saveAndFlush(suspended);
        addMember(workspace, suspended, Membership.WorkspaceRole.OWNER, null);
        var former = accounts.saveAndFlush(new Account("Former administrator"));
        addMember(workspace, former, Membership.WorkspaceRole.ADMIN, Instant.now());
        long id = Objects.requireNonNull(active.getId());
        assertThat(query.administratorAccountIds(workspace.getId())).containsExactly(id);
        assertThat(query.isAdministrator(workspace.getId(), id)).isTrue();
        assertThat(query.isAdministrator(workspace.getId(), Objects.requireNonNull(suspended.getId())))
                .isFalse();
        assertThat(query.isAdministrator(workspace.getId(), Objects.requireNonNull(former.getId())))
                .isFalse();
        assertThat(query.isAdministrator(Long.MAX_VALUE, id)).isFalse();
    }

    @Test
    void shouldSelectOnlyCurrentAccountsWithAnEnabledIdentityOnTheRequestedProvider() {
        var human = accounts.saveAndFlush(new Account("Eligible human"));
        var member = membership(human, "batch-review-eligibility");
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://batch-review.example.com"));
        var identity = new Identity();
        identity.setAccount(human);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("batch-review-human");
        identities.saveAndFlush(identity);
        var unclaimed = accounts.saveAndFlush(Account.unclaimed("Unclaimed human"));
        addMember(member.getWorkspace(), unclaimed, Membership.WorkspaceRole.MEMBER, null);
        long humanId = Objects.requireNonNull(human.getId());
        long unclaimedId = Objects.requireNonNull(unclaimed.getId());
        var ids = List.of(humanId, unclaimedId);
        assertThat(memberships.findEligibleAccountIds(member.getWorkspace().getId(), ids, false, provider.getId()))
                .containsExactly(human.getId());
        assertThat(memberships.findEligibleAccountIds(member.getWorkspace().getId(), ids, false, null))
                .containsExactlyInAnyOrderElementsOf(ids);
        identity.setDisabledAt(Instant.now());
        identities.saveAndFlush(identity);
        assertThat(memberships.findEligibleAccountIds(member.getWorkspace().getId(), ids, false, provider.getId()))
                .isEmpty();
    }

    @Test
    void shouldExcludeHiddenAndNoAiMembersFromBothEligibilityReaders() {
        var visible = accounts.saveAndFlush(new Account("Visible eligible"));
        var member = membership(visible, "shared-eligibility");
        var hidden = accounts.saveAndFlush(new Account("Hidden developer"));
        addMember(member.getWorkspace(), hidden, Membership.WorkspaceRole.MEMBER, null);
        memberships
                .findByWorkspace_IdAndId_AccountId(
                        member.getWorkspace().getId(), Objects.requireNonNull(hidden.getId()))
                .orElseThrow()
                .setHidden(true);
        var noAi = new Account("No AI developer");
        noAi.setAiChoice(AiChoice.NO_AI);
        noAi.setAiChoiceAt(Instant.now());
        accounts.saveAndFlush(noAi);
        addMember(member.getWorkspace(), noAi, Membership.WorkspaceRole.MEMBER, null);
        List<Long> ids = List.of(
                Objects.requireNonNull(visible.getId()),
                Objects.requireNonNull(hidden.getId()),
                Objects.requireNonNull(noAi.getId()));
        assertThat(selections.eligibleAccountIds(member.getWorkspace().getId(), ids))
                .containsExactly(visible.getId());
        assertThat(coverage.eligibleAccountIds(member.getWorkspace().getId())).containsExactly(visible.getId());
    }

    @Test
    void shouldRequireTheConnectedProviderIdentityThroughBothEligibilityReaders() {
        var linked = accounts.saveAndFlush(new Account("Linked developer"));
        var member = membership(linked, "connected-eligibility");
        var workspace = member.getWorkspace();
        var unlinked = accounts.saveAndFlush(new Account("Unlinked developer"));
        addMember(workspace, unlinked, Membership.WorkspaceRole.MEMBER, null);
        var provider = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://eligibility.example.com"));
        var identity = new Identity();
        identity.setAccount(linked);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey("123");
        identities.saveAndFlush(identity);
        var connection = connections.saveAndFlush(new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "eligibility",
                new ConnectionConfig.GitHubAppConfig(
                        workspace.getId(), workspace.getAccountLogin(), provider.getServerUrl(), Set.of())));
        connectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "eligibility",
                        UUID.randomUUID().toString(),
                        "Source connected"));
        List<Long> ids = List.of(Objects.requireNonNull(linked.getId()), Objects.requireNonNull(unlinked.getId()));
        assertThat(selections.eligibleAccountIds(workspace.getId(), ids)).containsExactly(linked.getId());
        assertThat(coverage.eligibleAccountIds(workspace.getId())).containsExactly(linked.getId());
    }

    @Test
    void shouldIncludeThenExcludeTheSelectedAccountWhenReviewCoverageChanges() {
        var human = accounts.saveAndFlush(new Account("Selected human"));
        var member = membership(human, "selected-account-coverage");
        var workspace = member.getWorkspace();
        long accountId = Objects.requireNonNull(human.getId());
        coverage.replace(
                workspace,
                new WorkspaceReviewScope(ReviewScopeMode.ALL, ReviewScopeMode.SELECTED, List.of(), List.of(accountId)));
        assertThat(coverage.scope(workspace).accountIds()).containsExactly(accountId);
        assertThat(member.isReviewSelected()).isTrue();
        coverage.replace(
                workspace,
                new WorkspaceReviewScope(ReviewScopeMode.ALL, ReviewScopeMode.SELECTED, List.of(), List.of()));
        assertThat(coverage.scope(workspace).accountIds()).isEmpty();
        assertThat(member.isReviewSelected()).isFalse();
    }

    private void addMember(
            Workspace workspace, Account account, Membership.WorkspaceRole role, @Nullable Instant leftAt) {
        var member = new Membership();
        member.setWorkspace(workspace);
        member.setId(new Membership.Id(workspace.getId(), account.getId()));
        member.setRole(role);
        if (leftAt != null) member.setJoinedAt(leftAt.minusSeconds(1));
        member.setLeftAt(leftAt);
        memberships.saveAndFlush(member);
    }

    private void link(Account account, IdentityProviderType type, String origin, String key) {
        var provider = providers.saveAndFlush(new IdentityProvider(type, origin));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(key);
        identities.saveAndFlush(identity);
    }

    private Membership membership(Account account, String slug) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaces.saveAndFlush(workspace);
        var member = new Membership();
        member.setWorkspace(workspace);
        member.setId(new Membership.Id(workspace.getId(), account.getId()));
        return memberships.saveAndFlush(member);
    }
}
