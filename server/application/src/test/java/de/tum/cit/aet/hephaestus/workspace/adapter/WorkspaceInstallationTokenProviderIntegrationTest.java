package de.tum.cit.aet.hephaestus.workspace.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionFixtures;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.AuthMode;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider.ScmAccess;
import de.tum.cit.aet.hephaestus.integration.core.spi.InstallationTokenProvider.ScmConnection;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every provider call resolves its scope's access, so the resolution reads the workspace and its connection once. A
 * lookup of the connection's settings alone reads no credential, so an unreadable token cannot break it.
 */
class WorkspaceInstallationTokenProviderIntegrationTest extends AbstractWorkspaceIntegrationTest {

    @Autowired
    private InstallationTokenProvider tokens;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private CredentialBundleConverter converter;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private PlatformTransactionManager transactions;

    @Test
    void shouldResolveAPersonalAccessTokenScopeInTwoReads() {
        Workspace workspace = workspace("token-pat");
        Connection pat = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                "pat-" + workspace.getId(),
                new ConnectionConfig.GitHubPatConfig(workspace.getAccountLogin(), null, Set.of()));
        pat.setCredentials(new BearerToken("ghp-stored-token", null), converter);
        ConnectionFixtures.reachState(pat, IntegrationState.ACTIVE);
        connections.save(pat);

        var read = reads.measure(() -> tokens.access(workspace.getId()));

        assertThat(read.value())
                .isEqualTo(new ScmAccess(
                        true,
                        new ScmConnection(AuthMode.PERSONAL_ACCESS_TOKEN, null, "https://github.com"),
                        "ghp-stored-token"));
        assertThat(read.cost().statements()).isEqualTo(2);
    }

    @Test
    void shouldResolveAGitHubAppScopeWithoutReadingItsStoredCredential() {
        Workspace workspace = workspace("token-app");
        connectActivitySource(workspace, ensureGitHubProvider());
        // An App connection's stored blob is installation identity: these reads must not decrypt it at all.
        jdbc.update(
                "UPDATE connection SET credentials_encrypted = ?, credentials_key_version = 1 WHERE workspace_id = ?",
                new byte[] {1, 2, 3},
                workspace.getId());

        var read = reads.measure(() -> tokens.access(workspace.getId()));

        assertThat(read.value().connection().authMode()).isEqualTo(AuthMode.INSTALLATION_APP);
        assertThat(read.value().connection().installationId()).isEqualTo(workspace.getId());
        assertThat(read.value().personalAccessToken()).isNull();
        assertThat(read.cost().statements()).isEqualTo(2);
        assertThat(connectionService.findActiveProviderKind(workspace.getId())).contains(IntegrationKind.GITHUB);
    }

    @Test
    void shouldResolveAGitLabCallInThreeReadsAndItsServerUrlWithoutTheToken() {
        Workspace workspace = workspace("token-gitlab");
        Connection gitlab = gitLab(workspace);
        var connection = new ScmConnection(AuthMode.PERSONAL_ACCESS_TOKEN, null, "https://gitlab.example.com");

        // A GitLab call asks for the server URL, then for the token.
        var url = reads.measure(() -> tokens.connection(workspace.getId()));
        var access = reads.measure(() -> tokens.access(workspace.getId()));

        assertThat(url.value()).isEqualTo(connection);
        assertThat(url.cost().statements()).isEqualTo(1);
        assertThat(access.value()).isEqualTo(new ScmAccess(true, connection, "glpat-stored-token"));
        assertThat(access.cost().statements()).isEqualTo(2);

        // A token that cannot be decrypted leaves the server URL readable: a URL lookup reads no credential.
        unreadable(gitlab);
        assertThat(tokens.connection(workspace.getId())).isEqualTo(connection);
    }

    @Test
    void shouldNotReadTheTokenOfASuspendedWorkspace() {
        Workspace workspace = workspace("token-suspended");
        unreadable(gitLab(workspace));
        workspace.suspend();
        workspaces.saveAndFlush(workspace);

        var read = reads.measure(() -> tokens.access(workspace.getId()));

        assertThat(read.value()).isEqualTo(new ScmAccess(false, ScmConnection.NONE, null));
        assertThat(read.cost().statements()).isEqualTo(1);
    }

    @Test
    void shouldReportAnUnreadableTokenAndLeaveTheCallersTransactionUsable() {
        Workspace workspace = workspace("token-unreadable");
        unreadable(gitLab(workspace));

        // The caller's read-write transaction still commits after the failed token read.
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertThatThrownBy(() -> tokens.access(workspace.getId()))
                    .isInstanceOf(CredentialUnreadableException.class);
            assertThat(status.isRollbackOnly()).isFalse();
        });
    }

    @Test
    void shouldResolveNoConnectionWhenTheWorkspaceHasNoActiveOne() {
        Workspace workspace = workspace("token-none");
        Connection ended = gitLab(workspace);
        ConnectionFixtures.reachState(ended, IntegrationState.SUSPENDED);
        connections.saveAndFlush(ended);

        assertThat(tokens.access(workspace.getId())).isEqualTo(new ScmAccess(true, ScmConnection.NONE, null));
        assertThat(tokens.connection(workspace.getId())).isEqualTo(ScmConnection.NONE);
    }

    @Test
    void shouldKeepTheTokenOutOfTheAccessText() {
        var access = new ScmAccess(true, ScmConnection.NONE, "ghp-secret");

        assertThat(access.toString()).doesNotContain("ghp-secret");
    }

    private Connection gitLab(Workspace workspace) {
        Connection gitlab = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "gitlab-" + workspace.getId(),
                new ConnectionConfig.GitLabConfig("https://gitlab.example.com", 7L, null, Set.of(), null));
        gitlab.setCredentials(new BearerToken("glpat-stored-token", null), converter);
        ConnectionFixtures.reachState(gitlab, IntegrationState.ACTIVE);
        return connections.saveAndFlush(gitlab);
    }

    private void unreadable(Connection connection) {
        jdbc.update(
                "UPDATE connection SET credentials_encrypted = ?, credentials_key_version = 1 WHERE id = ?",
                new byte[] {1, 2, 3},
                connection.getId());
    }

    private Workspace workspace(String prefix) {
        String suffix = Long.toString(System.nanoTime());
        return createWorkspace(
                prefix + "-" + suffix,
                "Token access",
                prefix + "-org-" + suffix,
                AccountType.ORG,
                persistUser(prefix + "-owner-" + suffix));
    }
}
