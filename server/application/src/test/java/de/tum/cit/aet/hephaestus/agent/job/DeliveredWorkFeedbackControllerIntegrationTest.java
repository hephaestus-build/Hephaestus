package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacement;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackPlacementRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSource;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorKind;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementAnchorSide;
import de.tum.cit.aet.hephaestus.practices.feedback.PlacementType;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.ObjectMapper;

class DeliveredWorkFeedbackControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final String ENDPOINT = "/workspaces/{slug}/practices/feedback/on-work?url={url}";
    private static final String ORIGIN = "https://gitlab.feedback.test";

    @Autowired
    private WebTestClient client;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    @Autowired
    private IssueRepository issues;

    @Autowired
    private PullRequestRepository pullRequests;

    @Autowired
    private IdentityLinkRepository identities;

    @Autowired
    private AgentJobRepository jobs;

    @Autowired
    private FeedbackRepository feedback;

    @Autowired
    private FeedbackPlacementRepository placements;

    @Autowired
    private IssueCommentRepository comments;

    @Autowired
    private PullRequestReviewCommentRepository inlineComments;

    @Autowired
    private PullRequestReviewThreadRepository threads;

    @Autowired
    private WorkspaceRepository workspaces;

    @Autowired
    private PracticeRepository practices;

    @Autowired
    private ObservationRepository observations;

    @Autowired
    private FeedbackObservationRepository bindings;

    @Autowired
    private ObjectMapper mapper;

    private Workspace workspace;
    private IdentityProvider provider;
    private Repository project;
    private User recipient;
    private User other;
    private Account caller;
    private Account administrator;
    private PullRequest work;
    private Issue issue;
    private AgentJob job;
    private int position;

    @BeforeEach
    void setUp() {
        provider = gitProviderRepository.save(new IdentityProvider(IdentityProviderType.GITLAB, ORIGIN));
        User githubIdentity = persistUser("feedback-github");
        recipient = gitlabUser("feedback-recipient", 80001L);
        other = gitlabUser("feedback-other", 80002L);
        workspace = createWorkspace("own-feedback", "Own feedback", "team", AccountType.ORG, githubIdentity);
        caller = linked(githubIdentity, recipient);
        ensureWorkspaceMembership(workspace, githubIdentity, WorkspaceRole.MEMBER);
        administrator = linked(other);
        ensureWorkspaceMembership(workspace, other, WorkspaceRole.ADMIN);
        var config = new ConnectionConfig.GitLabConfig(
                ORIGIN, null, null, ConnectionConfig.GitLabConfig.SigningMode.PLAINTEXT, Set.of());
        Connection connection = new Connection(workspace, IntegrationKind.GITLAB, ORIGIN, config);
        connection.setDisplayName("GitLab");
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.save(connection);
        project = new Repository();
        project.setNativeId(80003L);
        project.setProvider(provider);
        project.setName("project");
        project.setNameWithOwner("team/project");
        project.setHtmlUrl(ORIGIN + "/team/project");
        project.setDefaultBranch("main");
        project = repositories.save(project);
        RepositoryToMonitor monitor = new RepositoryToMonitor();
        monitor.setWorkspace(workspace);
        monitor.setNameWithOwner(project.getNameWithOwner());
        monitors.save(monitor);
        work = new PullRequest();
        fill(work, 80004L, "/-/merge_requests/5");
        work = pullRequests.save(work);
        issue = new Issue();
        fill(issue, 80005L, "/-/issues/5");
        issue = issues.save(issue);
        job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setConfigSnapshot(mapper.valueToTree(Map.of("model", "test")));
        job = jobs.save(job);
        position = 0;
    }

    @Test
    void shouldReturnOnlyOwnDeliveredInContextMetadataWhenAccountHasSeveralLinkedIdentities() {
        Feedback own = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        placement(
                save(work, other, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED),
                PlacementType.SUMMARY,
                "other-recipient",
                null);
        placement(
                save(issue, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED),
                PlacementType.SUMMARY,
                "other-work",
                null);
        placement(
                save(work, recipient, FeedbackChannel.IN_APP, FeedbackDeliveryState.DELIVERED),
                PlacementType.SUMMARY,
                "private-app",
                null);
        placement(
                save(work, recipient, FeedbackChannel.IN_CHAT, FeedbackDeliveryState.DELIVERED),
                PlacementType.SUMMARY,
                "private-chat",
                null);
        for (FeedbackDeliveryState state : FeedbackDeliveryState.values()) {
            if (state != FeedbackDeliveryState.DELIVERED) save(work, recipient, FeedbackChannel.IN_CONTEXT, state);
        }
        bindPractice(own, "test-meaningful-guidance");
        placement(own, PlacementType.SUMMARY, "gid://gitlab/Note/90001", workUrl(work) + "#note_90001");
        String body = get(caller, workspace, workUrl(work))
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();
        assertThat(body)
                .contains(own.getId().toString(), "test-meaningful-guidance", "Meaningful guidance", "#note_90001")
                .doesNotContain("SECRET BODY", "recipientUserId", "aboutUserId", "postedCommentRef", "deliveryState");
        var response = read(caller, workspace, workUrl(work));
        assertThat(response.feedback())
                .extracting(DeliveredWorkFeedbackItemDTO::id)
                .containsExactly(own.getId());
        assertThat(response.work().id()).isEqualTo(work.getId().toString());
        assertThat(response.feedback().getFirst().practices())
                .containsExactly(new DeliveredFeedbackPracticeDTO("test-meaningful-guidance", "Meaningful guidance"));
        assertThat(response.hasMore()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldUseCapturedGitHubPermalinksWithoutDecodingHistoricalGraphqlIds(boolean pullRequest) {
        connections.deleteAll(connections.findByWorkspaceId(workspace.getId()));
        IdentityProvider github = ensureGitHubProvider();
        var config = new ConnectionConfig.GitHubPatConfig("team", null, Set.of());
        Connection connection = new Connection(workspace, IntegrationKind.GITHUB, "team", config);
        connection.setDisplayName("GitHub");
        ReflectionTestUtils.setField(connection, "state", IntegrationState.ACTIVE);
        connections.save(connection);
        project.setProvider(github);
        project.setHtmlUrl("https://github.com/team/project");
        project = repositories.save(project);
        Issue target = pullRequest ? work : issue;
        target.setProvider(github);
        target.setRepository(project);
        target.setHtmlUrl(project.getHtmlUrl() + (pullRequest ? "/pull/5" : "/issues/5"));
        issues.save(target);
        Feedback own = save(target, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        String permalink = workUrl(target) + "#issuecomment-90001";
        placement(own, PlacementType.SUMMARY, "IC_opaque_new_node", permalink);
        placement(own, PlacementType.SUMMARY, "IC_opaque_historical_node", null);
        assertThat(read(caller, workspace, workUrl(target))
                        .feedback()
                        .getFirst()
                        .placements())
                .extracting(DeliveredWorkFeedbackPlacementDTO::permalink)
                .containsExactlyInAnyOrder(permalink, null);
    }

    @Test
    void shouldKeepAnAdminsRecipientViewOwnWhenTheyCanInspectOtherPeoplesFeedbackElsewhere() {
        save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        Feedback adminsOwn = save(work, other, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        placement(adminsOwn, PlacementType.SUMMARY, "admins-own", null);
        assertThat(read(administrator, workspace, workUrl(work)).feedback())
                .extracting(DeliveredWorkFeedbackItemDTO::id)
                .containsExactly(adminsOwn.getId());
        Account instanceAdmin = persistInstanceAdmin("Own-only instance administrator");
        client.get()
                .uri(ENDPOINT, workspace.getWorkspaceSlug(), workUrl(work))
                .headers(h -> h.setBearerAuth("mock-jwt-admin-" + instanceAdmin.getId()))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.feedback.length()")
                .isEqualTo(0);
    }

    @Test
    void shouldKeepFeedbackTenantScopedWhenAnotherWorkspaceRecordsTheSameRecipientAndWork() {
        Feedback own = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        placement(own, PlacementType.SUMMARY, "own-workspace", null);
        Workspace another = createWorkspace("other-feedback", "Other feedback", "other-team", AccountType.ORG, other);
        AgentJob foreignJob = new AgentJob();
        foreignJob.setWorkspace(another);
        foreignJob.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        foreignJob.setConfigSnapshot(mapper.valueToTree(Map.of("model", "test")));
        foreignJob = jobs.save(foreignJob);
        Feedback foreign = feedback.save(Feedback.builder()
                .agentJobId(foreignJob.getId())
                .workspaceId(another.getId())
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(work.getId())
                .recipientUserId(recipient.getId())
                .aboutUserId(recipient.getId())
                .channel(FeedbackChannel.IN_CONTEXT)
                .position(0)
                .deliveryState(FeedbackDeliveryState.DELIVERED)
                .source(FeedbackSource.AGENT)
                .createdAt(Instant.now())
                .deliveredAt(Instant.now())
                .build());
        placement(foreign, PlacementType.SUMMARY, "foreign-comment", workUrl(work) + "#note_foreign");
        assertThat(read(caller, workspace, workUrl(work)).feedback())
                .extracting(DeliveredWorkFeedbackItemDTO::id)
                .containsExactly(own.getId());
    }

    @Test
    void shouldResolveHistoricalSummaryAndInlineNotesOnlyOnTheExactMirroredWork() {
        Feedback own = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        summary(work, 90001L, workUrl(work) + "#note_90001");
        inline(90002L, workUrl(work) + "/diffs#note_90002");
        summary(issue, 90003L, workUrl(issue) + "#note_90003");
        placement(own, PlacementType.SUMMARY, "gid://gitlab/Note/90001", null);
        placement(own, PlacementType.INLINE, "gid://gitlab/Note/90002", null);
        placement(own, PlacementType.SUMMARY, "gid://gitlab/Note/90003", null);
        placement(own, PlacementType.SUMMARY, "gid://gitlab/Note/99999", null);
        var found = read(caller, workspace, workUrl(work)).feedback().getFirst().placements();
        assertThat(found)
                .extracting(DeliveredWorkFeedbackPlacementDTO::permalink)
                .containsExactlyInAnyOrder(
                        workUrl(work) + "#note_90001", workUrl(work) + "/diffs#note_90002", null, null);
        assertThat(found)
                .filteredOn(row -> row.type() == PlacementType.INLINE)
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.path()).isEqualTo("src/Example.java");
                    assertThat(row.startLine()).isEqualTo(4);
                    assertThat(row.side()).isEqualTo(PlacementAnchorSide.NEW);
                });
    }

    @Test
    void shouldRejectCapturedAndMirroredLinksWhenTheyNameAnotherOriginOrPieceOfWork() {
        Feedback own = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        placement(own, PlacementType.SUMMARY, "opaque-1", "https://evil.test/team/project/-/merge_requests/5#note_1");
        placement(own, PlacementType.SUMMARY, "opaque-2", workUrl(issue) + "#note_2");
        placement(own, PlacementType.SUMMARY, "opaque-3", workUrl(work) + "/../6#note_3");
        summary(work, 90004L, "https://evil.test/#note_4");
        placement(own, PlacementType.SUMMARY, "gid://gitlab/Note/90004", null);
        assertThat(read(caller, workspace, workUrl(work)).feedback().getFirst().placements())
                .allSatisfy(row -> assertThat(row.permalink()).isNull());
    }

    @Test
    void shouldResolveProjectIssueAndWorkItemAliasesWithoutIncludingTheSameNumberedMergeRequest() {
        Feedback expected = save(issue, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        summary(issue, 90005L, workUrl(issue) + "#note_90005");
        placement(expected, PlacementType.SUMMARY, "gid://gitlab/Note/90005", null);
        var result = read(caller, workspace, ORIGIN + "/team/project/-/work_items/5");
        assertThat(result.feedback())
                .extracting(DeliveredWorkFeedbackItemDTO::id)
                .containsExactly(expected.getId());
        assertThat(result.feedback().getFirst().placements().getFirst().permalink())
                .isEqualTo(workUrl(issue) + "#note_90005");
    }

    @Test
    void shouldBoundTheViewAndReportMoreWhenOlderDeliveredFeedbackExists() {
        for (int i = 0; i < 51; i++) {
            Feedback entry = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
            placement(entry, PlacementType.SUMMARY, "comment-" + i, null);
        }
        var result = read(caller, workspace, workUrl(work));
        assertThat(result.feedback()).hasSize(50);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.feedback())
                .extracting(DeliveredWorkFeedbackItemDTO::deliveredAt)
                .isSortedAccordingTo(java.util.Comparator.reverseOrder());
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDeliveryState.class,
            names = {"PARTIALLY_DELIVERED", "PARTIALLY_FAILED", "FAILED"})
    void shouldReturnOnlyRecordedPlacementsWithoutDraftPracticeMetadataWhenDeliveryIsIncomplete(
            FeedbackDeliveryState state) {
        Feedback partial = save(work, recipient, FeedbackChannel.IN_CONTEXT, state);
        bindPractice(partial, "unposted-draft-practice");
        placement(partial, PlacementType.SUMMARY, "summary-landed", workUrl(work) + "#note_90101");
        placement(partial, PlacementType.INLINE, "inline-landed", workUrl(work) + "#note_90102");
        placement(partial, PlacementType.INLINE, null, null);
        placement(partial, PlacementType.INLINE, "   ", null);
        var result = read(caller, workspace, workUrl(work));
        assertThat(result.feedback()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(partial.getId());
            assertThat(item.deliveredAt()).isNull();
            assertThat(item.practices()).isEmpty();
            assertThat(item.placements())
                    .extracting(DeliveredWorkFeedbackPlacementDTO::commentRef)
                    .containsExactlyInAnyOrder("summary-landed", "inline-landed");
        });
        get(caller, workspace, workUrl(work))
                .expectStatus()
                .isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body)
                        .doesNotContain(
                                "SECRET BODY",
                                "unposted-draft-practice",
                                "Meaningful guidance",
                                "deliveryState",
                                "suppressionReason",
                                "proposedPlacements"));
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDeliveryState.class,
            names = {"DELIVERED", "PARTIALLY_DELIVERED", "PARTIALLY_FAILED", "FAILED"})
    void shouldOmitUnitsWithoutConcreteProviderPlacementsRegardlessOfDeliveryState(FeedbackDeliveryState state) {
        save(work, recipient, FeedbackChannel.IN_CONTEXT, state);
        Feedback withoutRef = save(work, recipient, FeedbackChannel.IN_CONTEXT, state);
        placement(withoutRef, PlacementType.SUMMARY, null, workUrl(work) + "#note_90103");
        placement(withoutRef, PlacementType.INLINE, "   ", workUrl(work) + "#note_90104");
        assertThat(read(caller, workspace, workUrl(work)).feedback()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = FeedbackDeliveryState.class,
            names = {"SUPERSEDED", "PREPARED", "AWAITING_APPROVAL", "SUPPRESSED", "DISCARDED"})
    void shouldExcludeUnpublishedOrReplacedUnitsEvenWhenTheyCarryAPlacement(FeedbackDeliveryState state) {
        Feedback hidden = save(work, recipient, FeedbackChannel.IN_CONTEXT, state);
        placement(hidden, PlacementType.SUMMARY, "prior-or-unpublished", workUrl(work) + "#note_90105");
        assertThat(read(caller, workspace, workUrl(work)).feedback()).isEmpty();
    }

    @Test
    void shouldExposeSharedNativeCommentIdentityWhenDifferentFeedbackUnitsReferenceAnEditedSummary() {
        Feedback first = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        Feedback second = save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        placement(first, PlacementType.SUMMARY, "IC_opaque_shared_summary", null);
        placement(second, PlacementType.SUMMARY, "IC_opaque_shared_summary", null);
        assertThat(read(caller, workspace, workUrl(work)).feedback())
                .flatExtracting(DeliveredWorkFeedbackItemDTO::placements)
                .extracting(DeliveredWorkFeedbackPlacementDTO::commentRef)
                .containsExactly("IC_opaque_shared_summary", "IC_opaque_shared_summary");
    }

    @Test
    void shouldRefuseAnonymousAndNonMemberReadsEvenWhenTheWorkspaceIsPublic() {
        workspace.setIsPubliclyViewable(true);
        workspaces.save(workspace);
        client.get()
                .uri(ENDPOINT, workspace.getWorkspaceSlug(), workUrl(work))
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
        get(linked(gitlabUser("outsider", 81000L)), workspace, workUrl(work))
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotReturnFeedbackWhenWorkIsDeletedOrUnmonitoredOrOnAnotherServer() {
        save(work, recipient, FeedbackChannel.IN_CONTEXT, FeedbackDeliveryState.DELIVERED);
        get(caller, workspace, "https://other.test/team/project/-/merge_requests/5")
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        Workspace elsewhere = createWorkspace("feedback-elsewhere", "Elsewhere", "elsewhere", AccountType.ORG, other);
        ensureWorkspaceMembership(elsewhere, recipient, WorkspaceRole.MEMBER);
        get(caller, elsewhere, workUrl(work)).expectStatus().isNotFound().expectBody(Void.class);
        work.setDeletedAt(Instant.now());
        pullRequests.save(work);
        get(caller, workspace, workUrl(work)).expectStatus().isNotFound().expectBody(Void.class);
    }

    @Test
    void shouldRejectMissingUrlAsBadRequest() {
        client.get()
                .uri("/workspaces/{slug}/practices/feedback/on-work", workspace.getWorkspaceSlug())
                .headers(h -> h.setBearerAuth("mock-jwt-member-" + caller.getId()))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
    }

    private static String workUrl(Issue work) {
        return Objects.requireNonNull(work.getHtmlUrl(), "The fixture must have a provider work URL");
    }

    private WebTestClient.ResponseSpec get(Account account, Workspace target, String url) {
        return client.get()
                .uri(ENDPOINT, target.getWorkspaceSlug(), url)
                .headers(h -> h.setBearerAuth("mock-jwt-member-" + account.getId()))
                .exchange();
    }

    private DeliveredWorkFeedbackDTO read(Account account, Workspace target, String url) {
        return Objects.requireNonNull(get(account, target, url)
                .expectStatus()
                .isOk()
                .expectBody(DeliveredWorkFeedbackDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private User gitlabUser(String login, long nativeId) {
        User user = new User();
        user.setNativeId(nativeId);
        user.setProvider(provider);
        user.setLogin(login);
        user.setName(login);
        user.setAvatarUrl("https://example.com/avatar.png");
        user.setHtmlUrl(ORIGIN + "/" + login);
        user.setType(User.Type.USER);
        return userRepository.save(user);
    }

    private Account linked(User... users) {
        Account account = persistAccount("Account " + users[0].getLogin());
        for (User user : users) {
            IdentityLink link = new IdentityLink();
            link.setAccount(account);
            link.setProviderId(Objects.requireNonNull(user.getProvider().getId()));
            link.setSubject(user.getNativeId().toString());
            link.setExternalActorId(user.getId());
            identities.save(link);
        }
        return account;
    }

    private void fill(Issue target, long nativeId, String route) {
        target.setNativeId(nativeId);
        target.setProvider(provider);
        target.setRepository(project);
        target.setNumber(5);
        target.setTitle("Provider work");
        target.setState(Issue.State.OPEN);
        target.setAuthor(recipient);
        target.setHtmlUrl(project.getHtmlUrl() + route);
        target.setCreatedAt(Instant.now());
        target.setUpdatedAt(Instant.now());
    }

    private Feedback save(Issue target, User to, FeedbackChannel channel, FeedbackDeliveryState state) {
        Instant time = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(position);
        return feedback.save(Feedback.builder()
                .agentJobId(job.getId())
                .workspaceId(workspace.getId())
                .artifactKind(target instanceof PullRequest ? ArtifactKinds.PULL_REQUEST : ArtifactKinds.ISSUE)
                .artifactId(target.getId())
                .recipientUserId(to.getId())
                .aboutUserId(to.getId())
                .channel(channel)
                .position(position++)
                .deliveryState(state)
                .body("SECRET BODY must never leave this endpoint")
                .source(FeedbackSource.AGENT)
                .createdAt(time)
                .deliveredAt(state == FeedbackDeliveryState.DELIVERED ? time : null)
                .build());
    }

    private void placement(Feedback parent, PlacementType type, @Nullable String ref, @Nullable String url) {
        placements.save(FeedbackPlacement.builder()
                .feedback(parent)
                .placementType(type)
                .postedCommentRef(ref)
                .postedCommentUrl(url)
                .anchorKind(type == PlacementType.INLINE ? PlacementAnchorKind.LINE : null)
                .anchorPath(type == PlacementType.INLINE ? "src/Example.java" : null)
                .anchorStartLine(type == PlacementType.INLINE ? 4 : null)
                .anchorSide(type == PlacementType.INLINE ? PlacementAnchorSide.NEW : null)
                .build());
    }

    private void summary(Issue target, long nativeId, String url) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeId);
        comment.setProvider(provider);
        comment.setIssue(target);
        comment.setAuthor(recipient);
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setHtmlUrl(url);
        comment.setBody("Native provider text is not fetched by the endpoint");
        comments.save(comment);
    }

    private void inline(long nativeId, String url) {
        PullRequestReviewThread thread = new PullRequestReviewThread();
        thread.setNativeId(nativeId);
        thread.setNodeId("gid://gitlab/Discussion/" + nativeId);
        thread.setProvider(provider);
        thread.setPullRequest(work);
        thread = threads.save(thread);
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(nativeId);
        comment.setProvider(provider);
        comment.setPullRequest(work);
        comment.setThread(thread);
        comment.setPath("src/Example.java");
        comment.setHtmlUrl(url);
        comment.setBody("Inline provider text");
        inlineComments.save(comment);
    }

    private void bindPractice(Feedback parent, String slug) {
        Practice practice = new Practice();
        practice.setWorkspace(workspace);
        practice.setSlug(slug);
        practice.setName("Meaningful guidance");
        practice.setCriteria("Evidence bounded guidance");
        practice.setAutomatedReviewPolicy(PracticeTestEvidence.pullRequest());
        practice.setBindings(PracticeTestEvidence.bindings(ScmSignals.PULL_REQUEST_OPENED));
        practice = practices.save(practice);
        UUID observation = UUID.randomUUID();
        observations.insertIfAbsent(
                observation,
                "own-feedback-observation",
                job.getId(),
                workspace.getId(),
                practice.getId(),
                null,
                "scm.pull_request",
                work.getId(),
                recipient.getId(),
                "An observation",
                "ASSESSED",
                "ABSENT",
                "GOOD",
                "MAJOR",
                null,
                null,
                null,
                Instant.now(),
                "LIVE");
        bindings.insertIfAbsent(parent.getId(), observation, "PRIMARY", 0);
    }
}
