package de.tum.cit.aet.hephaestus.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPeopleDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonDTO;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionRepository;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelRepository;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceModelAssignmentRepository;
import de.tum.cit.aet.hephaestus.conversation.Conversation;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntry;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryService;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerFixtures;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerNotifications;
import de.tum.cit.aet.hephaestus.conversation.ConversationLedgerProbe;
import de.tum.cit.aet.hephaestus.conversation.ConversationService;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurn;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationTurnService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.testconfig.LlmCatalogTestFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithAdminUser;
import de.tum.cit.aet.hephaestus.testconfig.WithConversationUser;
import de.tum.cit.aet.hephaestus.workspace.Membership.WorkspaceRole;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.InstanceOfAssertFactories;
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

// Overlapping members and identical account logins distinguish tenancy from authentication and organization filters.
class CrossTenantIsolationIntegrationTest extends AbstractWorkspaceIntegrationTest {
    @Autowired
    private PracticeModelTestFixtures modelFixtures;

    private static final String SHARED_LOGIN = "shared-org";
    private static final Duration STREAM_WAIT = Duration.ofSeconds(20);

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ResultRepository practiceResultRepository;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private ConversationService conversations;

    @Autowired
    private ConversationTurnService conversationTurns;

    @Autowired
    private ConversationTurnRepository conversationTurnRepository;

    @Autowired
    private ConversationEntryService conversationEntries;

    @Autowired
    private ConversationEntryRepository conversationEntryRepository;

    @Autowired
    private ConversationLedgerNotifications conversationNotifications;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private LlmConnectionRepository llmConnections;

    @Autowired
    private LlmModelRepository llmModels;

    @Autowired
    private WorkspaceModelAssignmentRepository modelAssignments;

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

    private record TenantData(String practiceSlug, UUID practiceResultId, UUID feedbackId, UUID entryId, UUID turnId) {}

    @BeforeEach
    void provisionTwoTenants() {
        overlapUser = persistUser("conversation");

        User ownerA = persistUser("owner-a");
        workspaceA = createWorkspace("tenant-a", "Tenant A", SHARED_LOGIN, AccountType.ORG, ownerA);
        ensureMembership(workspaceA, overlapUser, WorkspaceRole.ADMIN);
        User aliceOnlyA = persistUser("alice-only-a");
        ensureMembership(workspaceA, aliceOnlyA, WorkspaceRole.MEMBER);

        User ownerB = persistUser("owner-b");
        workspaceB = createWorkspace("tenant-b", "Tenant B", SHARED_LOGIN, AccountType.ORG, ownerB);
        ensureMembership(workspaceB, overlapUser, WorkspaceRole.ADMIN);
        bobOnlyB = persistUser("bob-only-b");
        ensureMembership(workspaceB, bobOnlyB, WorkspaceRole.MEMBER);

        User ownerC = persistUser("owner-c");
        outsiderWorkspace = createWorkspace("tenant-c", "Tenant C", "org-c", AccountType.ORG, ownerC);

        tenantA = seed(workspaceA);
        tenantB = seed(workspaceB);
    }

