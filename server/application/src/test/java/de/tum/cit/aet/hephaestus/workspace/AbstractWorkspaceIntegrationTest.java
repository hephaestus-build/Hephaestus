package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.GitProviderRegistry;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.lifecycle.GitHubLifecycleListener;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    protected GitHubLifecycleListener githubLifecycleListener;

    @Autowired
    protected MembershipService workspaceMembershipService;

    @Autowired
    protected MembershipRepository workspaceMembershipRepository;

    @Autowired
    protected AccountRepository accountRepository;

    @Autowired
    private IdentityRepository fixtureIdentities;

    @Autowired
    private GitProviderRegistry fixtureActors;

    @Autowired
    private ConnectionRepository activityConnections;

    @Autowired
    private ConnectionService activityConnectionService;

    private final AtomicLong userIdGenerator = new AtomicLong(50_000);

    @BeforeEach
    void resetWorkspaceDatabaseState() {
        databaseTestUtils.cleanDatabase();
    }

    protected void connectActivitySource(Workspace workspace, IdentityProvider provider) {
        ConnectionConfig config = switch (provider.getType()) {
            case GITHUB ->
                new ConnectionConfig.GitHubAppConfig(
                        workspace.getId(), workspace.getAccountLogin(), provider.getServerUrl(), Set.of());
            case GITLAB -> new ConnectionConfig.GitLabConfig(provider.getServerUrl(), null, null, Set.of(), null);
            default -> throw new IllegalArgumentException("Activity source must be GitHub or GitLab");
        };
        IntegrationKind kind =
                provider.getType() == IdentityProviderType.GITLAB ? IntegrationKind.GITLAB : IntegrationKind.GITHUB;
        Connection connection = activityConnections.saveAndFlush(
                new Connection(workspace, kind, Long.toString(workspace.getId()), config));
        activityConnectionService.transition(
                connection,
                new ConnectionService.TransitionRequest(
                        IntegrationState.ACTIVE,
                        "TEST_CONNECTED",
                        "SYSTEM",
                        "activity-fixture",
                        UUID.randomUUID().toString(),
                        "Source connected"));
    }

    protected GitProviderRegistry.ActorProfile actor(User user) {
        return fixtureActors.actor(Objects.requireNonNull(user.getId())).orElseThrow();
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

    protected Account persistAccount(String displayName) {
        return accountRepository.save(new Account(displayName));
    }

    protected Account persistInstanceAdmin(String displayName) {
        Account account = new Account(displayName);
        account.setAppRole(Account.AppRole.APP_ADMIN);
        return accountRepository.save(account);
    }

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
        if (Set.of("admin", "mentor", "testuser").contains(login)) {
            TestUserFactory.ensureAccountForUser(accountRepository, fixtureIdentities, user);
        }
        return user;
    }

    protected Workspace createWorkspace(
            String slug, String displayName, String accountLogin, AccountType accountType, User owner) {
        if (owner == null || owner.getId() == null) {
            throw new IllegalArgumentException("Owner user must be persisted before creating a workspace");
        }
        return workspaceService.createWorkspace(slug, displayName, accountLogin, accountType, owner.getId());
    }

    protected long accountId(User user) {
        return workspaceMembershipService.accountIdForUser(Objects.requireNonNull(user.getId()));
    }

    protected Membership ensureMembership(Workspace workspace, User user, Membership.WorkspaceRole role) {
        return workspaceMembershipRepository
                .findByWorkspace_IdAndId_AccountId(workspace.getId(), accountId(user))
                .orElseGet(() -> workspaceMembershipService.createMembership(workspace, accountId(user), role));
    }

    protected Membership ensureAdminMembership(Workspace workspace) {
        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        TestUserFactory.ensureAccountForUser(accountRepository, fixtureIdentities, adminUser);
        return ensureMembership(workspace, adminUser, Membership.WorkspaceRole.ADMIN);
    }

    protected Membership ensureOwnerMembership(Workspace workspace) {
        User adminUser = TestUserFactory.ensureUser(userRepository, "admin", 3L, ensureGitHubProvider());
        TestUserFactory.ensureAccountForUser(accountRepository, fixtureIdentities, adminUser);
        return ensureMembership(workspace, adminUser, Membership.WorkspaceRole.OWNER);
    }
}
