package de.tum.cit.aet.hephaestus.workspace;

import static de.tum.cit.aet.hephaestus.practices.model.ObservationKind.OMISSION_GAP;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountWorkspaceMembershipQuery.WorkspaceMembershipView;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * An account whose first-linked actor owns a workspace connected to another provider or another instance of
 * the same provider, where its later-linked actor is the one reviewed. The account reads each workspace as its
 * actor on that workspace's connected instance.
 */
class WorkspaceActorSelectionIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    @Autowired
    private IdentityLinkRepository identities;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private AccountWorkspaceMembershipQuery memberships;

    @Test
    void shouldReadAWorkspaceAsTheActorOnItsActiveProviderWhenAnotherProviderWasLinkedFirst() {
        User githubActor = persistUser("felix-github");
        User gitlabActor = userRepository.saveAndFlush(
                TestUserFactory.createUser(264_583_885L, "felix-gitlab", ensureGitLabProvider()));
        Account account = persistAccount("Two providers");
        link(account, githubActor);
        link(account, gitlabActor);

        Workspace gitlabWorkspace = createWorkspace("actor-gitlab", "GitLab", "group", AccountType.ORG, githubActor);
        ensureWorkspaceMembership(gitlabWorkspace, gitlabActor, WorkspaceMembership.WorkspaceRole.MEMBER);
        connect(
                gitlabWorkspace,
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.com",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of()));
        Practice gitlabPractice = persistPractice(gitlabWorkspace, null, "gitlab-practice", "GitLab practice", null);
        AgentJob gitlabRun = persistPullRequestReview(gitlabWorkspace, 1, NOW.minus(Duration.ofDays(2)));
        observe(
                gitlabPractice,
                gitlabRun,
                1L,
                gitlabActor,
                OMISSION_GAP,
                Severity.MAJOR,
                NOW.minus(Duration.ofDays(2)));

        // A later review of the same actor in another workspace; none of it may surface in the GitLab one.
        Workspace githubWorkspace = createWorkspace("actor-github", "GitHub", "acme", AccountType.ORG, githubActor);
        ensureWorkspaceMembership(githubWorkspace, gitlabActor, WorkspaceMembership.WorkspaceRole.MEMBER);
        connect(githubWorkspace, IntegrationKind.GITHUB, new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        Practice githubPractice = persistPractice(githubWorkspace, null, "github-practice", "GitHub practice", null);
        AgentJob githubRun = persistPullRequestReview(githubWorkspace, 2, NOW.minus(Duration.ofDays(1)));
        observe(
                githubPractice,
                githubRun,
                2L,
                gitlabActor,
                OMISSION_GAP,
                Severity.MAJOR,
                NOW.minus(Duration.ofDays(1)));

        String token = "mock-jwt-user-sub-" + account.getId();
        read(token, "/workspaces/actor-gitlab/members/me")
                .jsonPath("$.userId")
                .isEqualTo(gitlabActor.getId())
                .jsonPath("$.role")
                .isEqualTo("OWNER");
        read(token, "/workspaces/actor-gitlab/practices/standings")
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].slug")
                .isEqualTo("gitlab-practice")
                .jsonPath("$[0].standing")
                .isEqualTo("DEVELOPING");
        read(token, "/workspaces/actor-gitlab/practice-profile/overview")
                .jsonPath("$.latestRun.jobId")
                .isEqualTo(gitlabRun.getId().toString());
        read(token, "/workspaces/actor-github/members/me").jsonPath("$.userId").isEqualTo(githubActor.getId());

        // Slack and Outline attribute through the same account view.
        assertThat(memberships.membershipsForAccount(Objects.requireNonNull(account.getId())).stream()
                        .collect(Collectors.toMap(
                                WorkspaceMembershipView::workspaceSlug, WorkspaceMembershipView::memberId)))
                .isEqualTo(Map.of("actor-gitlab", gitlabActor.getId(), "actor-github", githubActor.getId()));
    }

    @Test
    void shouldReadAWorkspaceAsTheActorOnItsConnectedInstanceWhenAnotherInstanceOfTheProviderWasLinkedFirst() {
        User saasActor =
                userRepository.saveAndFlush(TestUserFactory.createUser(4_001L, "felix-saas", ensureGitLabProvider()));
        User selfHostedActor = userRepository.saveAndFlush(TestUserFactory.createUser(
                4_002L,
                "ga84xah",
                gitProviderRepository.save(
                        new IdentityProvider(IdentityProviderType.GITLAB, "https://gitlab.lrz.de"))));
        Account account = persistAccount("Two GitLab instances");
        link(account, saasActor);
        link(account, selfHostedActor);

        Workspace workspace = createWorkspace("actor-lrz", "LRZ", "group", AccountType.ORG, saasActor);
        ensureWorkspaceMembership(workspace, selfHostedActor, WorkspaceMembership.WorkspaceRole.MEMBER);
        connect(
                workspace,
                IntegrationKind.GITLAB,
                new ConnectionConfig.GitLabConfig(
                        "https://gitlab.lrz.de",
                        null,
                        null,
                        ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT,
                        Set.of()));
        Practice practice = persistPractice(workspace, null, "lrz-practice", "LRZ practice", null);
        AgentJob run = persistPullRequestReview(workspace, 3, NOW.minus(Duration.ofDays(1)));
        UUID observation = observe(
                practice, run, 3L, selfHostedActor, OMISSION_GAP, Severity.MAJOR, NOW.minus(Duration.ofDays(1)));
        Feedback feedback = persistInAppFeedback(
                run,
                selfHostedActor,
                1,
                FeedbackDeliveryState.DELIVERED,
                InAppFeedbackBody.render("A habit", "What recurs.", "One thing to try."),
                NOW.minus(Duration.ofDays(1)));
        bind(feedback, observation);

        String token = "mock-jwt-user-sub-" + account.getId();
        read(token, "/workspaces/actor-lrz/members/me")
                .jsonPath("$.userId")
                .isEqualTo(selfHostedActor.getId())
                .jsonPath("$.role")
                .isEqualTo("OWNER");
        read(token, "/workspaces/actor-lrz/practices/standings")
                .jsonPath("$[0].slug")
                .isEqualTo("lrz-practice")
                .jsonPath("$[0].standing")
                .isEqualTo("DEVELOPING");
        read(token, "/workspaces/actor-lrz/practices/feedback/in-app")
                .jsonPath("$[0].id")
                .isEqualTo(feedback.getId().toString());
        assertThat(memberships.membershipsForAccount(Objects.requireNonNull(account.getId())))
                .extracting(WorkspaceMembershipView::memberId)
                .containsExactly(selfHostedActor.getId());
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
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(actor.getProvider().getId()));
        link.setSubject(actor.getNativeId().toString());
        identities.saveAndFlush(link);
    }

    private void connect(Workspace workspace, IntegrationKind kind, ConnectionConfig config) {
        Connection connection = new Connection(workspace, kind, kind.name(), config);
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
    }
}
