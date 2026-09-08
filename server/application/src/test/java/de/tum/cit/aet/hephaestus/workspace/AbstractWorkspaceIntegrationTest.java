package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GithubLifecycleListener;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class AbstractWorkspaceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected IdentityProviderRepository gitProviderRepository;

    @Autowired
    protected WorkspaceService workspaceService;

    @Autowired
    protected GithubLifecycleListener githubLifecycleListener;

    @Autowired
    protected WorkspaceMembershipService workspaceMembershipService;

    @Autowired
    protected WorkspaceMembershipRepository workspaceMembershipRepository;

    @Autowired
    private AccountRepository fixtureAccounts;

    @Autowired
    private IdentityLinkRepository fixtureIdentities;

    private final AtomicLong userIdGenerator = new AtomicLong(50_000);

    @BeforeEach
    void resetWorkspaceDatabaseState() {
        databaseTestUtils.cleanDatabase();
    }

    protected IdentityProvider ensureGitHubProvider() {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
    }

    protected IdentityProvider ensureGitLabProvider() {
        return gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, "https://gitlab.com")
                .orElseGet(() -> gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.com")));
    }

    /** Named authentication fixtures also get their verified login identity during setup. */
    protected User persistUser(String login) {
        IdentityProvider provider = ensureGitHubProvider();
        User user = new User();
        long nativeId = userIdGenerator.incrementAndGet();
        user.setNativeId(nativeId);
        user.setProvider(provider);
        user.setLogin(login);
        user.setName("User " + login);
        user.setAvatarUrl("https://example.com/" + login + ".png");
        user.setHtmlUrl("https://github.com/" + login);
        user.setType(User.Type.USER);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        user = userRepository.save(user);
        TestUserFactory.ensureAccountForUser(fixtureAccounts, fixtureIdentities, user);
        return user;
    }

    protected Long accountId(User actor) {
        TestUserFactory.ensureAccountForUser(fixtureAccounts, fixtureIdentities, actor);
        return java.util.Objects.requireNonNull(fixtureIdentities
                .findActiveByProviderSubject(
                        java.util.Objects.requireNonNull(actor.getProvider().getId()),
                        actor.getNativeId().toString(),
                        null)
                .orElseThrow()
                .getAccount()
                .getId());
    }

    protected Workspace createWorkspace(
            String slug, String displayName, String accountLogin, AccountType accountType, User owner) {
        if (owner == null || owner.getId() == null) {
            throw new IllegalArgumentException("Owner user must be persisted before creating a workspace");
        }
        return workspaceService.createWorkspace(slug, displayName, accountLogin, accountType, owner.getId());
    }

    protected WorkspaceMembership ensureWorkspaceMembership(
            Workspace workspace, User user, WorkspaceMembership.WorkspaceRole role) {
        return workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.getId(), user.getId())
                .orElseGet(() -> workspaceMembershipService.createMembership(workspace, user.getId(), role));
    }

    protected WorkspaceMembership ensureAdminMembership(Workspace workspace) {
        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        TestUserFactory.ensureAccountForUser(fixtureAccounts, fixtureIdentities, adminUser);
        return ensureWorkspaceMembership(workspace, adminUser, WorkspaceMembership.WorkspaceRole.ADMIN);
    }

    protected WorkspaceMembership ensureOwnerMembership(Workspace workspace) {
        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        TestUserFactory.ensureAccountForUser(fixtureAccounts, fixtureIdentities, adminUser);
        return ensureWorkspaceMembership(workspace, adminUser, WorkspaceMembership.WorkspaceRole.OWNER);
    }
}
