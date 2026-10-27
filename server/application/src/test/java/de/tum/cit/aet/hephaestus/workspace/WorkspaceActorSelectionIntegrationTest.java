package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery.MembershipView;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class WorkspaceActorSelectionIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private WorkspaceActorSelector actors;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private AccountMembershipQuery memberships;

    @Test
    void shouldReadAWorkspaceAsTheActorOnItsActiveProviderWhenAnotherProviderWasLinkedFirst() {
        User gitlabActor = userRepository.saveAndFlush(
                TestUserFactory.createUser(264_583_885L, "jordan-gitlab", ensureGitLabProvider()));
        User githubActor = persistUser("jordan-github");
        Account account = persistAccount("Two providers");
        link(account, gitlabActor);
        link(account, githubActor);

        Workspace workspace = createWorkspace("actor-github", "GitHub", "acme", AccountType.ORG, gitlabActor);
        ensureMembership(workspace, githubActor, Membership.WorkspaceRole.MEMBER);
        connect(workspace, IntegrationKind.GITHUB, new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        assertThat(actors.select(
                        workspace.getId(),
                        List.of(
                                Objects.requireNonNull(gitlabActor.getId()),
                                Objects.requireNonNull(githubActor.getId()))))
                .contains(githubActor.getId());

        String token = "mock-jwt-user-sub-" + account.getId();
        read(token, "/workspaces/actor-github/members/me")
                .jsonPath("$.account.id")
                .isEqualTo(Objects.requireNonNull(account.getId()).toString())
                .jsonPath("$.role")
                .isEqualTo("OWNER");
        // Slack and Outline attribute through the same account view.
        assertThat(memberships.membershipsForAccount(Objects.requireNonNull(account.getId())))
                .extracting(MembershipView::workspaceId)
                .containsExactly(workspace.getId());
    }

    @Test
    void shouldReadAWorkspaceAsTheActorOnItsConnectedInstanceWhenAnotherInstanceOfTheProviderWasLinkedFirst() {
        User saasActor =
                userRepository.saveAndFlush(TestUserFactory.createUser(4_001L, "jordan-saas", ensureGitLabProvider()));
        User selfHostedActor = userRepository.saveAndFlush(TestUserFactory.createUser(
                4_002L,
                "ab12cde",
                gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))));
        Account account = persistAccount("Two GitLab instances");
        link(account, saasActor);
        link(account, selfHostedActor);

        Workspace workspace = createWorkspace("actor-lrz", "LRZ", "group", AccountType.ORG, saasActor);
        ensureMembership(workspace, selfHostedActor, Membership.WorkspaceRole.MEMBER);
        connect(
                workspace,
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.lrz.de",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of(),
                        null));
        assertThat(actors.select(
                        workspace.getId(),
                        List.of(
                                Objects.requireNonNull(saasActor.getId()),
                                Objects.requireNonNull(selfHostedActor.getId()))))
                .contains(selfHostedActor.getId());

        String token = "mock-jwt-user-sub-" + account.getId();
        read(token, "/workspaces/actor-lrz/members/me")
                .jsonPath("$.account.id")
                .isEqualTo(Objects.requireNonNull(account.getId()).toString())
                .jsonPath("$.role")
                .isEqualTo("OWNER");
        assertThat(memberships.membershipsForAccount(Objects.requireNonNull(account.getId())))
                .extracting(MembershipView::workspaceId)
                .containsExactly(workspace.getId());
    }

    private WebTestClient.BodyContentSpec read(String token, String uri) {
        return webTestClient
                .get()
                .uri(uri)
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private void link(Account account, User actor) {
        Identity link = new Identity();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(actor.getProvider().getId()));
        link.setProviderUserKey(actor.getNativeId().toString());
        identities.saveAndFlush(link);
    }

    private void connect(Workspace workspace, IntegrationKind kind, ConnectionConfig config) {
        Connection connection = new Connection(workspace, kind, kind.name(), config);
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
    }
}