    private TenantData seed(Workspace ws) {
        String slug = "practice-" + ws.getWorkspaceSlug();
        var work = modelFixtures.work(ws, slug, "scm.pull_request", 1L);
        var result = modelFixtures.result(work, accountId(overlapUser), "Result in " + ws.getWorkspaceSlug());
        var feedback = modelFixtures.feedback(
                result, FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, "Advice in " + ws.getWorkspaceSlug());
        Conversation main = conversations.findOrCreateMain(ws.getId(), accountId(overlapUser));
        ConversationTurnService.Submission question = conversationTurns.submit(
                ws.getId(),
                main.getId(),
                new ConversationTurnService.Question(
                        ConversationTurn.Surface.APP,
                        "Question in " + ws.getWorkspaceSlug(),
                        UUID.randomUUID().toString(),
                        null,
                        null));
        return new TenantData(
                slug,
                result.getId(),
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
        ws.setOrganizationId(org.getId());
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
        @WithConversationUser
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
        @WithConversationUser
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
    @DisplayName("Problems")
    class Results {

        @Test
        @WithConversationUser
        void listReturnsOnlyOwnWorkspaceResults() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/results", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].id")
                    .isEqualTo(tenantA.practiceResultId().toString());
        }

        @Test
        @WithConversationUser
        void detailIsScopedToWorkspace() {
            expectDetailStatus("/practices/results/{key}", tenantA.practiceResultId())
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/practices/results/{key}", tenantB.practiceResultId())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithConversationUser
        void adviceOnTheDetailReadComesOnlyFromThisWorkspace() {
            UUID localFeedback = bindFeedback(
                    tenantA.practiceResultId(), "Feedback in tenant A", Instant.parse("2026-01-01T00:00:00Z"));
            UUID foreignFeedback = bindFeedback(
                    tenantB.practiceResultId(), "Feedback in tenant B", Instant.parse("2026-02-01T00:00:00Z"));

            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/practices/results/{key}",
                            workspaceA.getWorkspaceSlug(),
                            tenantA.practiceResultId())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.feedback[*].id")
                    .value(ids -> assertThat(ids)
                            .asInstanceOf(InstanceOfAssertFactories.LIST)
                            .contains(localFeedback.toString())
                            .doesNotContain(foreignFeedback.toString()));
        }
    }

    private UUID bindFeedback(UUID resultId, String body, Instant createdAt) {
        var result = practiceResultRepository.findById(resultId).orElseThrow();
        return modelFixtures
                .feedback(result, FeedbackDestination.ON_WORK, FeedbackState.DELIVERED, body, createdAt)
                .getId();
    }

    @Nested
    @DisplayName("Feedback reactions")
    class FeedbackFeedbackResponses {

        @Test
        @WithConversationUser
        void reactionsAreScopedToWorkspace() {
            expectDetailStatus("/practices/feedback/{key}/response", tenantA.feedbackId())
                    .isOk();
            expectDetailStatus("/practices/feedback/{key}/response", tenantB.feedbackId())
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Conversation threads")
    class ConversationThreads {

        @Test
        @WithConversationUser
        void shouldListOnlyTheOwnWorkspacesMessagesWhenTheDeveloperBelongsToTwoWorkspaces() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/conversation/entries", workspaceA.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.content.length()")
                    .isEqualTo(1)
                    .jsonPath("$.content[0].id")
                    .isEqualTo(tenantA.entryId().toString());
        }

