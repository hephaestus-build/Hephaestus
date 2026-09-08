package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.account.AccountPreferencesQueryAdapter;
import de.tum.cit.aet.hephaestus.account.UserPreferences;
import de.tum.cit.aet.hephaestus.account.UserPreferencesRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class AccountWorkspaceMembershipQueryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private AccountWorkspaceMembershipQuery query;

    @Autowired
    private AccountPreferencesQueryAdapter preferenceQuery;

    @Autowired
    private UserPreferencesRepository preferences;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityLinkRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private UserRepository users;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private WorkspaceMembershipRepository memberships;

    @Autowired
    private WorkspaceAccountMembershipRepository accountMemberships;

    @Autowired
    private WorkspaceAccountMembershipSync accountSync;

    @Autowired
    private WorkspaceAccountMembershipErasure erasure;

    @Test
    void shouldReturnOnlyVerifiedActorsWhenUsernamesAreReassignedOrSharedAcrossProviders() {
        var github = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.GITHUB, "https://identity-test-github.example.com"));
        var gitlab = provider("https://identity-test-gitlab.example.com");
        var account = accounts.saveAndFlush(new Account("Identity owner"));
        var accountId = account.getId();
        assertNotNull(accountId);
        var githubId = github.getId();
        assertNotNull(githubId);
        var ownedActor = actor(github, 123L, "renamed-login");
        var reclaimedLogin = actor(github, 456L, "original-login");
        var namesake = actor(gitlab, 123L, "original-login");
        var ownedWorkspace = membership(ownedActor, "identity-owned");
        membership(reclaimedLogin, "identity-reclaimed");
        membership(namesake, "identity-namesake");
        var ownedPreferences = new UserPreferences(ownedActor);
        ownedPreferences.setParticipateInResearch(true);
        ownedPreferences.setPracticeFeedbackDeliveryEnabled(false);
        preferences.saveAndFlush(ownedPreferences);
        preferences.saveAndFlush(new UserPreferences(reclaimedLogin));
        preferences.saveAndFlush(new UserPreferences(namesake));

        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(githubId);
        link.setSubject("123");
        link.setUsernameAtSignup("original-login");

        link.setExternalActorId(reclaimedLogin.getId());
        identities.saveAndFlush(link);
        accountMembership(ownedWorkspace, accountId);

        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.workspaceId()).isEqualTo(ownedWorkspace.getId());
            assertThat(view.memberId()).isEqualTo(ownedActor.getId());
        });

        assertThat(preferenceQuery.preferencesForAccount(accountId))
                .contains(new AccountPreferencesQuery.PreferencesView(true, false));

        link.setDisabledAt(Instant.now());
        identities.saveAndFlush(link);
        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.workspaceId()).isEqualTo(ownedWorkspace.getId());
            assertThat(view.memberId()).isNull();
        });
        assertThat(preferenceQuery.preferencesForAccount(accountId)).isEmpty();
    }

    @Test
    void shouldNotTurnANonScmDisplayNameIntoWorkspaceMembership() {
        var scm = provider("https://identity-scm.example.com");
        var outline = providers.saveAndFlush(
                new IdentityProvider(IdentityProviderType.OUTLINE, "https://identity-wiki.example.com"));
        var member = actor(scm, 321L, "shared-name");
        membership(member, "identity-display-name");
        var account = accounts.saveAndFlush(new Account("Unrelated wiki author"));
        var accountId = account.getId();
        assertNotNull(accountId);
        var outlineId = outline.getId();
        assertNotNull(outlineId);
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(outlineId);
        link.setSubject("outline-user-uuid");
        link.setTeamId("outline-team");
        link.setUsernameAtSignup("shared-name");
        identities.saveAndFlush(link);

        assertThat(query.membershipsForAccount(accountId)).isEmpty();
    }

    @Test
    void shouldKeepTheFirstLinkedActorWhenAnotherIdentityIsLinkedAndRolesChange() {
        var firstProvider = provider("https://identity-first.example.com");
        var secondProvider = provider("https://identity-second.example.com");
        var olderActor = actor(firstProvider, 1L, "older-actor");
        var firstLinkedActor = actor(secondProvider, 2L, "first-linked");
        var workspace = membership(firstLinkedActor, "identity-union");
        var laterMembership = new WorkspaceMembership();
        laterMembership.setWorkspace(workspace);
        laterMembership.setUser(olderActor);
        laterMembership = memberships.saveAndFlush(laterMembership);
        var account = accounts.saveAndFlush(new Account("Both identities"));
        var accountId = account.getId();
        assertNotNull(accountId);
        var firstLink = new IdentityLink();
        firstLink.setAccount(account);
        firstLink.setProviderId(Objects.requireNonNull(secondProvider.getId()));
        firstLink.setSubject(firstLinkedActor.getNativeId().toString());
        identities.saveAndFlush(firstLink);
        var humanMembership = accountMembership(workspace, accountId);
        assertThat(query.membershipsForAccount(accountId))
                .singleElement()
                .extracting(AccountWorkspaceMembershipQuery.WorkspaceMembershipView::memberId)
                .isEqualTo(firstLinkedActor.getId());

        var laterLink = new IdentityLink();
        laterLink.setAccount(account);
        laterLink.setProviderId(Objects.requireNonNull(firstProvider.getId()));
        laterLink.setSubject(olderActor.getNativeId().toString());
        identities.saveAndFlush(laterLink);
        laterMembership.setRole(WorkspaceMembership.WorkspaceRole.OWNER);
        memberships.saveAndFlush(laterMembership);
        assertThat(query.membershipsForAccount(accountId))
                .singleElement()
                .extracting(AccountWorkspaceMembershipQuery.WorkspaceMembershipView::role)
                .isEqualTo("MEMBER");
        humanMembership.setRole(WorkspaceMembership.WorkspaceRole.OWNER);
        accountMemberships.saveAndFlush(humanMembership);

        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.workspaceId()).isEqualTo(workspace.getId());
            assertThat(view.role()).isEqualTo("OWNER");
            assertThat(view.memberId()).isEqualTo(firstLinkedActor.getId());
        });

        humanMembership.setRole(WorkspaceMembership.WorkspaceRole.MEMBER);
        accountMemberships.saveAndFlush(humanMembership);
        laterMembership.setRole(WorkspaceMembership.WorkspaceRole.MEMBER);
        memberships.saveAndFlush(laterMembership);
        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.role()).isEqualTo("MEMBER");
            assertThat(view.memberId()).isEqualTo(firstLinkedActor.getId());
        });
    }

    @Test
    void shouldReconcileOnlyScmManagedAccessAndNeverRestoreASuspension() {
        var provider = provider("https://identity-sync.example.com");
        var actor = actor(provider, 42L, "sync-member");
        var workspace = membership(actor, "identity-sync");
        var account = accounts.saveAndFlush(new Account("Sync member"));
        var accountId = Objects.requireNonNull(account.getId());
        var link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(provider.getId()));
        link.setSubject("42");
        identities.saveAndFlush(link);
        var desired = java.util.Map.of(Objects.requireNonNull(actor.getId()), WorkspaceMembership.WorkspaceRole.OWNER);

        accountSync.synchronize(workspace, desired);
        var access = accountMemberships
                .findByWorkspace_IdAndAccountId(workspace.getId(), accountId)
                .orElseThrow();
        assertThat(access.getRole()).isEqualTo(WorkspaceMembership.WorkspaceRole.ADMIN);
        assertThat(access.getSource()).isEqualTo(WorkspaceAccountMembership.Source.SCM);
        access.setSuspended(true);
        accountMemberships.saveAndFlush(access);
        accountSync.synchronize(workspace, desired);
        assertThat(accountMemberships
                        .findByWorkspace_IdAndAccountId(workspace.getId(), accountId)
                        .orElseThrow()
                        .isSuspended())
                .isTrue();
        accountSync.synchronize(workspace, java.util.Map.of());
        assertThat(accountMemberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId))
                .isPresent();

        access.setSuspended(false);
        access.setSource(WorkspaceAccountMembership.Source.MANUAL);
        accountMemberships.saveAndFlush(access);
        accountSync.synchronize(workspace, java.util.Map.of());
        assertThat(accountMemberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId))
                .isPresent();
        access.setSource(WorkspaceAccountMembership.Source.SCM);
        accountMemberships.saveAndFlush(access);
        accountSync.synchronize(workspace, java.util.Map.of());
        assertThat(accountMemberships.findByWorkspace_IdAndAccountId(workspace.getId(), accountId))
                .isEmpty();
    }

    @Test
    void shouldEraseOnlyTheRequestedWorkspaceOrAccountMemberships() {
        var provider = provider("https://identity-erasure.example.com");
        var actor = actor(provider, 12L, "erasure-member");
        var first = membership(actor, "erasure-first");
        var second = membership(actor, "erasure-second");
        var account = accounts.saveAndFlush(new Account("Erased member"));
        var other = accounts.saveAndFlush(new Account("Remaining member"));
        var id = Objects.requireNonNull(account.getId());
        var otherId = Objects.requireNonNull(other.getId());
        accountMembership(first, id);
        accountMembership(second, id);
        accountMembership(second, otherId);

        erasure.deleteWorkspaceData(first.getId());
        assertThat(accountMemberships.findByWorkspace_IdAndAccountId(first.getId(), id))
                .isEmpty();
        assertThat(accountMemberships.findByWorkspace_IdAndAccountId(second.getId(), id))
                .isPresent();
        erasure.eraseAccount(id);
        assertThat(accountMemberships.findActiveByAccountId(id)).isEmpty();
        assertThat(accountMemberships.findByWorkspace_IdAndAccountId(second.getId(), otherId))
                .isPresent();
    }

    private WorkspaceAccountMembership accountMembership(Workspace workspace, Long accountId) {
        var membership = new WorkspaceAccountMembership();
        membership.setWorkspace(workspace);
        membership.setAccountId(accountId);
        return accountMemberships.saveAndFlush(membership);
    }

    private IdentityProvider provider(String url) {
        return providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, url));
    }

    private User actor(IdentityProvider provider, long subject, String login) {
        var actor = new User();
        actor.setProvider(provider);
        actor.setNativeId(subject);
        actor.setLogin(login);
        actor.setAvatarUrl("");
        actor.setHtmlUrl(provider.getServerUrl() + "/" + login);
        actor.setEmail("");
        actor.setType(User.Type.USER);
        return users.saveAndFlush(actor);
    }

    private Workspace membership(User actor, String slug) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug(slug);
        workspace.setDisplayName(slug);
        workspace.setAccountLogin(slug);
        workspace.setAccountType(AccountType.ORG);
        workspace = workspaces.saveAndFlush(workspace);
        var membership = new WorkspaceMembership();
        membership.setWorkspace(workspace);
        membership.setUser(actor);
        memberships.saveAndFlush(membership);
        return workspace;
    }
}
