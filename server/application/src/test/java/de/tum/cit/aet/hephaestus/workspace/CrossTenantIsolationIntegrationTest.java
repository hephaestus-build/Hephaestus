package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerFixtures;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerNotifications;
import de.tum.cit.aet.hephaestus.mentor.MentorLedgerProbe;
import de.tum.cit.aet.hephaestus.mentor.MentorTurn;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnService;
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
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.Disposable;
import tools.jackson.databind.ObjectMapper;

/**
 * Cross-tenant probes use two workspaces with the same account login and an overlapping member. This
 * keeps authentication valid while distinguishing workspace scoping from organization- or user-based
 * filtering. Detail probes include a successful same-workspace request before the foreign request.
 */
class CrossTenantIsolationIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Duration STREAM_WAIT = Duration.ofSeconds(20);
    private static final String SHARED_LOGIN = "shared-org";
    private static final String DIFF_EVIDENCE_JSON =
            "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":\"context/diff.patch\","
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
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private MentorConversationService mentorConversations;

    @Autowired
    private MentorTurnService mentorTurns;

    @Autowired
    private MentorTurnRepository mentorTurnRepository;

    @Autowired
    private MentorEntryService mentorEntries;

    @Autowired
    private MentorEntryRepository mentorEntryRepository;

    @Autowired
    private MentorLedgerNotifications mentorNotifications;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private LlmConnectionRepository llmConnections;

    @Autowired
    private LlmModelRepository llmModels;

    @Autowired
    private WorkspaceAgentBindingRepository agentBindings;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipRepository teamMembershipRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository monitorRepository;

    private static final AtomicLong repositoryIds = new AtomicLong(9700000);

    private User overlapUser;
    private User bobOnlyB;

    private Workspace workspaceA;
    private Workspace workspaceB;
    private Workspace outsiderWorkspace;

    private TenantData tenantA;
    private TenantData tenantB;

    private record TenantData(String practiceSlug, UUID observationId, UUID feedbackId, UUID entryId, UUID turnId) {}

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
        practice.setSignals(PracticeTestEvidence.signals(ScmSignals.PULL_REQUEST_OPENED));
        practice.setEvidenceRequirements(PracticeTestEvidence.needsFor(ScmSignals.PULL_REQUEST_OPENED.artifactKind()));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);
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
                "Observation in " + ws.getWorkspaceSlug(),
                "MET",
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

        MentorConversation main =
                mentorConversations.findOrCreateMain(ws.getId(), overlapUser.getId(), Set.of(overlapUser.getId()));
        MentorTurnService.Submission question = mentorTurns.submit(
                ws.getId(),
                main.getId(),
                new MentorTurnService.Question(
                        MentorTurn.Surface.APP,
                        "Question in " + ws.getWorkspaceSlug(),
                        UUID.randomUUID().toString(),
                        null,
                        null));

        return new TenantData(
                practiceSlug,
                observationId,
                feedback.getId(),
                question.question().getId(),
                question.turn().getId());
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
    @DisplayName("Observations")
    class Observations {

        @Test
        @WithMentorUser
        void listReturnsOnlyOwnWorkspaceObservations() {
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
                    .isEqualTo("Observation in tenant-a");
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
            seedInAppUnit(workspaceA, "A way of working measured in tenant A");
            seedInAppUnit(workspaceB, "A way of working measured in tenant B");

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
                    .isEqualTo("A way of working measured in tenant A");
        }

        @Test
        @WithMentorUser
        void neverRetiresACardQueuedInAnotherTenant() {
            String sharedKey = FeedbackThreadKey.forPractice(
                    "ships-tests-with-the-change", overlapUser.getId(), FeedbackChannel.IN_APP);
            Feedback inA = seedInAppUnit(workspaceA, "A way of working measured in tenant A", sharedKey);
            Feedback inB = seedInAppUnit(workspaceB, "A way of working measured in tenant B", sharedKey);

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
            Feedback inA = seedInAppUnit(workspaceA, "A way of working measured in tenant A");
            Feedback inB = seedInAppUnit(workspaceB, "A way of working measured in tenant B");

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
        practice.setSignals(PracticeTestEvidence.signals(ScmSignals.PULL_REQUEST_OPENED));
        practice.setEvidenceRequirements(PracticeTestEvidence.needsFor(ScmSignals.PULL_REQUEST_OPENED.artifactKind()));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);
        practice.setAutonomy(PracticeAutonomy.AUTOMATIC);
        practice = practiceRepository.saveAndFlush(practice);
        PracticeRevision revision = practiceRevisionRepository.save(new PracticeRevision(practice, 1));
        practice.setCurrentRevision(revision);
        practice = practiceRepository.saveAndFlush(practice);

        AgentJob job = new AgentJob();
        job.setWorkspace(ws);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(OBJECT_MAPPER.valueToTree(Map.of("model", "test")));
        job.setEvidenceSnapshot(OBJECT_MAPPER.valueToTree(Map.of("manifest", Map.of("contractVersion", "1.2.0"))));
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
                "NOT_MET",
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
                .body(InAppFeedbackBody.render(headline, "The message.", "The way of working to try."))
                .source(FeedbackSource.AGENT)
                .threadKey(threadKey)
                .createdAt(Instant.now())
                .build());
        feedbackObservationRepository.insertIfAbsent(unit.getId(), observationId, "PRIMARY", 0);
        return unit;
    }

    @Nested
    @DisplayName("Mentor conversation")
    class Mentor {

        @Test
        @WithMentorUser
        void shouldListOnlyTheOwnWorkspacesMessagesWhenTheDeveloperBelongsToTwoWorkspaces() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/mentor/entries", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.entries.length()")
                    .isEqualTo(1)
                    .jsonPath("$.entries[0].id")
                    .isEqualTo(tenantA.entryId().toString());
        }

        @Test
        @WithMentorUser
        void detailIsScopedToWorkspace() {
            expectDetailStatus("/mentor/entries/{key}/thread", tenantA.entryId())
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/mentor/entries/{key}/thread", tenantB.entryId())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldLeaveTheOtherWorkspacesTurnQueuedWhenTheDeveloperStopsItThroughThisWorkspace() {
            Map<String, String> stop = Map.of("status", "STOPPED");

            expectMentorWrite(HttpMethod.PATCH, "/mentor/turns/{key}/status", tenantA.turnId(), stop)
                    .isOk()
                    .expectBody(Void.class);
            expectMentorWrite(HttpMethod.PATCH, "/mentor/turns/{key}/status", tenantB.turnId(), stop)
                    .isNotFound()
                    .expectBody(Void.class);

            assertThat(mentorTurnRepository
                            .findById(tenantB.turnId())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(MentorTurn.Status.QUEUED);
        }

        @Test
        @WithMentorUser
        void shouldKeepTheOtherWorkspacesVoteWhenTheDeveloperVotesThroughThisWorkspace() {
            UUID ownAnswer = hephAnswer(workspaceA).getId();
            UUID foreignAnswer = hephAnswer(workspaceB).getId();
            mentorEntries.vote(workspaceB.getId(), foreignAnswer, MentorEntry.Vote.NOT_HELPFUL);
            Map<String, String> helpful = Map.of("vote", "HELPFUL");

            expectMentorWrite(HttpMethod.PUT, "/mentor/entries/{key}/vote", ownAnswer, helpful)
                    .isOk()
                    .expectBody(Void.class);
            expectMentorWrite(HttpMethod.PUT, "/mentor/entries/{key}/vote", foreignAnswer, helpful)
                    .isNotFound()
                    .expectBody(Void.class);
            expectMentorWrite(HttpMethod.DELETE, "/mentor/entries/{key}/vote", foreignAnswer, null)
                    .isNotFound()
                    .expectBody(Void.class);

            assertThat(mentorEntryRepository
                            .findById(foreignAnswer)
                            .orElseThrow()
                            .getVote())
                    .isEqualTo(MentorEntry.Vote.NOT_HELPFUL);
        }

        @Test
        @WithMentorUser
        void shouldKeepTheOtherWorkspacesMessageWhenTheDeveloperDeletesItThroughThisWorkspace() {
            expectMentorWrite(HttpMethod.DELETE, "/mentor/entries/{key}", tenantB.entryId(), null)
                    .isNotFound()
                    .expectBody(Void.class);
            expectMentorWrite(HttpMethod.DELETE, "/mentor/entries/{key}", tenantA.entryId(), null)
                    .isNoContent()
                    .expectBody(Void.class);

            MentorEntry foreign =
                    mentorEntryRepository.findById(tenantB.entryId()).orElseThrow();
            assertThat(foreign.getRedactedAt()).isNull();
            assertThat(foreign.getText()).isEqualTo("Question in " + workspaceB.getWorkspaceSlug());
            assertThat(mentorTurnRepository
                            .findById(tenantB.turnId())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(MentorTurn.Status.QUEUED);
        }

        @Test
        @WithMentorUser
        void shouldOpenNoThreadOnTheOtherWorkspacesMessageWhenTheDeveloperAsksThroughThisWorkspace() {
            enableHeph(workspaceA);
            String ownRequest = UUID.randomUUID().toString();
            String foreignRequest = UUID.randomUUID().toString();

            ask(ownRequest, tenantA.entryId()).isOk().expectBody(Void.class);
            ask(foreignRequest, tenantB.entryId()).isNotFound().expectBody(Void.class);

            assertThat(mentorConversations.findThread(workspaceA.getId(), tenantA.entryId()))
                    .isPresent();
            assertThat(mentorConversations.findThread(workspaceB.getId(), tenantB.entryId()))
                    .isEmpty();
            assertThat(mentorEntries.exists(workspaceA.getId(), foreignRequest)).isFalse();
            assertThat(mentorEntries.exists(workspaceB.getId(), foreignRequest)).isFalse();
        }

        @Test
        @WithMentorUser
        void shouldStreamNoChangeOfTheOtherWorkspaceWhenTheDeveloperWatchesThisWorkspace() {
            List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
            Disposable subscription = webTestClient
                    .get()
                    .uri("/workspaces/{slug}/mentor/events", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .returnResult(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                    .getResponseBody()
                    .filter(event -> event.event() != null)
                    .subscribe(events::add);
            try {
                await().atMost(STREAM_WAIT).until(() -> streamed(events, "snapshot", tenantA.turnId()));
                awaitLedgerListening();

                MentorEntry foreignAnswer = hephAnswer(workspaceB);
                mentorTurns.stop(workspaceB.getId(), tenantB.turnId());
                // Changes reach the stream in commit order, so this change arrives after the foreign ones.
                mentorTurns.stop(workspaceA.getId(), tenantA.turnId());

                await().atMost(STREAM_WAIT)
                        .until(() -> streamed(events, "turn", tenantA.turnId())
                                && streamed(events, "turn", MentorTurn.Status.STOPPED.name()));
                assertThat(events)
                        .noneMatch(event -> mentions(event, tenantB.turnId())
                                || mentions(event, tenantB.entryId())
                                || mentions(event, foreignAnswer.getId())
                                || mentions(event, foreignAnswer.getConversationId()));
            } finally {
                subscription.dispose();
            }
        }
    }

    private StatusAssertions expectMentorWrite(HttpMethod method, String suffix, Object key, @Nullable Object body) {
        WebTestClient.RequestBodySpec request = webTestClient
                .method(method)
                .uri("/workspaces/{slug}" + suffix, workspaceA.getWorkspaceSlug(), key)
                .headers(TestAuthUtils.withCurrentUser());
        WebTestClient.RequestHeadersSpec<?> sent = body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return sent.exchange().expectStatus();
    }

    private StatusAssertions ask(String requestId, UUID parentEntryId) {
        return webTestClient
                .post()
                .uri("/workspaces/{slug}/mentor/turns", workspaceA.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("requestId", requestId, "text", "Follow-up question", "parentEntryId", parentEntryId))
                .exchange()
                .expectStatus();
    }

    /** Binds a mentor model to the workspace, so Heph accepts questions there. */
    private void enableHeph(Workspace ws) {
        var connection = llmConnections.save(LlmCatalogTestFixtures.connection("heph-" + ws.getWorkspaceSlug()));
        var model =
                llmModels.save(LlmCatalogTestFixtures.model(connection, "heph-" + ws.getWorkspaceSlug(), "heph-model"));
        agentBindings.save(LlmCatalogTestFixtures.mentorBinding(ws, model));
    }

    /** One of Heph's answers in the shared developer's main conversation of {@code ws}. */
    private MentorEntry hephAnswer(Workspace ws) {
        MentorConversation main =
                mentorConversations.findOrCreateMain(ws.getId(), overlapUser.getId(), Set.of(overlapUser.getId()));
        return mentorEntries.append(
                ws.getId(), main.getId(), MentorLedgerFixtures.heph("Answer in " + ws.getWorkspaceSlug()));
    }

    /** Waits until this process receives ledger notifications, so the stream does not miss a change. */
    private void awaitLedgerListening() {
        MentorLedgerProbe.on(mentorNotifications).awaitListening(transactions, mentorTurnRepository, STREAM_WAIT);
    }

    private static boolean streamed(List<ServerSentEvent<String>> events, String name, Object content) {
        return events.stream().anyMatch(event -> {
            String data = event.data();
            return name.equals(event.event()) && data != null && data.contains(content.toString());
        });
    }

    private static boolean mentions(ServerSentEvent<String> event, UUID id) {
        String data = event.data();
        return data != null && data.contains(id.toString());
    }

    @Nested
    @DisplayName("Workspace activity roster")
    class WorkspaceActivity {

        @BeforeEach
        void connectActivitySources() {
            connectActivitySource(workspaceA, ensureGitHubProvider());
            connectActivitySource(workspaceB, ensureGitHubProvider());
        }

        @Test
        @WithMentorUser
        void shouldListOnlyWorkspaceContributorsWhenOrganizationsOverlap() {
            Team sharedTeam = seedTeam("shared-team", 900_100L, SHARED_LOGIN, ensureGitHubProvider());
            teamMembershipRepository.save(new TeamMembership(sharedTeam, bobOnlyB, TeamMembership.Role.MEMBER));

            seedActivity(
                    workspaceA,
                    userRepository
                            .findByLoginAndProviderId(
                                    "alice-only-a",
                                    Objects.requireNonNull(
                                            ensureGitHubProvider().getId()))
                            .orElseThrow(),
                    1);
            seedActivity(workspaceB, bobOnlyB, 1);
            List<String> logins = memberActivity(workspaceA).stream()
                    .map(member -> member.person().login())
                    .toList();

            assertThat(logins).contains("alice-only-a").doesNotContain("bob-only-b");
        }

        @Test
        @WithMentorUser
        void countsOnlyOwnWorkspaceActivity() {
            seedActivity(workspaceA, overlapUser, 1);
            seedActivity(workspaceB, overlapUser, 3);

            ActivityPersonDTO mentor = memberActivity(workspaceA).stream()
                    .filter(member -> member.person().login().equals("mentor"))
                    .findFirst()
                    .orElseThrow();

            assertThat(mentor.counts().issuesOpened()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Member activity")
    class MemberActivity {

        @Test
        @WithMentorUser
        void shouldReturnNotFoundWhenOpenWorkMemberOnlyBelongsToAnotherWorkspace() {
            expectDetailStatus("/activity/members/{key}/open-work", "mentor")
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/activity/members/{key}/open-work", bobOnlyB.getLogin())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithMentorUser
        void shouldReturnNotFoundWhenWorkMemberOnlyBelongsToAnotherWorkspace() {
            expectDetailStatus("/activity/work?login={key}", "mentor").isOk().expectBody(Void.class);
            expectDetailStatus("/activity/work?login={key}", bobOnlyB.getLogin())
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

    private void seedActivity(Workspace ws, User actor, int count) {
        var repository = new Repository();
        long nativeId = repositoryIds.incrementAndGet();
        repository.setNativeId(nativeId);
        repository.setProvider(ensureGitHubProvider());
        repository.setName("activity-" + nativeId);
        repository.setNameWithOwner(SHARED_LOGIN + "/activity-" + nativeId);
        repository.setHtmlUrl("https://github.com/" + repository.getNameWithOwner());
        repository = repositoryRepository.save(repository);
        var monitor = new RepositoryToMonitor();
        monitor.setWorkspace(ws);
        monitor.setNameWithOwner(repository.getNameWithOwner());
        monitorRepository.save(monitor);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            activityEventRepository.insertIfAbsent(
                    id,
                    "evt-" + id,
                    ActivityEventType.ISSUE_CREATED.name(),
                    Instant.now().minusSeconds(60),
                    actor.getId(),
                    ws.getId(),
                    repository.getId(),
                    "issue",
                    (long) i);
        }
    }

    private List<ActivityPersonDTO> memberActivity(Workspace workspace) {
        var response = webTestClient
                .get()
                .uri("/workspaces/{slug}/activity/people", workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(ActivityPeopleDTO.class)
                .returnResult()
                .getResponseBody();
        return Objects.requireNonNull(response).people();
    }
}