        @Test
        @WithConversationUser
        void detailIsScopedToWorkspace() {
            expectDetailStatus("/conversation/entries/{key}/thread", tenantA.entryId())
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/conversation/entries/{key}/thread", tenantB.entryId())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithConversationUser
        void shouldLeaveTheOtherWorkspacesTurnQueuedWhenTheDeveloperStopsItThroughThisWorkspace() {
            Map<String, String> stop = Map.of("status", "STOPPED");

            expectConversationWrite(HttpMethod.PATCH, "/conversation/turns/{key}/status", tenantA.turnId(), stop)
                    .isOk()
                    .expectBody(Void.class);
            expectConversationWrite(HttpMethod.PATCH, "/conversation/turns/{key}/status", tenantB.turnId(), stop)
                    .isNotFound()
                    .expectBody(Void.class);

            assertThat(conversationTurnRepository
                            .findById(tenantB.turnId())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(ConversationTurn.Status.QUEUED);
        }

        @Test
        @WithConversationUser
        void shouldKeepTheOtherWorkspacesVoteWhenTheDeveloperVotesThroughThisWorkspace() {
            UUID ownAnswer = conversationAnswer(workspaceA).getId();
            UUID foreignAnswer = conversationAnswer(workspaceB).getId();
            conversationEntries.vote(workspaceB.getId(), foreignAnswer, ConversationEntry.Vote.NOT_HELPFUL);
            Map<String, String> helpful = Map.of("vote", "HELPFUL");

            expectConversationWrite(HttpMethod.PUT, "/conversation/entries/{key}/vote", ownAnswer, helpful)
                    .isOk()
                    .expectBody(Void.class);
            expectConversationWrite(HttpMethod.PUT, "/conversation/entries/{key}/vote", foreignAnswer, helpful)
                    .isNotFound()
                    .expectBody(Void.class);
            expectConversationWrite(HttpMethod.DELETE, "/conversation/entries/{key}/vote", foreignAnswer, null)
                    .isNotFound()
                    .expectBody(Void.class);

            assertThat(conversationEntryRepository
                            .findById(foreignAnswer)
                            .orElseThrow()
                            .getVote())
                    .isEqualTo(ConversationEntry.Vote.NOT_HELPFUL);
        }

        @Test
        @WithConversationUser
        void shouldKeepTheOtherWorkspacesMessageWhenTheDeveloperDeletesItThroughThisWorkspace() {
            expectConversationWrite(HttpMethod.DELETE, "/conversation/entries/{key}", tenantB.entryId(), null)
                    .isNotFound()
                    .expectBody(Void.class);
            expectConversationWrite(HttpMethod.DELETE, "/conversation/entries/{key}", tenantA.entryId(), null)
                    .isNoContent()
                    .expectBody(Void.class);

            ConversationEntry foreign =
                    conversationEntryRepository.findById(tenantB.entryId()).orElseThrow();
            assertThat(foreign.getRedactedAt()).isNull();
            assertThat(foreign.getText()).isEqualTo("Question in " + workspaceB.getWorkspaceSlug());
            assertThat(conversationTurnRepository
                            .findById(tenantB.turnId())
                            .orElseThrow()
                            .getStatus())
                    .isEqualTo(ConversationTurn.Status.QUEUED);
        }

        @Test
        @WithConversationUser
        void shouldOpenNoThreadOnTheOtherWorkspacesMessageWhenTheDeveloperAsksThroughThisWorkspace() {
            enableConversation(workspaceA);
            String ownRequest = UUID.randomUUID().toString();
            String foreignRequest = UUID.randomUUID().toString();

            ask(ownRequest, tenantA.entryId()).isOk().expectBody(Void.class);
            ask(foreignRequest, tenantB.entryId()).isNotFound().expectBody(Void.class);

            assertThat(conversations.findThread(workspaceA.getId(), tenantA.entryId()))
                    .isPresent();
            assertThat(conversations.findThread(workspaceB.getId(), tenantB.entryId()))
                    .isEmpty();
            assertThat(conversationEntries.exists(workspaceA.getId(), foreignRequest))
                    .isFalse();
            assertThat(conversationEntries.exists(workspaceB.getId(), foreignRequest))
                    .isFalse();
        }

        @Test
        @WithConversationUser
        void shouldStreamNoChangeOfTheOtherWorkspaceWhenTheDeveloperWatchesThisWorkspace() {
            List<ServerSentEvent<String>> events = new CopyOnWriteArrayList<>();
            Disposable subscription = webTestClient
                    .get()
                    .uri("/workspaces/{slug}/conversation/events", workspaceA.getWorkspaceSlug())
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

                ConversationEntry foreignAnswer = conversationAnswer(workspaceB);
                conversationTurns.stop(workspaceB.getId(), tenantB.turnId());
                // Changes reach the stream in commit order, so this change arrives after the foreign ones.
                conversationTurns.stop(workspaceA.getId(), tenantA.turnId());

                await().atMost(STREAM_WAIT)
                        .until(() -> streamed(events, "turn", tenantA.turnId())
                                && streamed(events, "turn", ConversationTurn.Status.STOPPED.name()));
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

    private StatusAssertions expectConversationWrite(
            HttpMethod method, String suffix, Object key, @Nullable Object body) {
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
                .uri("/workspaces/{slug}/conversation/turns", workspaceA.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("requestId", requestId, "text", "Follow-up question", "parentEntryId", parentEntryId))
                .exchange()
                .expectStatus();
    }

    /** Binds a conversation model to the workspace, so Hephaestus accepts questions there. */
    private void enableConversation(Workspace ws) {
        var connection = llmConnections.save(LlmCatalogTestFixtures.connection("ask-" + ws.getWorkspaceSlug()));
        var model =
                llmModels.save(LlmCatalogTestFixtures.model(connection, "ask-" + ws.getWorkspaceSlug(), "ask-model"));
        modelAssignments.save(LlmCatalogTestFixtures.conversationBinding(ws, model));
    }

    /** One of Hephaestus's answers in the shared developer's main conversation of {@code ws}. */
    private ConversationEntry conversationAnswer(Workspace ws) {
        Conversation main = conversations.findOrCreateMain(ws.getId(), accountId(overlapUser));
        return conversationEntries.append(
                ws.getId(), main.getId(), ConversationLedgerFixtures.hephaestus("Answer in " + ws.getWorkspaceSlug()));
    }

    /** Waits until this process receives ledger notifications, so the stream does not miss a change. */
    private void awaitLedgerListening() {
        ConversationLedgerProbe.on(conversationNotifications)
                .awaitListening(transactions, conversationTurnRepository, STREAM_WAIT);
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
        @WithConversationUser
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
        @WithConversationUser
        void countsOnlyOwnWorkspaceActivity() {
            seedActivity(workspaceA, overlapUser, 1);
            seedActivity(workspaceB, overlapUser, 3);

            ActivityPersonDTO conversation = memberActivity(workspaceA).stream()
                    .filter(member -> member.person().login().equals("conversation"))
                    .findFirst()
                    .orElseThrow();

            assertThat(conversation.counts().issuesOpened()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Member activity")
    class MemberActivity {
        @BeforeEach
        void connectActivitySource() {
            CrossTenantIsolationIntegrationTest.this.connectActivitySource(workspaceA, ensureGitHubProvider());
        }

        @Test
        @WithConversationUser
        void shouldReturnNotFoundWhenOpenWorkMemberOnlyBelongsToAnotherWorkspace() {
            expectDetailStatus("/activity/members/{key}/open-work", "conversation")
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/activity/members/{key}/open-work", bobOnlyB.getLogin())
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithConversationUser
        void shouldReturnNotFoundWhenWorkMemberOnlyBelongsToAnotherWorkspace() {
            expectDetailStatus("/activity/work?login={key}", "conversation")
                    .isOk()
                    .expectBody(Void.class);
            expectDetailStatus("/activity/work?login={key}", bobOnlyB.getLogin())
                    .isNotFound()
                    .expectBody(Void.class);
        }
    }

    @Nested
    @DisplayName("Teams")
    class Teams {

        @Test
        @WithConversationUser
        void listIsScopedToWorkspaceProvider() {
            linkSyncedOrg(workspaceA, ensureGitHubProvider());
            seedTeam("own-team", 900_200L, SHARED_LOGIN, ensureGitHubProvider());
            seedTeam("foreign-team", 900_201L, SHARED_LOGIN, ensureGitLabProvider());

            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/teams", workspaceA.getWorkspaceSlug())
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
        @WithConversationUser
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
        @WithConversationUser
        void shouldAnswerNotFoundWhenNonMemberCallsScopedEndpoint() {
            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/practices/results", outsiderWorkspace.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        @WithConversationUser
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
                .patch()
                .uri("/workspaces/{slug}/teams/{id}/visibility", workspaceA.getWorkspaceSlug(), teamId)
                .headers(TestAuthUtils.withCurrentUser())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("hidden", true))
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
        monitor.setNativeId(repository.getNativeId());
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
