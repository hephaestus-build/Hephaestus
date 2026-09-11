package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The front door a developer uses to ask for a review of their own work.
 *
 * <p>Three properties are worth more here than the happy path. Reaching the work is not standing to
 * commission coaching about it, so an ordinary member is refused. A workspace that declines to review
 * something answers 200 with a sentence rather than an error, because the asker cannot fix a workspace
 * condition and should not be told the button is broken. And both limits on asking are enforced
 * against the ledger, so a person cannot re-ask their way around either.
 */
class PracticeReviewRequestControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String REQUESTS = "/workspaces/{slug}/practices/review-requests";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private ArtifactSignalRepository signalRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private Workspace workspace;
    private User author;
    /**
     * The one identity every test other than the author's runs as. The mock JWT decoder resolves a
     * fixed set of tokens, so {@code @WithUser(username = …)} changes nothing the server sees — the
     * only way to be somebody other than {@code testuser} is to sign a request as {@code mentor}.
     */
    private User colleague;

    private long pullRequestId;

    @BeforeEach
    void setUpWorkspace() {
        User owner = persistUser("request-owner");
        workspace = createWorkspace("request-ws", "Request WS", "request-org", AccountType.ORG, owner);
        ensureAdminMembership(workspace);
        author = persistUser("testuser");
        ensureWorkspaceMembership(workspace, author, WorkspaceRole.MEMBER);
        colleague = persistUser("mentor");
        ensureWorkspaceMembership(workspace, colleague, WorkspaceRole.MEMBER);

        Repository repository = persistRepository("request-org/request-repo", 9101L);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        repositoryToMonitorRepository.save(monitor);
        pullRequestId = persistPullRequest(repository, author, 77);
    }

    @Nested
    @DisplayName("Who may ask")
    class WhoMayAsk {

        @Test
        void refusesAnAnonymousCaller() {
            webTestClient
                    .post()
                    .uri(REQUESTS, workspace.getWorkspaceSlug())
                    .bodyValue(body(ArtifactKinds.PULL_REQUEST.value(), pullRequestId))
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        /**
         * The rule the whole endpoint rests on. A colleague can see this merge request — that is what
         * being on the team means — but the feedback a review produces is delivered to its author, so
         * letting a bystander occasion it hands them a way to aim coaching at somebody else.
         */
        @Test
        @WithMentorUser
        void refusesAWorkspaceMemberWhoIsNeitherAuthorNorAssignee() {
            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void admitsTheAuthorOfTheWork() {
            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void admitsAWorkspaceAdminAskingAboutSomebodyElsesWork() {
            promoteColleagueToAdmin();

            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("What comes back")
    class WhatComesBack {

        /**
         * The endpoint's central claim. This workspace has no practice bound to a merge request, so no
         * review runs — and the caller gets a 200 naming the reason, not a 4xx implying they did
         * something wrong. The sentence is the one the reason itself carries, so every surface says it
         * the same way.
         */
        @Test
        @WithUser
        void answersARefusalAsTwoHundredWithASentence() {
            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("REFUSED")
                    .jsonPath("$.reason")
                    .isNotEmpty()
                    .jsonPath("$.reasonDescription")
                    .isNotEmpty()
                    .jsonPath("$.jobId")
                    .doesNotExist();
        }

        @Test
        @WithUser
        void answersNotFoundForWorkThisWorkspaceDoesNotMonitor() {
            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId + 9999)
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        /** A chat thread is reviewed on the occasion its source produces; there is nothing to point at. */
        @Test
        @WithUser
        void refusesAKindThatHasNoFrontDoorHere() {
            post(ArtifactKinds.CONVERSATION_THREAD.value(), pullRequestId)
                    .expectStatus()
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        @WithUser
        void refusesSomethingThatIsNotAnArtifactKindAtAll() {
            post("NotAKind", pullRequestId).expectStatus().isBadRequest().expectBody(Void.class);
        }

        /**
         * A body with no id at all. {@code @Positive} is satisfied by a missing value, so the request
         * would otherwise pass validation and be unboxed into the loader — an NPE the endpoint reports
         * as a 500 it never declares, in place of the 400 it does.
         */
        @Test
        @WithUser
        void namesTheMissingFieldWhenTheArtifactIdIsAbsent() {
            postBody(Map.of("artifactKind", ArtifactKinds.PULL_REQUEST.value()))
                    .expectStatus()
                    .isBadRequest()
                    .expectBody()
                    .jsonPath("$.errors.artifactId")
                    .exists();
        }
    }

    @Nested
    @DisplayName("Limits on asking")
    class Limits {

        /**
         * The workspace's cooldown, applied to asking. The ordinary cooldown cannot catch this: it is
         * keyed on an agent-job idempotency key whose phase segment is the trigger signal, and a request
         * carries none, so it occupies a lane of its own.
         */
        @Test
        @WithUser
        void refusesASecondAskAboutTheSameWorkInsideTheCooldown() {
            giveTheWorkspaceACooldown();
            recordManualRequest(pullRequestId, author.getId(), Instant.now());

            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("REFUSED")
                    .jsonPath("$.reason")
                    .isEqualTo("REQUEST_COOLDOWN_ACTIVE");
        }

        /** An ask older than the window does not hold the door shut forever. */
        @Test
        @WithUser
        void letsThroughAnAskWhoseCooldownHasExpired() {
            giveTheWorkspaceACooldown();
            recordManualRequest(pullRequestId, author.getId(), Instant.now().minusSeconds(3600));

            expectRefusedButNotLimited(post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId));
        }

        /**
         * The only limit keyed on a person. Every row here is about a <em>different</em> artifact, so
         * every artifact-keyed check passes — which is exactly the pattern (one review each of twenty
         * colleagues' merge requests) that this limit exists to stop.
         */
        @Test
        @WithUser
        void refusesAPersonWhoHasSpentTheHoursAllowanceOnOtherWork() {
            for (int i = 0; i < 5; i++) {
                recordManualRequest(pullRequestId + 100 + i, author.getId(), Instant.now());
            }

            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.status")
                    .isEqualTo("REFUSED")
                    .jsonPath("$.reason")
                    .isEqualTo("REQUESTER_QUOTA_EXHAUSTED");
        }

        /** The allowance is per person: somebody else's asks do not spend mine. */
        @Test
        @WithUser
        void doesNotChargeOnePersonForAnothersAsks() {
            User other = persistUser("other-asker");
            for (int i = 0; i < 5; i++) {
                recordManualRequest(pullRequestId + 200 + i, other.getId(), Instant.now());
            }

            expectRefusedButNotLimited(post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId));
        }

        /**
         * A refused ask leaves no ledger row. The limits count manual rows, so recording their own
         * refusals would make the population self-inflating and tighten the allowance under retry.
         */
        @Test
        @WithUser
        void aRateLimitedAskWritesNothingToTheLedger() {
            giveTheWorkspaceACooldown();
            recordManualRequest(pullRequestId, author.getId(), Instant.now());

            post(ArtifactKinds.PULL_REQUEST.value(), pullRequestId)
                    .expectStatus()
                    .isOk()
                    .expectBody(Void.class);

            org.assertj.core.api.Assertions.assertThat(signalRepository.findForArtifact(
                            workspace.getId(), ArtifactKinds.PULL_REQUEST.value(), pullRequestId))
                    .hasSize(1);
        }
    }

    // Fixtures

    /**
     * The test profile sets the fleet cooldown to 0, which switches the per-artifact limit off. An
     * operator who runs a cooldown is the case under test, so the workspace states one.
     */
    private void giveTheWorkspaceACooldown() {
        Workspace stored = workspaceRepository.findById(workspace.getId()).orElseThrow();
        stored.getReviewSettings().applyPatch(null, 15);
        workspaceRepository.save(stored);
    }

    private void promoteColleagueToAdmin() {
        workspaceMembershipRepository
                .findByWorkspace_IdAndUser_Id(workspace.getId(), colleague.getId())
                .ifPresent(membership -> {
                    membership.setRole(WorkspaceRole.ADMIN);
                    workspaceMembershipRepository.save(membership);
                });
    }

    /**
     * The ask reached the gate. Which reason the gate then gave depends on how this workspace is set up
     * and is not what these tests are about; that it is not one of the two limits is.
     */
    private static void expectRefusedButNotLimited(WebTestClient.ResponseSpec response) {
        response.expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.reason")
                .value(reason -> org.assertj.core.api.Assertions.assertThat(reason)
                        .isNotIn("REQUEST_COOLDOWN_ACTIVE", "REQUESTER_QUOTA_EXHAUSTED"));
    }

    private WebTestClient.ResponseSpec post(String kind, long artifactId) {
        return postBody(body(kind, artifactId));
    }

    private WebTestClient.ResponseSpec postBody(Map<String, Object> body) {
        return webTestClient
                .post()
                .uri(REQUESTS, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .bodyValue(body)
                .exchange();
    }

    private static Map<String, Object> body(String kind, long artifactId) {
        return Map.of("artifactKind", kind, "artifactId", artifactId);
    }

    private Repository persistRepository(String nameWithOwner, long nativeId) {
        Repository repository = new Repository();
        repository.setNativeId(nativeId);
        repository.setProvider(ensureGitHubProvider());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        return repositoryRepository.save(repository);
    }

    private long persistPullRequest(Repository repository, User prAuthor, int number) {
        Instant now = Instant.now();
        Long providerId = repository.getProvider().getId();
        org.junit.jupiter.api.Assertions.assertNotNull(providerId);
        pullRequestRepository.upsertCore(
                9200L + number,
                providerId,
                number,
                "A change worth reviewing",
                "Body",
                "OPEN",
                null,
                "https://github.com/" + repository.getNameWithOwner() + "/pull/" + number,
                false,
                null,
                0,
                now,
                now,
                now,
                prAuthor.getId(),
                repository.getId(),
                null,
                null,
                false,
                false,
                1,
                10,
                5,
                3,
                null,
                null,
                null,
                "feature/branch",
                "main",
                "headsha",
                "basesha",
                null,
                null);
        return pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), number)
                .orElseThrow()
                .getId();
    }

    /** A ledger row exactly as {@code ManualReviewRequests} would leave one, for the limits to count. */
    private void recordManualRequest(long artifactId, Long requesterId, Instant occurredAt) {
        ArtifactSignal row = new ArtifactSignal();
        row.setId(UUID.randomUUID());
        row.setWorkspace(workspace);
        row.setArtifactKind(ArtifactKinds.PULL_REQUEST.value());
        row.setArtifactId(artifactId);
        row.setSignalName(ScmSignals.PULL_REQUEST_MANUAL_REVIEW.value());
        row.setRevision("run~" + UUID.randomUUID());
        row.setOccurredAt(occurredAt);
        row.setDiscoveredVia(DiscoveredVia.MANUAL);
        row.setState(SignalState.RECORDED);
        row.setStateChangedAt(occurredAt);
        row.setRequestedByUserId(requesterId);
        signalRepository.save(row);
    }
}
