package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserInfoDTO;
import de.tum.cit.aet.hephaestus.leaderboard.LeaderboardEntryDTO;
import de.tum.cit.aet.hephaestus.mentor.ChatThread;
import de.tum.cit.aet.hephaestus.mentor.ChatThreadRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRevisionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackThreadKey;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-tenant probes use two workspaces with the same account login and an overlapping member. This
 * keeps authentication valid while distinguishing workspace scoping from organization- or user-based
 * filtering. Detail probes include a successful same-workspace request before the foreign request.
 */
class CrossTenantIsolationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SHARED_LOGIN = "shared-org";
    private static final String DIFF_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"inputs/context/diff.patch\","
                    + "\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":42,\"endLine\":42,\"quote\":\"example\","
                    + "\"quoteRedacted\":false}]}";

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private PracticeRepository practiceRepository;

    @Autowired
    private AgentJobRepository agentJobRepository;

    @Autowired
    private ObservationRepository observationRepository;

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private FeedbackObservationRepository feedbackObservationRepository;

    @Autowired
    private PracticeRevisionRepository practiceRevisionRepository;

    @Autowired
    private ChatThreadRepository chatThreadRepository;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipRepository teamMembershipRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    private User overlapUser;
    private User bobOnlyB;

    private Workspace workspaceA;
    private Workspace workspaceB;
    private Workspace outsiderWorkspace;

    private TenantData tenantA;
    private TenantData tenantB;

    private record TenantData(String practiceSlug, UUID observationId, UUID feedbackId, UUID threadId) {}

    @BeforeEach
    void provisionTwoTenants() {
        overlapUser = persistUser("mentor");

        User ownerA = persistUser("owner-a");
        workspaceA = createWorkspace("tenant-a", "Tenant A", SHARED_LOGIN, AccountType.ORG, ownerA);
        ensureWorkspaceMembership(workspaceA, overlapUser, WorkspaceRole.ADMIN);
        User aliceOnlyA = persistUser("alice-only-a");
        ensureWorkspaceMembership(workspaceA, aliceOnlyA, WorkspaceRole.MEMBER);

        User ownerB = persistUser("owner-b");
        workspaceB = createWorkspace("tenant-b", "Tenant B", SHARED_LOGIN, AccountType.ORG, ownerB);
        ensureWorkspaceMembership(workspaceB, overlapUser, WorkspaceRole.ADMIN);
        bobOnlyB = persistUser("bob-only-b");
        ensureWorkspaceMembership(workspaceB, bobOnlyB, WorkspaceRole.MEMBER);

        User ownerC = persistUser("owner-c");
        outsiderWorkspace = createWorkspace("tenant-c", "Tenant C", "org-c", AccountType.ORG, ownerC);

        tenantA = seed(workspaceA);
        tenantB = seed(workspaceB);
    }

    private TenantData seed(Workspace ws) {
        String practiceSlug = "practice-" + ws.getWorkspaceSlug();
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(ws);
        practice.setSlug(practiceSlug);
        practice.setName("Practice of " + ws.getWorkspaceSlug());
        practice.setCriteria("Criteria for " + ws.getWorkspaceSlug());
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice = practiceRepository.save(practice);

        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job = agentJobRepository.save(job);

        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "occ-" + observationId,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                null,
                "scm.pull_request",
                1L,
                overlapUser.getId(),
                "Finding in " + ws.getWorkspaceSlug(),
                "ASSESSED",
                "PRESENT",
                "GOOD",
                null,
                null,
                "reasoning",
                null,
                Instant.now(),
                "LIVE");

        Feedback feedback = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(ws.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .recipientUserId(overlapUser.getId())
                .aboutUserId(overlapUser.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body("Advice in " + ws.getWorkspaceSlug())
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .build());

        ChatThread thread = new ChatThread();
        thread.setId(UUID.randomUUID());
        thread.setTitle("Thread in " + ws.getWorkspaceSlug());
        thread.setWorkspace(ws);
        thread.setUser(overlapUser);
        chatThreadRepository.save(thread);

        return new TenantData(practiceSlug, observationId, feedback.getId(), thread.getId());
    }

    private void linkSyncedOrg(Workspace ws, IdentityProvider provider) {
        Organization org = new Organization();
        org.setNativeId(800_000L + ws.getId());
        org.setLogin(ws.getAccountLogin());
        org.setHtmlUrl("https://example.com/" + ws.getAccountLogin());
        org.setProvider(provider);
        org = organizationRepository.save(org);
        ws.setOrganization(org);
        workspaceRepository.save(ws);
    }

    private Team seedTeam(String name, long nativeId, String organization, IdentityProvider provider) {
        Team team = new Team();
        team.setNativeId(nativeId);
        team.setName(name);
        team.setSlug(name);
        team.setOrganization(organization);
        team.setHtmlUrl("https://example.com/teams/" + name);
        team.setPrivacy(Team.Privacy.VISIBLE);
        team.setProvider(provider);
        return teamRepository.save(team);
    }

    @Nested
    @DisplayName("Practices catalog")
    class Practices {

        @Test
        @WithMentorUser
        void listReturnsOnlyOwnWorkspacePractices() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].slug")
                    .isEqualTo(tenantA.practiceSlug());
        }

        @Test
        @WithMentorUser
        void detailIsScopedToWorkspace() {
            expectDetailStatus("/practices/{key}", tenantA.practiceSlug())
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/practices/{key}", tenantB.practiceSlug())
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Observations (practice findings)")
    class Observations {

        @Test
        @WithMentorUser
        void listReturnsOnlyOwnWorkspaceFindings() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/observations", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].summary")
                    .isEqualTo("Finding in tenant-a");
        }

        @Test
        @WithMentorUser
        void detailIsScopedToWorkspace() {
            expectDetailStatus("/practices/observations/{key}", tenantA.observationId())
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/practices/observations/{key}", tenantB.observationId())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void adviceOnTheDetailReadComesOnlyFromThisWorkspace() {
            bindAdvice(
                    workspaceA, tenantA.observationId(), "Advice in tenant A", Instant.parse("2026-01-01T00:00:00Z"));
            bindAdvice(
                    workspaceB, tenantA.observationId(), "Advice in tenant B", Instant.parse("2026-02-01T00:00:00Z"));

            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/practices/observations/{key}",
                            workspaceA.getWorkspaceSlug(),
                            tenantA.observationId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.deliveredFeedback")
                    .isEqualTo("Advice in tenant A");
        }
    }

    private void bindAdvice(Workspace ws, UUID observationId, String body, Instant createdAt) {
        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job = agentJobRepository.save(job);

        Feedback unit = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(ws.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .recipientUserId(overlapUser.getId())
                .aboutUserId(overlapUser.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .body(body)
                .source(FeedbackSource.AGENT)
                .createdAt(createdAt)
                .build());
        feedbackObservationRepository.insertIfAbsent(unit.getId(), observationId, "PRIMARY", 0);
    }

    @Nested
    @DisplayName("Feedback reactions")
    class FeedbackReactions {

        @Test
        @WithMentorUser
        void reactionsAreScopedToWorkspace() {
            expectDetailStatus("/practices/feedback/{key}/response", tenantA.feedbackId())
                    .isNoContent();
            expectDetailStatus("/practices/feedback/{key}/response", tenantB.feedbackId())
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("In-app feedback (the developer's own practice pages)")
    class InAppFeedback {

        @Test
        @WithMentorUser
        void readsOnlyTheMessagesPreparedInThisWorkspace() {
            seedInAppUnit(workspaceA, "A habit measured in tenant A");
            seedInAppUnit(workspaceB, "A habit measured in tenant B");

            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/feedback/in-app", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].headline")
                    .isEqualTo("A habit measured in tenant A");
        }

        @Test
        @WithMentorUser
        void neverRetiresACardQueuedInAnotherTenant() {
            String sharedKey = FeedbackThreadKey.forPractice(
                    "ships-tests-with-the-change", overlapUser.getId(), FeedbackChannel.IN_APP);
            Feedback inA = seedInAppUnit(workspaceA, "A habit measured in tenant A", sharedKey);
            Feedback inB = seedInAppUnit(workspaceB, "A habit measured in tenant B", sharedKey);

            assertThat(feedbackRepository.findLatestOnThread(
                            workspaceA.getId(), overlapUser.getId(), FeedbackChannel.IN_APP.name(), sharedKey))
                    .contains(inA.getId());
            assertThat(feedbackRepository.markSuperseded(workspaceA.getId(), inB.getId()))
                    .as("tenant A cannot retire tenant B's card even holding its id")
                    .isZero();
            assertThat(feedbackRepository.markSuperseded(workspaceB.getId(), inB.getId()))
                    .as("positive control: the card was still queued, so the refusal above was the predicate")
                    .isEqualTo(1);
        }

        @Test
        @WithMentorUser
        void namesOnlyThisTenantsPracticeForAQueuedCard() {
            Feedback inA = seedInAppUnit(workspaceA, "A habit measured in tenant A");
            Feedback inB = seedInAppUnit(workspaceB, "A habit measured in tenant B");

            assertThat(feedbackRepository.findHeadlinePractices(workspaceA.getId(), List.of(inA.getId(), inB.getId())))
                    .singleElement()
                    .satisfies(row -> {
                        assertThat(row.getFeedbackId()).isEqualTo(inA.getId());
                        assertThat(row.getPracticeSlug()).isEqualTo("in-app-" + workspaceA.getWorkspaceSlug());
                    });
        }
    }

    private Feedback seedInAppUnit(Workspace ws, String headline) {
        return seedInAppUnit(ws, headline, null);
    }

    private Feedback seedInAppUnit(Workspace ws, String headline, @Nullable String threadKey) {
        Practice practice = new Practice();
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setWorkspace(ws);
        practice.setSlug("in-app-" + ws.getWorkspaceSlug());
        practice.setName("In-app practice of " + ws.getWorkspaceSlug());
        practice.setCriteria("Criteria for " + ws.getWorkspaceSlug());
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);

        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.0.0"))));
        job = agentJobRepository.save(job);

        UUID observationId = UUID.randomUUID();
        observationRepository.insertIfAbsent(
                observationId,
                "in-app-occ-" + observationId,
                job.getId(),
                job.getWorkspace().getId(),
                practice.getId(),
                revision.getId(),
                "scm.pull_request",
                2L,
                overlapUser.getId(),
                "In-app evidence in " + ws.getWorkspaceSlug(),
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                DIFF_EVIDENCE_JSON,
                "reasoning",
                null,
                Instant.now(),
                "LIVE");

        Feedback unit = feedbackRepository.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(ws.getId())
                .recipientUserId(overlapUser.getId())
                .aboutUserId(overlapUser.getId())
                .channel(FeedbackChannel.IN_APP)
                .position(7000)
                .deliveryState(FeedbackDeliveryState.PREPARED)
                .body(InAppFeedbackBody.render(headline, "The message.", "The habit to try."))
                .source(FeedbackSource.AGENT)
                .threadKey(threadKey)
                .createdAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(unit.getId(), observationId, "PRIMARY", 0);
        return unit;
    }

    @Nested
    @DisplayName("Mentor threads")
    class Mentor {

        @Test
        @WithMentorUser
        void listReturnsOnlyOwnWorkspaceThreads() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/mentor/threads", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.length()")
                    .isEqualTo(1)
                    .jsonPath("$[0].id")
                    .isEqualTo(tenantA.threadId().toString());
        }

        @Test
        @WithMentorUser
        void detailIsScopedToWorkspace() {
            expectDetailStatus("/mentor/threads/{key}", tenantA.threadId())
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/mentor/threads/{key}", tenantB.threadId())
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Leaderboard member roster")
    class Leaderboard {

        @Test
        @WithMentorUser
        void rosterIsScopedToWorkspaceMembership() {
            Team sharedTeam = seedTeam("shared-team", 900_100L, SHARED_LOGIN, ensureGitHubProvider());
            teamMembershipRepository.save(new TeamMembership(sharedTeam, bobOnlyB, TeamMembership.Role.MEMBER));

            List<String> logins = leaderboardLogins(workspaceA);

            assertThat(logins).contains("alice-only-a").doesNotContain("bob-only-b");
        }

        @Test
        @WithMentorUser
        void xpCountsOnlyOwnWorkspaceActivity() {
            seedActivity(workspaceA, overlapUser, 10);
            seedActivity(workspaceB, overlapUser, 99);

            Integer score = leaderboardEntries(workspaceA).stream()
                    .filter(e -> userOf(e).login().equals("mentor"))
                    .findFirst()
                    .orElseThrow()
                    .score();

            assertThat(score).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Contributor profiles and activity")
    class Profiles {

        @Test
        @WithMentorUser
        void shouldReturnNotFoundWhenProfileUserOnlyBelongsToAnotherWorkspace() {
            expectDetailStatus("/profile/{key}", "mentor").isOk().expectBody(Void.class);
            expectDetailStatus("/profile/{key}", bobOnlyB.getLogin())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldReturnNotFoundWhenActivityUserOnlyBelongsToAnotherWorkspace() {
            expectDetailStatus("/profile/{key}/activity-monitor", "mentor")
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/profile/{key}/activity-monitor", bobOnlyB.getLogin())
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Teams")
    class Teams {

        @Test
        @WithMentorUser
        void listIsScopedToWorkspaceProvider() {
            linkSyncedOrg(workspaceA, ensureGitHubProvider());
            seedTeam("own-team", 900_200L, SHARED_LOGIN, ensureGitHubProvider());
            seedTeam("foreign-team", 900_201L, SHARED_LOGIN, ensureGitLabProvider());

            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/team", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[?(@.name=='own-team')]")
                    .exists()
                    .jsonPath("$[?(@.name=='foreign-team')]")
                    .doesNotExist();
        }

        @Test
        @WithMentorUser
        void memberRosterExposesOnlyOwnWorkspaceTeams() {
            linkSyncedOrg(workspaceA, ensureGitHubProvider());
            Team ownTeam = seedTeam("own-team", 900_400L, SHARED_LOGIN, ensureGitHubProvider());
            Team foreignTeam = seedTeam("foreign-team", 900_401L, SHARED_LOGIN, ensureGitLabProvider());
            teamMembershipRepository.save(new TeamMembership(ownTeam, overlapUser, TeamMembership.Role.MEMBER));
            teamMembershipRepository.save(new TeamMembership(foreignTeam, overlapUser, TeamMembership.Role.MEMBER));

            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/users", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$[?(@.login=='mentor')].teams[?(@.name=='own-team')]")
                    .exists()
                    .jsonPath("$..teams[?(@.name=='foreign-team')]")
                    .doesNotExist();
        }

        @Test
        @WithAdminUser
        void visibilityWriteIsScopedToWorkspaceProvider() {
            ensureAdminMembership(workspaceA);
            linkSyncedOrg(workspaceA, ensureGitHubProvider());
            Team ownTeam = seedTeam("own-team", 900_300L, SHARED_LOGIN, ensureGitHubProvider());
            Team foreignTeam = seedTeam("foreign-team", 900_301L, SHARED_LOGIN, ensureGitLabProvider());

            expectVisibilityWriteStatus(ownTeam.getId()).isOk().expectBody(Void.class);
            expectVisibilityWriteStatus(foreignTeam.getId()).isNotFound().expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Access-control boundaries")
    class AccessControl {

        @Test
        @WithMentorUser
        void nonMemberIsRefusedFromScopedEndpoint() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/observations", outsiderWorkspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void workspaceMembershipDoesNotGrantInstanceAdminListing() {
            webTestClient
                    .get()
                    .uri("/admin/workspaces")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
    }

    private StatusAssertions expectDetailStatus(String suffix, Object key) {
        return webTestClient
                .get()
                .uri("/workspaces/{slug}" + suffix, workspaceA.getWorkspaceSlug(), key)
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus();
    }

    private StatusAssertions expectVisibilityWriteStatus(Long teamId) {
        return webTestClient
                .post()
                .uri("/workspaces/{slug}/team/{id}/visibility", workspaceA.getWorkspaceSlug(), teamId)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(true)
                .exchange()
                .expectStatus();
    }

    private void seedActivity(Workspace ws, User actor, double xp) {
        UUID id = UUID.randomUUID();
        activityEventRepository.insertIfAbsent(
                id,
                "evt-" + id,
                ActivityEventType.REVIEW_COMMENTED.name(),
                Instant.now(),
                actor.getId(),
                ws.getId(),
                null,
                "pull_request",
                1L,
                xp);
    }

    private List<String> leaderboardLogins(Workspace workspace) {
        return leaderboardEntries(workspace).stream()
                .map(e -> userOf(e).login())
                .toList();
    }

    private static UserInfoDTO userOf(LeaderboardEntryDTO entry) {
        UserInfoDTO user = entry.user();
        assertNotNull(user);
        return user;
    }

    private List<LeaderboardEntryDTO> leaderboardEntries(Workspace workspace) {
        List<LeaderboardEntryDTO> entries = webTestClient
                .get()
                .uri(uri -> uri.path("/workspaces/{slug}/leaderboard")
                        .queryParam("after", "2020-01-01T00:00:00Z")
                        .queryParam("before", "2100-01-01T00:00:00Z")
                        .queryParam("team", "all")
                        .queryParam("sort", "SCORE")
                        .queryParam("mode", "INDIVIDUAL")
                        .build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBodyList(LeaderboardEntryDTO.class)
                .returnResult()
                .getResponseBody();
        assertThat(entries).isNotNull();
        return entries;
    }
}
