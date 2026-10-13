package de.tum.cit.aet.hephaestus.workspace;

import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_MET;
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
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * An account whose first-linked actor owns a workspace connected to another provider or another instance of
 * the same provider, where its later-linked actor is the one reviewed. The account reads the workspace as its
 * actor on the connected instance, and keeps what it started through either actor.
 */
class WorkspaceActorSelectionIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    @Autowired
    private IdentityLinkRepository identities;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private AccountWorkspaceMembershipQuery memberships;

    @Autowired
    private MentorConversationService mentorConversations;

    @Autowired
    private MentorTurnService mentorTurns;

    @Test
    void shouldReadAWorkspaceAsTheActorOnItsActiveProviderWhenAnotherProviderWasLinkedFirst() {
        User gitlabActor = userRepository.saveAndFlush(
                TestUserFactory.createUser(264_583_885L, "felix-gitlab", ensureGitLabProvider()));
        User githubActor = persistUser("felix-github");
        Account account = persistAccount("Two providers");
        link(account, gitlabActor);
        link(account, githubActor);

        Workspace workspace = createWorkspace("actor-github", "GitHub", "acme", AccountType.ORG, gitlabActor);
        ensureWorkspaceMembership(workspace, githubActor, WorkspaceMembership.WorkspaceRole.MEMBER);
        connect(workspace, IntegrationKind.GITHUB, new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        Practice practice = persistPractice(workspace, null, "github-practice", "GitHub practice", null);
        AgentJob run = persistPullRequestReview(workspace, 1, NOW.minus(Duration.ofDays(1)));
        observe(practice, run, 1L, githubActor, NOT_MET, Severity.MAJOR, NOW.minus(Duration.ofDays(1)));

        String token = "mock-jwt-user-sub-" + account.getId();
        read(token, "/workspaces/actor-github/members/me")
                .jsonPath("$.userId")
                .isEqualTo(githubActor.getId())
                .jsonPath("$.role")
                .isEqualTo("OWNER");
        read(token, "/workspaces/actor-github/practices/standings")
                .jsonPath("$[0].slug")
                .isEqualTo("github-practice")
                // One problem on one piece of work: a verdict read for this actor, one setback.
                .jsonPath("$[0].standing")
                .isEqualTo("MIXED");
        read(token, "/workspaces/actor-github/practice-profile/overview")
                .jsonPath("$.latestRun.reviewId")
                .isEqualTo(run.getId().toString());

        // Slack and Outline attribute through the same account view.
        assertThat(memberships.membershipsForAccount(Objects.requireNonNull(account.getId())))
                .extracting(WorkspaceMembershipView::memberId)
                .containsExactly(githubActor.getId());
    }

    @Test
    void shouldKeepTheAccountsConversationWhenTheWorkspaceReadsItAsAnotherActor() {
        User gitlabActor = userRepository.saveAndFlush(
                TestUserFactory.createUser(264_583_886L, "felix-gitlab-heph", ensureGitLabProvider()));
        User githubActor = persistUser("felix-github-heph");
        User colleague = persistUser("colleague-heph");
        Account account = persistAccount("Two providers with a conversation");
        link(account, gitlabActor);
        link(account, githubActor);

        Workspace workspace = createWorkspace("actor-heph", "Heph", "acme", AccountType.ORG, gitlabActor);
        ensureWorkspaceMembership(workspace, githubActor, WorkspaceMembership.WorkspaceRole.MEMBER);
        ensureWorkspaceMembership(workspace, colleague, WorkspaceMembership.WorkspaceRole.MEMBER);
        connect(workspace, IntegrationKind.GITHUB, new ConnectionConfig.GitHubPatConfig("acme", null, Set.of()));
        UUID earlier = question(workspace, gitlabActor, "Asked as the GitLab actor");
        UUID colleagues = question(workspace, colleague, "A colleague's question");
        Workspace elsewhere = createWorkspace("actor-heph-other", "Other", "other", AccountType.ORG, githubActor);
        UUID otherWorkspace = question(elsewhere, githubActor, "Another workspace");

        String token = "mock-jwt-member-" + account.getId();
        String mentor = "/workspaces/actor-heph/mentor";
        read(token, mentor + "/entries").jsonPath("$.entries[*].id").isEqualTo(List.of(earlier.toString()));
        read(token, mentor + "/entries/" + earlier + "/thread")
                .jsonPath("$.parent.id")
                .isEqualTo(earlier.toString());
        for (UUID foreign : List.of(colleagues, otherWorkspace)) {
            webTestClient
                    .get()
                    .uri(mentor + "/entries/" + foreign + "/thread")
                    .headers(headers -> headers.setBearerAuth(token))
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }
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
                        Set.of(),
                        null));
        Practice practice = persistPractice(workspace, null, "lrz-practice", "LRZ practice", null);
        AgentJob run = persistPullRequestReview(workspace, 3, NOW.minus(Duration.ofDays(1)));
        UUID observation =
                observe(practice, run, 3L, selfHostedActor, NOT_MET, Severity.MAJOR, NOW.minus(Duration.ofDays(1)));
        Feedback feedback = persistInAppFeedback(
                run,
                selfHostedActor,
                1,
                FeedbackDeliveryState.DELIVERED,
                InAppFeedbackBody.render("A way of working", "What recurs.", "One thing to try."),
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
                // One problem on one piece of work: a verdict read for this actor, one setback.
                .jsonPath("$[0].standing")
                .isEqualTo("MIXED");
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

    /** The id of a question {@code author} asked Heph in {@code workspace}. */
    private UUID question(Workspace workspace, User author, String text) {
        MentorConversation main =
                mentorConversations.findOrCreateMain(workspace.getId(), author.getId(), Set.of(author.getId()));
        return mentorTurns
                .submit(
                        workspace.getId(),
                        main.getId(),
                        new MentorTurnService.Question(
                                MentorTurn.Surface.APP, text, UUID.randomUUID().toString(), null, null))
                .question()
                .getId();
    }

    private void connect(Workspace workspace, IntegrationKind kind, ConnectionConfig config) {
        Connection connection = new Connection(workspace, kind, kind.name(), config);
        connection.setState(IntegrationState.ACTIVE);
        connections.saveAndFlush(connection);
    }
}
