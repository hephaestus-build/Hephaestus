package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.security.UserViewContextHolder;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialBundleConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import de.tum.cit.aet.hephaestus.workspace.dto.WorkspaceListItemDTO;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

class WorkspaceConnectionReadCostIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private WorkspaceQueryService queries;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private QueryPlans plans;

    private Account owner;

    @BeforeEach
    void owner() {
        owner = persistAccount("Workspace read owner");
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "workspace-reader",
                Objects.requireNonNull(owner.getId()).toString(),
                new String[0],
                "read-cost-token"));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldReadConnectionMetadataOnceWhenBuildingWorkspaceDetails() {
        var workspace = workspace(owner, 0);
        var slack = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "TREADCOST",
                new ConnectionConfig.SlackConfig("TREADCOST", "Read cost", null, Set.of()));
        slack.setState(IntegrationState.ACTIVE);
        slack = connections.saveAndFlush(slack);

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().id()).isEqualTo(workspace.getId());
        assertThat(read.value().kind()).isEqualTo("GITHUB");
        assertThat(read.value().installationId()).isEqualTo(workspace.getId() + 10000);
        assertThat(read.value().serverUrl()).isEqualTo("https://api.github.com");
        assertThat(read.value().hasPersonalAccessToken()).isFalse();
        assertThat(read.value().hasSlackToken()).isFalse();
        assertThat(read.value().slackConnectionId()).isEqualTo(slack.getId());
        assertThat(read.cost()).isEqualTo(new SqlReadMeasurement.Cost(1, 0));
        assertThat(plans.fullScans(read.sql())).isEmpty();
    }

    @Test
    void shouldReadGitLabMetadataWhenTheGitHubConnectionIsSuspended() {
        var workspace = workspace(owner, 0);
        var github = connections
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        github.setState(IntegrationState.SUSPENDED);
        connections.saveAndFlush(github);
        var gitlab = new Connection(
                workspace,
                IntegrationKind.GITLAB,
                "read-group",
                new ConnectionConfig.GitLabConfig("https://gitlab.example.test", 42L, 7L, Set.of(), null));
        gitlab.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(gitlab);
        storeUnreadableCredential(workspace, IntegrationKind.GITLAB);

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().kind()).isEqualTo("GITLAB");
        assertThat(read.value().providerType()).isEqualTo(IdentityProviderType.GITLAB);
        assertThat(read.value().serverUrl()).isEqualTo("https://gitlab.example.test");
        assertThat(read.value().installationId()).isNull();
        assertThat(read.value().installationLinkedAt()).isNull();
        assertThat(read.value().gitlabWebhookRegistered()).isTrue();
        assertThat(read.value().hasPersonalAccessToken()).isTrue();
    }

    @Test
    void shouldReportStoredCredentialsWhenTheyCannotBeDecrypted() {
        var workspace = workspace(owner, 0, true);
        assertThat(jdbc.update(
                        """
                UPDATE connection SET credentials_encrypted=?, credentials_alg=?, credentials_key_version=?
                WHERE workspace_id=? AND kind='GITHUB'
                """, new byte[] {1, 2, 3}, CredentialBundleConverter.ALGORITHM_TAG, 999, workspace.getId()))
                .isEqualTo(1);

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().hasPersonalAccessToken()).isTrue();
        assertThat(read.value().installationId()).isNull();
        assertThat(read.value().serverUrl()).isEqualTo("https://api.github.com");
    }

    @Test
    void shouldKeepReadCostsBoundedWhenTheAccessibleWorkspaceListGrows() {
        List<Long> expected = new ArrayList<>();
        for (int index = 0; index < 2; index++)
            expected.add(workspace(owner, index).getId());
        var foreign = workspace(persistAccount("Other workspace owner"), 99);

        var small = reads.measure(queries::findAccessibleWorkspaceListItems);
        assertThat(small.value()).extracting(WorkspaceListItemDTO::id).containsExactlyElementsOf(expected);
        for (int index = 2; index < 8; index++)
            expected.add(workspace(owner, index).getId());

        var larger = reads.measure(queries::findAccessibleWorkspaceListItems);

        assertThat(larger.value())
                .extracting(WorkspaceListItemDTO::id)
                .containsExactlyElementsOf(expected)
                .doesNotContain(foreign.getId());
        assertThat(larger.value())
                .extracting(WorkspaceListItemDTO::providerType)
                .containsOnly(IdentityProviderType.GITHUB);
        assertThat(larger.cost().statements())
                .as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements());
        assertThat(larger.cost().statements()).isLessThanOrEqualTo(5);
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
        assertThat(larger.cost().entities()).isEqualTo(small.cost().entities()).isLessThanOrEqualTo(1);
    }

    @Test
    void shouldExcludeMembershipsThatTheAccountLeft() {
        var active = workspace(owner, 0);
        var left = workspace(owner, 1);
        var membership = workspaceMembershipRepository
                .findByWorkspace_IdAndId_AccountId(left.getId(), Objects.requireNonNull(owner.getId()))
                .orElseThrow();
        membership.setLeftAt(Instant.now());
        workspaceMembershipRepository.saveAndFlush(membership);

        var read = reads.measure(queries::findAccessibleWorkspaceListItems);

        assertThat(read.value()).extracting(WorkspaceListItemDTO::id).containsExactly(active.getId());
    }

    @Test
    void shouldKeepInstanceAdminsLimitedToTheirMemberships() {
        var own = workspace(owner, 0);
        workspace(persistAccount("Other workspace owner"), 99);
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "workspace-reader",
                Objects.requireNonNull(owner.getId()).toString(),
                new String[] {"app_admin"},
                "read-cost-token"));

        var read = reads.measure(queries::findAccessibleWorkspaceListItems);

        assertThat(read.value()).extracting(WorkspaceListItemDTO::id).containsExactly(own.getId());
    }

    @Test
    void shouldReturnNoWorkspaceListForAnonymousCallers() {
        workspace(owner, 0);
        SecurityContextHolder.clearContext();

        assertThat(reads.measure(queries::findAccessibleWorkspaceListItems).value())
                .isEmpty();
    }

    @Test
    void shouldKeepAUserViewLimitedToItsWorkspace() {
        workspace(owner, 0);
        var viewed = workspace(owner, 1);
        viewed.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        workspaces.saveAndFlush(viewed);
        UserViewContextHolder.set(
                new UserViewContextHolder.View(viewed.getId(), Objects.requireNonNull(owner.getId())));
        try {
            var read = reads.measure(queries::findAccessibleWorkspaceListItems);
            assertThat(read.value()).extracting(WorkspaceListItemDTO::id).containsExactly(viewed.getId());
            assertThat(read.value().getFirst().providerType()).isEqualTo(IdentityProviderType.GITHUB);
            assertThat(read.value().getFirst().status()).isEqualTo("SUSPENDED");
        } finally {
            UserViewContextHolder.clear();
        }
    }

    @Test
    void shouldRetainConnectionMetadataForSuspendedWorkspaceDetails() {
        var workspace = workspace(owner, 0);
        workspace.setStatus(Workspace.WorkspaceStatus.SUSPENDED);
        workspaces.saveAndFlush(workspace);

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().status()).isEqualTo("SUSPENDED");
        assertThat(read.value().installationId()).isEqualTo(workspace.getId() + 10000);
        assertThat(read.value().providerType()).isEqualTo(IdentityProviderType.GITHUB);
        assertThat(reads.measure(queries::findAccessibleWorkspaceListItems).value())
                .isEmpty();
    }

    @Test
    void shouldIgnoreNewerInactiveScmConnections() {
        var workspace = workspace(owner, 0);
        long installation = workspace.getId() + 20000;
        var recent = new Connection(
                workspace,
                IntegrationKind.GITHUB,
                Long.toString(installation),
                new ConnectionConfig.GitHubAppConfig(
                        installation, "read-new", "https://github.example.test", Set.of()));
        recent.setState(IntegrationState.SUSPENDED);
        recent = connections.saveAndFlush(recent);
        jdbc.update(
                "UPDATE connection SET created_at = ? WHERE workspace_id = ?",
                Timestamp.from(Instant.parse("2020-01-01T00:00:00Z")),
                workspace.getId());
        var linkedAt = Instant.parse("2020-01-01T00:00:00Z");
        jdbc.update(
                "UPDATE connection SET created_at = ? WHERE id = ?",
                Timestamp.from(Instant.parse("2021-01-01T00:00:00Z")),
                recent.getId());

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().installationId()).isEqualTo(workspace.getId() + 10000);
        assertThat(read.value().installationLinkedAt()).isEqualTo(linkedAt);
        assertThat(read.value().serverUrl()).isEqualTo("https://api.github.com");
    }

    @Test
    void shouldReturnNoScmProviderWithoutAnActiveScmConnection() {
        var workspace = workspace(owner, 0);
        var github = connections
                .findActive(workspace.getId(), IntegrationKind.GITHUB)
                .orElseThrow();
        github.setState(IntegrationState.SUSPENDED);
        connections.saveAndFlush(github);

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().kind()).isNull();
        assertThat(read.value().providerType()).isNull();
        assertThat(read.value().installationId()).isNull();
        assertThat(read.value().installationLinkedAt()).isNull();
        assertThat(read.value().serverUrl()).isNull();
        assertThat(read.value().hasPersonalAccessToken()).isFalse();
        assertThat(reads.measure(queries::findAccessibleWorkspaceListItems)
                        .value()
                        .getFirst()
                        .providerType())
                .isNull();
    }

    @Test
    void shouldKeepCaseInsensitiveDisplayNameAndSlugOrder() {
        var zeta = workspace(owner, 0);
        zeta.setDisplayName("zeta");
        var second = workspace(owner, 1);
        second.setDisplayName("ALPHA");
        second.setWorkspaceSlug("z-space");
        var first = workspace(owner, 2);
        first.setDisplayName("alpha");
        first.setWorkspaceSlug("a-space");
        workspaces.saveAllAndFlush(List.of(zeta, second, first));

        assertThat(reads.measure(queries::findAccessibleWorkspaceListItems).value())
                .extracting(WorkspaceListItemDTO::id)
                .containsExactly(first.getId(), second.getId(), zeta.getId());
    }

    @Test
    void shouldReportStoredSlackCredentialsWithoutDecryptingThem() {
        var workspace = workspace(owner, 0);
        var slack = new Connection(
                workspace,
                IntegrationKind.SLACK,
                "TSTORED",
                new ConnectionConfig.SlackConfig("TSTORED", "Stored", null, Set.of()));
        slack.setState(IntegrationState.ACTIVE);
        slack = connections.saveAndFlush(slack);
        storeUnreadableCredential(workspace, IntegrationKind.SLACK);

        var read = reads.measure(() -> queries.toWorkspaceDTO(workspace));

        assertThat(read.value().hasSlackToken()).isTrue();
        assertThat(read.value().slackConnectionId()).isEqualTo(slack.getId());
        assertThat(plans.fullScans(read.sql())).isEmpty();
    }

    private void storeUnreadableCredential(Workspace workspace, IntegrationKind kind) {
        assertThat(jdbc.update(
                        """
                UPDATE connection SET credentials_encrypted=?, credentials_alg=?, credentials_key_version=?
                WHERE workspace_id=? AND kind=?
                """,
                        new byte[] {1, 2, 3},
                        CredentialBundleConverter.ALGORITHM_TAG,
                        999,
                        workspace.getId(),
                        kind.name()))
                .isEqualTo(1);
    }

    private Workspace workspace(Account account, int index) {
        return workspace(account, index, false);
    }

    private Workspace workspace(Account account, int index, boolean personalAccessToken) {
        var workspace = new Workspace();
        workspace.setWorkspaceSlug("connection-read-" + index);
        workspace.setDisplayName("Connection read " + index);
        workspace.setAccountLogin("connection-read-" + index);
        workspace.setAccountType(AccountType.ORG);
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace = workspaces.saveAndFlush(workspace);
        workspaceMembershipService.createMembership(
                workspace, Objects.requireNonNull(account.getId()), Membership.WorkspaceRole.OWNER);
        long installation = workspace.getId() + 10000;
        ConnectionConfig config = personalAccessToken
                ? new ConnectionConfig.GitHubPatConfig(workspace.getAccountLogin(), "https://api.github.com", Set.of())
                : new ConnectionConfig.GitHubAppConfig(
                        installation, workspace.getAccountLogin(), "https://api.github.com", Set.of());
        var connection = new Connection(
                workspace, IntegrationKind.GITHUB, personalAccessToken ? null : Long.toString(installation), config);
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
        return workspace;
    }
}
