package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityItemDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySummaryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityTimelinePageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.MemberActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembership;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.membership.TeamMembershipRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermission;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.permission.TeamRepositoryPermissionRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitor;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership.WorkspaceRole;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.UriBuilder;

/**
 * The Activity read model against real rows: what counts, whose activity a scope covers, and what is open for
 * one member. Every range but the default one is pinned so a slow run cannot move an event across a boundary.
 */
@WithMentorUser
class ActivityControllerIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final String FROM = "2026-01-01T00:00:00Z";
    private static final String TO = "2026-02-01T00:00:00Z";
    private static final Instant DAY = Instant.parse("2026-01-10T12:00:00Z");

    private static final ParameterizedTypeReference<List<MemberActivityDTO>> MEMBER_LIST =
            new ParameterizedTypeReference<>() {};

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ActivityEventRepository activityEventRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PullRequestReviewRepository reviewRepository;

    @Autowired
    private IssueRepository issueRepository;

    @Autowired
    private LabelRepository labelRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMembershipRepository teamMembershipRepository;

    @Autowired
    private TeamRepositoryPermissionRepository teamRepositoryPermissionRepository;

    @Autowired
    private WorkspaceTeamSettingsService teamSettingsService;

    private final AtomicLong nativeIds = new AtomicLong(770_000);

    private Workspace workspace;
    private Repository monitored;
    private Repository unmonitored;

    /** Logs in as {@code z-ada} but is named Ada, so ordering by name and by login disagree. */
    private User ada;

    /** Logs in as {@code a-zoe} but is named Zoe. */
    private User zoe;

    @BeforeEach
    void seedWorkspace() {
        User caller = persistUser("mentor");
        User owner = persistUser("activity-owner");
        workspace = createWorkspace("activity", "Activity", "activity-org", AccountType.ORG, owner);
        ensureWorkspaceMembership(workspace, caller, WorkspaceRole.MEMBER);
        ada = member("z-ada", "Ada");
        zoe = member("a-zoe", "Zoe");
        monitored = repository("activity-org/widgets", true);
        unmonitored = repository("activity-org/elsewhere", false);
    }

    @Nested
    class Summary {

        @Test
        void shouldCountOnlyTheMembersOwnActivityInTheRangeWhenLoginIsGiven() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, approval.getId(), DAY);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -1L, DAY.plusSeconds(60));
            record(ada, ActivityEventType.COMMENT_CREATED, ActivityTargetType.ISSUE_COMMENT, -2L, DAY.plusSeconds(120));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -3L,
                    Instant.parse("2025-12-31T23:59:59Z"));
            record(zoe, ActivityEventType.PULL_REQUEST_OPENED, ActivityTargetType.PULL_REQUEST, zoesWork.getId(), DAY);

            ActivitySummaryDTO summary = summary(uri -> uri.queryParam("login", ada.getLogin()));

            assertThat(summary.approvals()).isEqualTo(1);
            assertThat(summary.issuesOpened())
                    .as("the issue before the range is left out")
                    .isEqualTo(1);
            assertThat(summary.comments()).isEqualTo(1);
            assertThat(summary.pullRequestsOpened())
                    .as("Zoe's pull request is hers")
                    .isZero();
        }

        @Test
        void shouldNotCountAReviewOfOnesOwnPullRequestWhenTheAuthorReviews() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview ownReview = review(zoesWork, zoe, PullRequestReview.State.COMMENTED);
            PullRequestReview adasReview = review(zoesWork, ada, PullRequestReview.State.COMMENTED);
            record(zoe, ActivityEventType.REVIEW_COMMENTED, ActivityTargetType.REVIEW, ownReview.getId(), DAY);
            record(ada, ActivityEventType.REVIEW_COMMENTED, ActivityTargetType.REVIEW, adasReview.getId(), DAY);

            ActivitySummaryDTO zoes = summary(uri -> uri.queryParam("login", zoe.getLogin()));
            ActivitySummaryDTO adas = summary(uri -> uri.queryParam("login", ada.getLogin()));

            assertThat(zoes.commentReviews()).isZero();
            assertThat(adas.commentReviews()).isEqualTo(1);
            assertThat(timeline(uri -> uri.queryParam("login", zoe.getLogin())).content())
                    .as("the timeline agrees with the count")
                    .isEmpty();
        }

        @Test
        void shouldLeaveAHiddenMemberOutOfWorkspaceActivityWhenTheirOwnSummaryStillCounts() {
            User hidden = member("hidden-hal", "Hal");
            var membership = workspaceMembershipRepository
                    .findByWorkspace_IdAndUser_Id(workspace.getId(), hidden.getId())
                    .orElseThrow();
            membership.setHidden(true);
            workspaceMembershipRepository.save(membership);
            record(hidden, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -10L, DAY);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -11L, DAY);

            assertThat(members(uri -> uri))
                    .extracting(member -> member.user().id())
                    .contains(ada.getId())
                    .doesNotContain(hidden.getId());
            assertThat(summary(uri -> uri).issuesOpened())
                    .as("only Ada's issue is workspace activity")
                    .isEqualTo(1);
            assertThat(summary(uri -> uri.queryParam("login", hidden.getLogin()))
                            .issuesOpened())
                    .isEqualTo(1);
        }

        @Test
        void shouldReturnNotFoundWhenTheLoginIsNoMember() {
            User stranger = persistUser("activity-stranger");

            summaryStatus(uri -> uri.queryParam("login", stranger.getLogin()))
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void shouldNotCountActivityWhenTheActorIsABot() {
            User bot = member("activity-bot", "Activity bot");
            bot.setType(User.Type.BOT);
            record(userRepository.save(bot), ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -12L, DAY);

            assertThat(summary(uri -> uri.queryParam("login", "activity-bot")).issuesOpened())
                    .isZero();
        }

        @Test
        void shouldRejectTheRangeWhenItStartsAfterItEnds() {
            status("/summary", uri -> uri.queryParam("from", TO).queryParam("to", FROM))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        void shouldRejectTheRangeWhenItSpansMoreThanTheMaximum() {
            status(
                            "/summary",
                            uri -> uri.queryParam("from", "2024-12-01T00:00:00Z")
                                    .queryParam("to", TO))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        /** The application clock is the system clock here; an hour's margin keeps the run's own duration out of it. */
        @Test
        void shouldCountTheSevenDaysBeforeNowWhenNoRangeIsGiven() {
            Instant now = Instant.now();
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -60L,
                    now.minus(Duration.ofHours(1)));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -61L, now.minus(Duration.ofDays(8)));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -62L, now.plus(Duration.ofHours(1)));

            ActivitySummaryDTO summary =
                    Objects.requireNonNull(status("/summary", uri -> uri.queryParam("login", ada.getLogin()))
                            .isOk()
                            .expectBody(ActivitySummaryDTO.class)
                            .returnResult()
                            .getResponseBody());

            assertThat(summary.issuesOpened())
                    .as("only the issue within the last seven days, and none from the future")
                    .isEqualTo(1);
        }

        @Test
        void shouldRefuseTheRequestWhenTheCallerIsNoMember() {
            User stranger = persistUser("closed-owner");
            Workspace closed = createWorkspace("activity-closed", "Closed", "closed-org", AccountType.ORG, stranger);

            webTestClient
                    .get()
                    .uri("/workspaces/{slug}/activity/summary", closed.getWorkspaceSlug())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isForbidden()
                    .expectBody(Void.class);
        }
    }

    @Nested
    class Members {

        @Test
        void shouldOrderMembersByNameWhenListingWorkspaceActivity() {
            record(zoe, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -20L, DAY);

            List<MemberActivityDTO> members = members(uri -> uri);
            List<Long> ids = members.stream().map(member -> member.user().id()).toList();

            assertThat(ids).containsSubsequence(ada.getId(), zoe.getId());
            assertThat(members)
                    .filteredOn(member -> member.user().id().equals(zoe.getId()))
                    .singleElement()
                    .satisfies(member ->
                            assertThat(member.summary().issuesOpened()).isEqualTo(1));
        }

        @Test
        void shouldLeaveABotOutWhenListingWorkspaceActivity() {
            User bot = member("activity-member-bot", "Activity bot");
            bot.setType(User.Type.BOT);
            userRepository.save(bot);

            assertThat(members(uri -> uri))
                    .extracting(member -> member.user().id())
                    .contains(ada.getId())
                    .doesNotContain(bot.getId());
        }
    }

    @Nested
    class Timeline {

        @Test
        void shouldWalkEveryEntryOnceNewestFirstWhenNewerActivityArrivesBetweenPages() {
            UUID oldest = record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -30L, DAY);
            // Two entries at one instant, split across the page boundary, so the id decides their order.
            UUID tiedFirst =
                    record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -30L, DAY.plusSeconds(60));
            UUID tiedSecond =
                    record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -31L, DAY.plusSeconds(60));
            UUID newer = record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -32L,
                    DAY.plusSeconds(120));
            UUID newest =
                    record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -31L, DAY.plusSeconds(180));

            ActivityTimelinePageDTO first =
                    timeline(uri -> uri.queryParam("login", ada.getLogin()).queryParam("size", 3));
            UUID arrivedLate =
                    record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -33L, DAY.plusSeconds(240));
            ActivityTimelinePageDTO second = timeline(uri -> uri.queryParam("login", ada.getLogin())
                    .queryParam("size", 3)
                    .queryParam("cursor", Objects.requireNonNull(first.nextCursor())));

            List<ActivityItemDTO> walked = new ArrayList<>(first.content());
            walked.addAll(second.content());
            assertThat(first.content()).hasSize(3);
            assertThat(second.nextCursor()).as("the second page is the last").isNull();
            assertThat(walked)
                    .extracting(item -> UUID.fromString(item.id()))
                    .doesNotHaveDuplicates()
                    .doesNotContain(arrivedLate)
                    .containsExactlyInAnyOrder(newest, newer, tiedFirst, tiedSecond, oldest)
                    .startsWith(newest, newer)
                    .endsWith(oldest);
            assertThat(walked).extracting(ActivityItemDTO::occurredAt).isSortedAccordingTo(Comparator.reverseOrder());
        }

        @Test
        void shouldRejectTheKindWhenItIsUnknown() {
            get("/timeline", uri -> uri.queryParam("kinds", "NOT_A_KIND"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        void shouldRejectThePageSizeWhenItExceedsFifty() {
            get("/timeline", uri -> uri.queryParam("size", 51)).isBadRequest().expectBody(Void.class);
        }

        @Test
        void shouldRejectTheCursorWhenItCannotBeRead() {
            get("/timeline", uri -> uri.queryParam("cursor", "not-a-cursor"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        void shouldListOnlyTheAskedKindsWhenAKindFilterIsGiven() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, approval.getId(), DAY);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -40L, DAY.plusSeconds(60));

            ActivityTimelinePageDTO reviews = timeline(uri ->
                    uri.queryParam("login", ada.getLogin()).queryParam("kinds", ActivityKind.REVIEW_APPROVED.name()));

            assertThat(reviews.content()).singleElement().satisfies(item -> {
                assertThat(item.kind()).isEqualTo(ActivityKind.REVIEW_APPROVED);
                assertThat(item.actor().id()).isEqualTo(ada.getId());
                WorkItemDTO work = Objects.requireNonNull(item.work());
                assertThat(work.id()).isEqualTo(zoesWork.getId());
                assertThat(work.type()).isEqualTo(WorkItemDTO.WorkItemType.PULL_REQUEST);
            });
        }

        @Test
        void shouldKeepTheEntryWithoutWorkWhenThePullRequestIsGoneOrTombstoned() {
            PullRequest live = pullRequest(zoe, monitored, work -> work);
            PullRequest tombstoned = pullRequest(zoe, monitored, work -> {
                work.setDeletedAt(DAY);
                return work;
            });
            long gone = Long.MAX_VALUE;
            record(zoe, ActivityEventType.PULL_REQUEST_OPENED, ActivityTargetType.PULL_REQUEST, live.getId(), DAY);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    tombstoned.getId(),
                    DAY.plusSeconds(60));
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    gone,
                    DAY.plusSeconds(120));

            List<ActivityItemDTO> items =
                    timeline(uri -> uri.queryParam("login", zoe.getLogin())).content();

            assertThat(items).hasSize(3);
            assertThat(items.get(0).work()).as("the pull request row is gone").isNull();
            assertThat(items.get(1).work())
                    .as("the pull request was deleted upstream")
                    .isNull();
            assertThat(Objects.requireNonNull(items.get(2).work()).id()).isEqualTo(live.getId());
            assertThat(summary(uri -> uri.queryParam("login", zoe.getLogin())).pullRequestsOpened())
                    .as("the count keeps matching the timeline")
                    .isEqualTo(3);
        }

        @Test
        void shouldDropTheReviewLinkWhenThePullRequestWasDeletedUpstream() {
            PullRequest tombstoned = pullRequest(zoe, monitored, work -> {
                work.setDeletedAt(DAY);
                return work;
            });
            PullRequestReview approval = review(tombstoned, ada, PullRequestReview.State.APPROVED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, approval.getId(), DAY);

            assertThat(timeline(uri -> uri.queryParam("login", ada.getLogin())).content())
                    .singleElement()
                    .satisfies(item -> {
                        assertThat(item.work()).isNull();
                        assertThat(item.htmlUrl()).isNull();
                    });
        }
    }

    @Nested
    class Teams {

        private Team platform;

        /** A sub-team of {@link #platform}; Zoe is its member and it can write to the monitored repository. */
        private Team web;

        @BeforeEach
        void seedTeams() {
            Organization organization = new Organization();
            organization.setNativeId(nativeIds.incrementAndGet());
            organization.setLogin(workspace.getAccountLogin());
            organization.setHtmlUrl("https://github.com/" + workspace.getAccountLogin());
            organization.setProvider(ensureGitHubProvider());
            workspace.setOrganization(organizationRepository.save(organization));
            workspace = workspaceRepository.save(workspace);

            platform = team("platform", null);
            web = team("platform-web", platform.getId());
            teamMembershipRepository.save(new TeamMembership(web, zoe, TeamMembership.Role.MEMBER));
            teamRepositoryPermissionRepository.save(
                    new TeamRepositoryPermission(web, monitored, TeamRepositoryPermission.PermissionLevel.WRITE));
        }

        @Test
        void shouldCoverOnlyTheTeamAndItsSubTeamsWhenATeamIsGiven() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    zoesWork.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -50L,
                    DAY.plusSeconds(60),
                    unmonitored);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -51L, DAY);

            assertThat(members(uri -> uri.queryParam("teamId", platform.getId())))
                    .extracting(member -> member.user().id())
                    .containsExactly(zoe.getId());
            assertThat(timeline(uri -> uri.queryParam("teamId", platform.getId()))
                            .content())
                    .as("the team's activity is in repositories the team can access")
                    .singleElement()
                    .satisfies(item -> assertThat(item.kind()).isEqualTo(ActivityKind.PULL_REQUEST_OPENED));
            ActivitySummaryDTO summary = summary(uri -> uri.queryParam("teamId", platform.getId()));
            assertThat(summary.pullRequestsOpened()).isEqualTo(1);
            assertThat(summary.issuesOpened()).isZero();
        }

        @Test
        void shouldCountOnlyTheTeamsRepositoriesWhenAMemberIsReadInATeamsScope() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    zoesWork.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -52L,
                    DAY.plusSeconds(60),
                    unmonitored);

            ActivitySummaryDTO summary =
                    summary(uri -> uri.queryParam("login", zoe.getLogin()).queryParam("teamId", platform.getId()));

            assertThat(summary.pullRequestsOpened()).isEqualTo(1);
            assertThat(summary.issuesOpened())
                    .as("the team cannot access the repository the issue is in")
                    .isZero();
        }

        @Test
        void shouldCountOnlyReviewsOnPullRequestsWithTheTeamsLabelWhenTheTeamFiltersByLabel() {
            Label platformLabel = label("platform", monitored);
            PullRequest labeled = pullRequest(ada, monitored, work -> {
                work.getLabels().add(platformLabel);
                return work;
            });
            PullRequest unlabeled = pullRequest(ada, monitored, work -> work);
            PullRequestReview onLabeled = review(labeled, zoe, PullRequestReview.State.COMMENTED);
            PullRequestReview onUnlabeled = review(unlabeled, zoe, PullRequestReview.State.COMMENTED);
            record(
                    zoe,
                    ActivityEventType.REVIEW_COMMENTED,
                    ActivityTargetType.REVIEW,
                    onLabeled.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.REVIEW_COMMENTED,
                    ActivityTargetType.REVIEW,
                    onUnlabeled.getId(),
                    DAY.plusSeconds(60),
                    monitored);
            teamSettingsService
                    .addLabelFilter(workspace, platform.getId(), platformLabel.getId())
                    .orElseThrow();

            assertThat(summary(uri -> uri.queryParam("teamId", platform.getId()))
                            .commentReviews())
                    .isEqualTo(1);
        }

        @Test
        void shouldLeaveOutARepositoryWhenTheTeamHidesItsContributions() {
            Repository gadgets = repository("activity-org/gadgets", true);
            teamRepositoryPermissionRepository.save(
                    new TeamRepositoryPermission(web, gadgets, TeamRepositoryPermission.PermissionLevel.WRITE));
            PullRequest inWidgets = pullRequest(zoe, monitored, work -> work);
            PullRequest inGadgets = pullRequest(zoe, gadgets, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    inWidgets.getId(),
                    DAY,
                    monitored);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    inGadgets.getId(),
                    DAY.plusSeconds(60),
                    gadgets);
            teamSettingsService
                    .updateRepositoryVisibility(workspace, web.getId(), monitored.getId(), true)
                    .orElseThrow();

            assertThat(summary(uri -> uri.queryParam("teamId", platform.getId()))
                            .pullRequestsOpened())
                    .isEqualTo(1);
        }

        @Test
        void shouldNotFindATeamWhenItBelongsToAnotherWorkspace() {
            Workspace other = createWorkspace(
                    "activity-other", "Other", "other-org", AccountType.ORG, persistUser("other-owner"));
            Team elsewhere = team("elsewhere", null);
            elsewhere.setOrganization(other.getAccountLogin());
            elsewhere = teamRepository.save(elsewhere);
            long elsewhereId = elsewhere.getId();

            summaryStatus(uri -> uri.queryParam("teamId", elsewhereId))
                    .isNotFound()
                    .expectBody(Void.class);
        }

        @Test
        void shouldLeaveOutAHiddenSubTeamAndEverythingBelowItWhenTheParentTeamIsRead() {
            Team webUi = team("platform-web-ui", web.getId());
            teamMembershipRepository.save(new TeamMembership(webUi, ada, TeamMembership.Role.MEMBER));
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            record(
                    zoe,
                    ActivityEventType.PULL_REQUEST_OPENED,
                    ActivityTargetType.PULL_REQUEST,
                    zoesWork.getId(),
                    DAY,
                    monitored);
            teamSettingsService.updateTeamVisibility(workspace, web.getId(), true);

            assertThat(members(uri -> uri.queryParam("teamId", platform.getId())))
                    .as("Zoe is only in the hidden sub-team, Ada only in the team below it")
                    .isEmpty();
            assertThat(summary(uri -> uri.queryParam("login", zoe.getLogin()).queryParam("teamId", platform.getId()))
                            .pullRequestsOpened())
                    .as("only the hidden sub-team can access the repository")
                    .isZero();
        }

        @Test
        void shouldListNothingWhenTheTeamHasNoMembers() {
            Team empty = team("platform-empty", null);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -70L, DAY);

            assertThat(members(uri -> uri.queryParam("teamId", empty.getId()))).isEmpty();
            assertThat(summary(uri -> uri.queryParam("teamId", empty.getId())))
                    .isEqualTo(new ActivitySummaryDTO(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            ActivityTimelinePageDTO timeline = timeline(uri -> uri.queryParam("teamId", empty.getId()));
            assertThat(timeline.content()).isEmpty();
            assertThat(timeline.nextCursor()).isNull();
        }

        @Test
        void shouldNotFindATeamWhenItIsHiddenFromWorkspaceActivity() {
            teamSettingsService.updateTeamVisibility(workspace, platform.getId(), true);

            summaryStatus(uri -> uri.queryParam("teamId", platform.getId()))
                    .isNotFound()
                    .expectBody(Void.class);
        }

        private Label label(String name, Repository repository) {
            Label label = new Label();
            label.setNativeId(nativeIds.incrementAndGet());
            label.setProvider(ensureGitHubProvider());
            label.setName(name);
            label.setColor("0e8a16");
            label.setRepository(repository);
            return labelRepository.save(label);
        }

        private Team team(String name, @Nullable Long parentId) {
            Team team = new Team();
            team.setNativeId(nativeIds.incrementAndGet());
            team.setName(name);
            team.setSlug(name);
            team.setOrganization(workspace.getAccountLogin());
            team.setHtmlUrl("https://github.com/orgs/activity-org/teams/" + name);
            team.setPrivacy(Team.Privacy.VISIBLE);
            team.setProvider(ensureGitHubProvider());
            team.setParentId(parentId);
            return teamRepository.save(team);
        }
    }

    @Nested
    class OpenWork {

        @Test
        void shouldListOpenReviewRequestsByOthersInMonitoredRepositoriesWhenAMemberIsAsked() {
            PullRequest requested = pullRequest(zoe, monitored, requesting(ada));
            pullRequest(zoe, monitored, requesting(ada).andThen(draft()));
            pullRequest(zoe, monitored, requesting(ada).andThen(closed()));
            pullRequest(zoe, unmonitored, requesting(ada));
            pullRequest(ada, monitored, requesting(ada));

            assertThat(openWork(ada).reviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .containsExactly(requested.getId());
        }

        @Test
        void shouldListOwnOpenPullRequestsIncludingDraftsWhenAMemberIsAsked() {
            PullRequest open = pullRequest(ada, monitored, work -> work);
            PullRequest drafted = pullRequest(ada, monitored, draft());
            pullRequest(ada, monitored, closed());
            pullRequest(ada, unmonitored, work -> work);
            pullRequest(zoe, monitored, work -> work);

            assertThat(openWork(ada).pullRequests().content())
                    .extracting(WorkItemDTO::id)
                    .containsExactlyInAnyOrder(open.getId(), drafted.getId());
        }

        @Test
        void shouldListOpenAssignedIssuesButNoPullRequestsWhenAMemberIsAsked() {
            Issue assigned = issue(monitored, Issue.State.OPEN, ada);
            issue(monitored, Issue.State.CLOSED, ada);
            issue(unmonitored, Issue.State.OPEN, ada);
            issue(monitored, Issue.State.OPEN, zoe);
            pullRequest(zoe, monitored, work -> {
                work.getAssignees().add(ada);
                return work;
            });

            assertThat(openWork(ada).issues().content()).singleElement().satisfies(item -> {
                assertThat(item.id()).isEqualTo(assigned.getId());
                assertThat(item.type()).isEqualTo(WorkItemDTO.WorkItemType.ISSUE);
            });
        }

        @Test
        void shouldReportNoMoreWorkWhenAListHoldsExactlyItsLimit() {
            for (int i = 0; i < OpenWorkService.LIMIT; i++) {
                pullRequest(ada, monitored, work -> work);
            }

            var pullRequests = openWork(ada).pullRequests();

            assertThat(pullRequests.content()).hasSize(OpenWorkService.LIMIT);
            assertThat(pullRequests.hasMore()).isFalse();
        }

        @Test
        void shouldReportMoreWorkWhenAListExceedsItsLimitByOne() {
            for (int i = 0; i <= OpenWorkService.LIMIT; i++) {
                pullRequest(ada, monitored, work -> work);
            }

            var pullRequests = openWork(ada).pullRequests();

            assertThat(pullRequests.content()).hasSize(OpenWorkService.LIMIT);
            assertThat(pullRequests.hasMore()).isTrue();
        }

        @Test
        void shouldListTheMostRecentlyUpdatedWorkFirst() {
            PullRequest older = pullRequest(ada, monitored, updatedAt(DAY));
            PullRequest newest = pullRequest(ada, monitored, updatedAt(DAY.plusSeconds(7200)));
            PullRequest newer = pullRequest(ada, monitored, updatedAt(DAY.plusSeconds(3600)));

            assertThat(openWork(ada).pullRequests().content())
                    .extracting(WorkItemDTO::id)
                    .containsExactly(newest.getId(), newer.getId(), older.getId());
        }

        @Test
        void shouldLeaveChecksUnknownWhenTheyWereObservedForAnEarlierHead() {
            PullRequest current = pullRequest(ada, monitored, head("aaa", "aaa"));
            PullRequest stale = pullRequest(ada, monitored, head("bbb", "ccc"));

            assertThat(openWork(ada).pullRequests().content())
                    .extracting(WorkItemDTO::id, WorkItemDTO::checks)
                    .containsExactlyInAnyOrder(tuple(current.getId(), CheckState.SUCCESS), tuple(stale.getId(), null));
        }

        @Test
        void shouldReturnNotFoundWhenTheMemberIsUnknown() {
            webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/activity/members/{login}/open-work",
                            workspace.getWorkspaceSlug(),
                            "nobody-here")
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isNotFound()
                    .expectBody(Void.class);
        }

        private OpenWorkDTO openWork(User member) {
            return Objects.requireNonNull(webTestClient
                    .get()
                    .uri(
                            "/workspaces/{slug}/activity/members/{login}/open-work",
                            workspace.getWorkspaceSlug(),
                            member.getLogin())
                    .headers(TestAuthUtils.withCurrentUser())
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectBody(OpenWorkDTO.class)
                    .returnResult()
                    .getResponseBody());
        }

        private static Function<PullRequest, PullRequest> requesting(User reviewer) {
            return work -> {
                work.getRequestedReviewers().add(reviewer);
                return work;
            };
        }

        private static Function<PullRequest, PullRequest> head(String observedSha, String headSha) {
            return work -> {
                work.observeHeadChecks(observedSha, CheckState.SUCCESS, true);
                work.setHeadRefOid(headSha);
                return work;
            };
        }

        private static Function<PullRequest, PullRequest> updatedAt(Instant updatedAt) {
            return work -> {
                work.setUpdatedAt(updatedAt);
                return work;
            };
        }

        private static Function<PullRequest, PullRequest> draft() {
            return work -> {
                work.setDraft(true);
                return work;
            };
        }

        private static Function<PullRequest, PullRequest> closed() {
            return work -> {
                work.setState(Issue.State.CLOSED);
                work.setClosedAt(DAY);
                return work;
            };
        }
    }

    private ActivitySummaryDTO summary(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(summaryStatus(query)
                .isOk()
                .expectBody(ActivitySummaryDTO.class)
                .returnResult()
                .getResponseBody());
    }

    private StatusAssertions summaryStatus(Function<UriBuilder, UriBuilder> query) {
        return get("/summary", query);
    }

    private List<MemberActivityDTO> members(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(get("/members", query)
                .isOk()
                .expectBody(MEMBER_LIST)
                .returnResult()
                .getResponseBody());
    }

    private ActivityTimelinePageDTO timeline(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(get("/timeline", query)
                .isOk()
                .expectBody(ActivityTimelinePageDTO.class)
                .returnResult()
                .getResponseBody());
    }

    /** A read over the pinned range. */
    private StatusAssertions get(String path, Function<UriBuilder, UriBuilder> query) {
        return status(path, uri -> query.apply(uri.queryParam("from", FROM).queryParam("to", TO)));
    }

    private StatusAssertions status(String path, Function<UriBuilder, UriBuilder> query) {
        return webTestClient
                .get()
                .uri(uri -> query.apply(uri.path("/workspaces/{slug}/activity" + path))
                        .build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus();
    }

    private User member(String login, String name) {
        User user = persistUser(login);
        user.setName(name);
        user = userRepository.save(user);
        ensureWorkspaceMembership(workspace, user, WorkspaceRole.MEMBER);
        return user;
    }

    private Repository repository(String nameWithOwner, boolean monitor) {
        Repository repository = new Repository();
        repository.setNativeId(nativeIds.incrementAndGet());
        repository.setProvider(ensureGitHubProvider());
        repository.setName(nameWithOwner.substring(nameWithOwner.indexOf('/') + 1));
        repository.setNameWithOwner(nameWithOwner);
        repository.setHtmlUrl("https://github.com/" + nameWithOwner);
        repository.setDefaultBranch("main");
        repository = repositoryRepository.save(repository);
        if (monitor) {
            RepositoryToMonitor monitorRow = new RepositoryToMonitor();
            monitorRow.setWorkspace(workspace);
            monitorRow.setNameWithOwner(nameWithOwner);
            repositoryToMonitorRepository.save(monitorRow);
        }
        return repository;
    }

    private PullRequest pullRequest(User author, Repository repository, Function<PullRequest, PullRequest> shape) {
        long nativeId = nativeIds.incrementAndGet();
        PullRequest work = new PullRequest();
        work.setNativeId(nativeId);
        work.setProvider(ensureGitHubProvider());
        work.setNumber((int) (nativeId % 100_000));
        work.setTitle("Pull request " + nativeId);
        work.setState(Issue.State.OPEN);
        work.setHtmlUrl(repository.getHtmlUrl() + "/pull/" + nativeId);
        work.setRepository(repository);
        work.setAuthor(author);
        work.setCreatedAt(DAY);
        work.setUpdatedAt(DAY);
        return pullRequestRepository.save(shape.apply(work));
    }

    private PullRequestReview review(PullRequest pullRequest, User author, PullRequestReview.State state) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(nativeIds.incrementAndGet());
        review.setProvider(ensureGitHubProvider());
        review.setState(state);
        review.setPullRequest(pullRequest);
        review.setAuthor(author);
        review.setSubmittedAt(DAY);
        review.setHtmlUrl(pullRequest.getHtmlUrl() + "#review-" + review.getNativeId());
        return reviewRepository.save(review);
    }

    private Issue issue(Repository repository, Issue.State state, User assignee) {
        long nativeId = nativeIds.incrementAndGet();
        Issue issue = new Issue();
        issue.setNativeId(nativeId);
        issue.setProvider(ensureGitHubProvider());
        issue.setNumber((int) (nativeId % 100_000));
        issue.setTitle("Issue " + nativeId);
        issue.setState(state);
        issue.setHtmlUrl(repository.getHtmlUrl() + "/issues/" + nativeId);
        issue.setRepository(repository);
        issue.setAuthor(zoe);
        issue.setCreatedAt(DAY);
        issue.setUpdatedAt(DAY);
        issue.getAssignees().add(assignee);
        return issueRepository.save(issue);
    }

    private UUID record(User actor, ActivityEventType type, ActivityTargetType target, long targetId, Instant at) {
        return record(actor, type, target, targetId, at, null);
    }

    private UUID record(
            User actor,
            ActivityEventType type,
            ActivityTargetType target,
            long targetId,
            Instant at,
            @Nullable Repository repository) {
        UUID id = UUID.randomUUID();
        activityEventRepository.insertIfAbsent(
                id,
                "activity-" + id,
                type.name(),
                at,
                actor.getId(),
                workspace.getId(),
                repository == null ? null : repository.getId(),
                target.getValue(),
                targetId);
        return id;
    }
}
