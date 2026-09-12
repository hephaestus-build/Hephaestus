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

        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.workspaceId()).isEqualTo(ownedWorkspace.getId());
            assertThat(view.memberId()).isEqualTo(ownedActor.getId());
        });

        assertThat(preferenceQuery.preferencesForAccount(accountId))
                .contains(new AccountPreferencesQuery.PreferencesView(true, false));

        link.setDisabledAt(Instant.now());
        identities.saveAndFlush(link);
        assertThat(query.membershipsForAccount(accountId)).isEmpty();
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

        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.workspaceId()).isEqualTo(workspace.getId());
            assertThat(view.role()).isEqualTo("OWNER");
            assertThat(view.memberId()).isEqualTo(firstLinkedActor.getId());
        });

        laterMembership.setRole(WorkspaceMembership.WorkspaceRole.MEMBER);
        memberships.saveAndFlush(laterMembership);
        assertThat(query.membershipsForAccount(accountId)).singleElement().satisfies(view -> {
            assertThat(view.role()).isEqualTo("MEMBER");
            assertThat(view.memberId()).isEqualTo(firstLinkedActor.getId());
        });
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
