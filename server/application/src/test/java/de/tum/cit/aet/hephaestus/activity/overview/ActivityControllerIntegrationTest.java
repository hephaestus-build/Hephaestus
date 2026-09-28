package de.tum.cit.aet.hephaestus.activity.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.activity.ActivityEventRepository;
import de.tum.cit.aet.hephaestus.activity.ActivityEventType;
import de.tum.cit.aet.hephaestus.activity.ActivityTargetType;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityActionDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityBucketDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityOverviewDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivitySummaryDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityWorkPageDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.MemberActivityDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.OpenWorkDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ReviewerDTO.ReviewerState;
import de.tum.cit.aet.hephaestus.activity.overview.dto.TeamRefDTO;
import de.tum.cit.aet.hephaestus.activity.overview.dto.WorkItemDTO;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.Organization;
import de.tum.cit.aet.hephaestus.integration.scm.domain.organization.OrganizationRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer.ReviewState;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
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
    private IssueCommentRepository issueCommentRepository;

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
            assertThat(work(uri -> uri.queryParam("login", zoe.getLogin())).content())
                    .as("the work list agrees with the count")
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

            ActivitySummaryDTO summary = Objects.requireNonNull(
                            status("/summary", uri -> uri.queryParam("login", ada.getLogin()))
                                    .isOk()
                                    .expectBody(ActivityOverviewDTO.class)
                                    .returnResult()
                                    .getResponseBody())
                    .summary();

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
    class Buckets {

        @Test
        void shouldListEveryDayOfTheRangeAddingUpToTheSummaryWhenTheRangeIsAMonth() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -100L, DAY);
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -101L, DAY.plusSeconds(60));
            record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -102L,
                    Instant.parse("2026-01-31T23:59:59Z"));
            record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -103L,
                    Instant.parse("2026-02-01T00:00:00Z"));

            ActivityOverviewDTO overview = overview(uri -> uri.queryParam("login", ada.getLogin()));

            assertThat(overview.bucket()).isEqualTo(ActivityBucketSize.DAY);
            assertThat(overview.buckets())
                    .as("31 days, zeros included, oldest first")
                    .hasSize(31)
                    .extracting(ActivityBucketDTO::start)
                    .startsWith(Instant.parse(FROM))
                    .endsWith(Instant.parse("2026-01-31T00:00:00Z"))
                    .isSorted();
            assertThat(bucketAt(overview, "2026-01-10T00:00:00Z").issuesOpened())
                    .isEqualTo(2);
            assertThat(bucketAt(overview, "2026-01-31T00:00:00Z").comments())
                    .as("the comment at the range's end is outside it")
                    .isEqualTo(1);
            assertThat(bucketAt(overview, "2026-01-11T00:00:00Z"))
                    .isEqualTo(new ActivitySummaryDTO(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            assertThat(sum(overview.buckets().stream()
                            .map(ActivityBucketDTO::summary)
                            .toList()))
                    .isEqualTo(overview.summary());
        }

        @Test
        void shouldCountAnEventOnTheNextDayWhenItIsAfterMidnightInTheZone() {
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -110L,
                    Instant.parse("2026-01-10T23:30:00Z"));

            ActivityOverviewDTO utc = overview(uri -> uri.queryParam("login", ada.getLogin()));
            ActivityOverviewDTO berlin =
                    overview(uri -> uri.queryParam("login", ada.getLogin()).queryParam("zone", "Europe/Berlin"));

            assertThat(bucketAt(utc, "2026-01-10T00:00:00Z").issuesOpened()).isEqualTo(1);
            assertThat(berlin.buckets().getFirst().start())
                    .as("midnight in Berlin of the day the range starts on")
                    .isEqualTo(Instant.parse("2025-12-31T23:00:00Z"));
            assertThat(bucketAt(berlin, "2026-01-10T23:00:00Z").issuesOpened())
                    .as("half past midnight on 11 January in Berlin")
                    .isEqualTo(1);
            assertThat(bucketAt(berlin, "2026-01-09T23:00:00Z").issuesOpened()).isZero();
        }

        /**
         * Production sets the JVM default time zone (to Europe/Berlin), and a database session takes the default its
         * connection opened with. Here the default is changed after that, to a zone 14 hours east of UTC, so the JVM,
         * the session and the asked zone all disagree; a bucket must follow the asked zone alone.
         */
        @Test
        void shouldBucketInTheAskedZoneWhenTheDefaultTimeZoneIsAnother() {
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -116L,
                    Instant.parse("2026-01-10T00:30:00Z"));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -117L,
                    Instant.parse("2026-01-10T23:30:00Z"));
            TimeZone previous = TimeZone.getDefault();
            ActivityOverviewDTO utc;
            try {
                TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"));
                utc = overview(uri -> uri.queryParam("login", ada.getLogin()));
            } finally {
                TimeZone.setDefault(previous);
            }

            assertThat(bucketAt(utc, "2026-01-10T00:00:00Z").issuesOpened())
                    .as("both issues were opened on 10 January in UTC")
                    .isEqualTo(2);
        }

        @Test
        void shouldRejectTheZoneWhenItIsNotAnIanaTimeZone() {
            get("/summary", uri -> uri.queryParam("zone", "Mars/Olympus_Mons"))
                    .isBadRequest()
                    .expectBody(Void.class);
            get("/summary", uri -> uri.queryParam("zone", "+02:00"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        void shouldSizeTheBucketsByTheLengthOfTheRange() {
            ActivityOverviewDTO weeks = overviewOf("2026-01-01T00:00:00Z", "2026-02-02T00:00:00Z");
            ActivityOverviewDTO longestWeeks = overviewOf("2025-08-01T00:00:00Z", "2026-02-01T00:00:00Z");
            ActivityOverviewDTO months = overviewOf("2025-07-31T00:00:00Z", "2026-02-01T00:00:00Z");

            assertThat(weeks.bucket()).as("32 days").isEqualTo(ActivityBucketSize.WEEK);
            assertThat(weeks.buckets())
                    .extracting(ActivityBucketDTO::start)
                    .as("Mondays, from the one before the range starts")
                    .startsWith(Instant.parse("2025-12-29T00:00:00Z"), Instant.parse("2026-01-05T00:00:00Z"))
                    .endsWith(Instant.parse("2026-01-26T00:00:00Z"));
            assertThat(longestWeeks.bucket()).as("184 days").isEqualTo(ActivityBucketSize.WEEK);
            assertThat(months.bucket()).as("185 days").isEqualTo(ActivityBucketSize.MONTH);
            assertThat(months.buckets())
                    .extracting(ActivityBucketDTO::start)
                    .containsExactly(
                            Instant.parse("2025-07-01T00:00:00Z"),
                            Instant.parse("2025-08-01T00:00:00Z"),
                            Instant.parse("2025-09-01T00:00:00Z"),
                            Instant.parse("2025-10-01T00:00:00Z"),
                            Instant.parse("2025-11-01T00:00:00Z"),
                            Instant.parse("2025-12-01T00:00:00Z"),
                            Instant.parse("2026-01-01T00:00:00Z"));
        }

        private ActivityOverviewDTO overviewOf(String from, String to) {
            return Objects.requireNonNull(
                    status("/summary", uri -> uri.queryParam("from", from).queryParam("to", to))
                            .isOk()
                            .expectBody(ActivityOverviewDTO.class)
                            .returnResult()
                            .getResponseBody());
        }

        private static ActivitySummaryDTO bucketAt(ActivityOverviewDTO overview, String start) {
            return overview.buckets().stream()
                    .filter(bucket -> bucket.start().equals(Instant.parse(start)))
                    .findFirst()
                    .orElseThrow()
                    .summary();
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
    class Work {

        @Test
        void shouldListCommentsAndAnApprovalOnOnePullRequestAsOneEntry() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
            record(zoe, ActivityEventType.PULL_REQUEST_OPENED, ActivityTargetType.PULL_REQUEST, zoesWork.getId(), DAY);
            for (int i = 1; i <= 3; i++) {
                record(
                        ada,
                        ActivityEventType.COMMENT_CREATED,
                        ActivityTargetType.ISSUE_COMMENT,
                        comment(zoesWork, ada).getId(),
                        DAY.plusSeconds(60L * i));
            }
            record(
                    ada,
                    ActivityEventType.REVIEW_APPROVED,
                    ActivityTargetType.REVIEW,
                    approval.getId(),
                    DAY.plusSeconds(600));

            ActivityWorkDTO adas = entry(work(uri -> uri.queryParam("login", ada.getLogin())), zoesWork);
            ActivityWorkDTO everyones = entry(work(uri -> uri), zoesWork);

            assertThat(adas.actions())
                    .as("in the order of kinds")
                    .containsExactly(
                            new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1),
                            new ActivityActionDTO(ActivityKind.COMMENTED, 3));
            assertThat(adas.lastOccurredAt()).isEqualTo(DAY.plusSeconds(600));
            assertThat(adas.people()).extracting(person -> person.id()).containsExactly(ada.getId());
            assertThat(Objects.requireNonNull(adas.work()).type()).isEqualTo(WorkItemDTO.WorkItemType.PULL_REQUEST);
            assertThat(everyones.actions())
                    .containsExactly(
                            new ActivityActionDTO(ActivityKind.PULL_REQUEST_OPENED, 1),
                            new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1),
                            new ActivityActionDTO(ActivityKind.COMMENTED, 3));
            assertThat(everyones.people())
                    .as("by name: Ada before Zoe, though a-zoe logs in before z-ada")
                    .extracting(person -> person.id())
                    .containsExactly(ada.getId(), zoe.getId());
        }

        @Test
        void shouldListActivityAsItsOwnEntryWhenItsWorkIsNotKnown() {
            PullRequest tombstoned = pullRequest(zoe, monitored, work -> {
                work.setDeletedAt(DAY);
                return work;
            });
            PullRequestReview onTombstoned = review(tombstoned, ada, PullRequestReview.State.APPROVED);
            UUID unknownReview = record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, -120L, DAY);
            UUID unknownComment = record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -120L,
                    DAY.plusSeconds(60));
            record(
                    ada,
                    ActivityEventType.REVIEW_APPROVED,
                    ActivityTargetType.REVIEW,
                    onTombstoned.getId(),
                    DAY.plusSeconds(120));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    Long.MAX_VALUE,
                    DAY.plusSeconds(180));

            List<ActivityWorkDTO> entries =
                    work(uri -> uri.queryParam("login", ada.getLogin())).content();

            assertThat(entries)
                    .extracting(ActivityWorkDTO::id)
                    .containsExactly(
                            "work:" + Long.MAX_VALUE,
                            "work:" + tombstoned.getId(),
                            "event:" + unknownComment,
                            "event:" + unknownReview);
            assertThat(entries).allSatisfy(entry -> assertThat(entry.work()).isNull());
            assertThat(summary(uri -> uri.queryParam("login", ada.getLogin())).approvals())
                    .as("the count keeps matching the list")
                    .isEqualTo(2);
        }

        @Test
        void shouldWalkEveryEntryOnceNewestFirstWhenNewerActivityArrivesBetweenPages() {
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -130L, DAY);
            // Two entries at one instant, split across a page boundary, so the id decides their order.
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -131L, DAY.plusSeconds(60));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -132L, DAY.plusSeconds(60));
            UUID newer = record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    -133L,
                    DAY.plusSeconds(120));
            record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -136L, DAY.plusSeconds(150));
            record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -134L, DAY.plusSeconds(180));

            ActivityWorkPageDTO first =
                    work(uri -> uri.queryParam("login", ada.getLogin()).queryParam("size", 2));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -135L, DAY.plusSeconds(240));
            ActivityWorkPageDTO second = work(uri -> uri.queryParam("login", ada.getLogin())
                    .queryParam("size", 2)
                    .queryParam("cursor", Objects.requireNonNull(first.nextCursor())));
            ActivityWorkPageDTO third = work(uri -> uri.queryParam("login", ada.getLogin())
                    .queryParam("size", 2)
                    .queryParam("cursor", Objects.requireNonNull(second.nextCursor())));

            List<ActivityWorkDTO> walked = new ArrayList<>(first.content());
            walked.addAll(second.content());
            walked.addAll(third.content());
            assertThat(third.nextCursor()).as("the third page is the last").isNull();
            assertThat(walked)
                    .extracting(ActivityWorkDTO::id)
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder(
                            "work:-134", "work:-136", "event:" + newer, "work:-131", "work:-132", "work:-130")
                    .startsWith("work:-134", "work:-136", "event:" + newer)
                    .endsWith("work:-130")
                    .doesNotContain("work:-135");
            assertThat(walked)
                    .extracting(ActivityWorkDTO::lastOccurredAt)
                    .isSortedAccordingTo(Comparator.reverseOrder());
        }

        /** No end is given, so each page would otherwise read up to its own now. */
        @Test
        void shouldListEveryEntryOnceWhenOlderWorkGetsActivityBetweenPagesOfAnOpenEndedRange() {
            Instant now = Instant.now();
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -170L,
                    now.minus(Duration.ofHours(3)));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -171L,
                    now.minus(Duration.ofHours(2)));
            record(
                    ada,
                    ActivityEventType.ISSUE_CREATED,
                    ActivityTargetType.ISSUE,
                    -172L,
                    now.minus(Duration.ofHours(1)));

            List<String> walked = new ArrayList<>();
            @Nullable String cursor = null;
            do {
                @Nullable String after = cursor;
                ActivityWorkPageDTO page = Objects.requireNonNull(status("/work", uri -> {
                            UriBuilder query =
                                    uri.queryParam("login", ada.getLogin()).queryParam("size", 1);
                            return after == null ? query : query.queryParam("cursor", after);
                        })
                        .isOk()
                        .expectBody(ActivityWorkPageDTO.class)
                        .returnResult()
                        .getResponseBody());
                page.content().forEach(entry -> walked.add(entry.id()));
                if (after == null) {
                    record(ada, ActivityEventType.ISSUE_CLOSED, ActivityTargetType.ISSUE, -170L, Instant.now());
                }
                cursor = page.nextCursor();
            } while (cursor != null);

            assertThat(walked).containsExactly("work:-172", "work:-171", "work:-170");
        }

        @Test
        void shouldAddUpToTheSummaryWhenEveryPageOfOneMembersWorkIsWalked() {
            seedMixedActivity(null);

            assertWorkAddsUpToTheSummary(uri -> uri.queryParam("login", ada.getLogin()));
        }

        @Test
        void shouldAddUpToTheSummaryWhenEveryPageOfWorkspaceWorkIsWalked() {
            seedMixedActivity(null);

            assertThat(assertWorkAddsUpToTheSummary(uri -> uri))
                    .as("every kind is listed, each from its own count")
                    .containsExactlyInAnyOrder(ActivityKind.values());
        }

        @Test
        void shouldListOnlyTheAskedKindsWhenAKindFilterIsGiven() {
            PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
            PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
            record(ada, ActivityEventType.REVIEW_APPROVED, ActivityTargetType.REVIEW, approval.getId(), DAY);
            record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    comment(zoesWork, ada).getId(),
                    DAY.plusSeconds(30));
            record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -140L, DAY.plusSeconds(60));

            ActivityWorkPageDTO reviews = work(uri ->
                    uri.queryParam("login", ada.getLogin()).queryParam("kinds", ActivityKind.REVIEW_APPROVED.name()));

            assertThat(reviews.content()).singleElement().satisfies(entry -> {
                assertThat(entry.actions()).containsExactly(new ActivityActionDTO(ActivityKind.REVIEW_APPROVED, 1));
                assertThat(entry.lastOccurredAt()).isEqualTo(DAY);
                assertThat(Objects.requireNonNull(entry.work()).id()).isEqualTo(zoesWork.getId());
            });
        }

        @Test
        void shouldRejectTheKindWhenItIsUnknown() {
            get("/work", uri -> uri.queryParam("kinds", "NOT_A_KIND"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        @Test
        void shouldRejectThePageSizeWhenItExceedsAHundred() {
            get("/work", uri -> uri.queryParam("size", 101)).isBadRequest().expectBody(Void.class);
        }

        @Test
        void shouldRejectTheCursorWhenItCannotBeRead() {
            get("/work", uri -> uri.queryParam("cursor", "not-a-cursor"))
                    .isBadRequest()
                    .expectBody(Void.class);
        }

        private static ActivityWorkDTO entry(ActivityWorkPageDTO page, Issue work) {
            return page.content().stream()
                    .filter(entry -> entry.id().equals("work:" + work.getId()))
                    .findFirst()
                    .orElseThrow();
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
            assertThat(work(uri -> uri.queryParam("teamId", platform.getId())).content())
                    .as("the team's activity is in repositories the team can access")
                    .singleElement()
                    .satisfies(entry -> assertThat(entry.actions())
                            .containsExactly(new ActivityActionDTO(ActivityKind.PULL_REQUEST_OPENED, 1)));
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
            ActivityWorkPageDTO work = work(uri -> uri.queryParam("teamId", empty.getId()));
            assertThat(work.content()).isEmpty();
            assertThat(work.nextCursor()).isNull();
        }

        @Test
        void shouldAddUpToTheSummaryWhenEveryPageOfATeamsWorkIsWalked() {
            Label platformLabel = label("platform", monitored);
            teamSettingsService
                    .addLabelFilter(workspace, platform.getId(), platformLabel.getId())
                    .orElseThrow();
            seedMixedActivity(monitored);
            record(zoe, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -150L, DAY, unmonitored);

            assertWorkAddsUpToTheSummary(uri -> uri.queryParam("teamId", platform.getId()));
            assertWorkAddsUpToTheSummary(
                    uri -> uri.queryParam("teamId", platform.getId()).queryParam("login", zoe.getLogin()));
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

        @Test
        void shouldListAPullRequestAskingTheMembersTeamWhenTheTeamIsAskedForAReview() {
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(web).andThen(requestingTeam(platform)));

            OpenWorkDTO zoes = openWork(zoe);

            assertThat(zoes.teamReviewRequests().content())
                    .filteredOn(item -> item.id().equals(asking.getId()))
                    .singleElement()
                    .satisfies(item -> assertThat(item.requestedTeams())
                            .as("only the teams Zoe is in")
                            .containsExactly(new TeamRefDTO(web.getId(), "platform-web")));
            assertThat(zoes.reviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
            assertThat(openWork(ada).teamReviewRequests().content())
                    .as("Ada is in no team the pull request asks")
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
        }

        @Test
        void shouldLeaveOutATeamRequestWhenTheMemberIsAskedDirectlyAuthoredItOrGaveAVerdict() {
            PullRequest direct = pullRequest(ada, monitored, requestingTeam(web).andThen(requesting(zoe)));
            PullRequest own = pullRequest(zoe, monitored, requestingTeam(web));
            PullRequest approved = pullRequest(ada, monitored, requestingTeam(web));
            review(approved, zoe, PullRequestReview.State.APPROVED);
            PullRequest sentBack = pullRequest(ada, monitored, requestingTeam(web));
            review(sentBack, zoe, PullRequestReview.State.CHANGES_REQUESTED);
            PullRequest commented = pullRequest(ada, monitored, requestingTeam(web));
            review(commented, zoe, PullRequestReview.State.COMMENTED);
            PullRequest dismissed = pullRequest(ada, monitored, requestingTeam(web));
            PullRequestReview withdrawn = review(dismissed, zoe, PullRequestReview.State.APPROVED);
            withdrawn.setDismissed(true);
            reviewRepository.save(withdrawn);
            PullRequest drafted =
                    pullRequest(ada, monitored, requestingTeam(web).andThen(work -> {
                        work.setDraft(true);
                        return work;
                    }));
            PullRequest elsewhere = pullRequest(ada, unmonitored, requestingTeam(web));

            OpenWorkDTO zoes = openWork(zoe);

            assertThat(zoes.teamReviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .as("a comment is not a verdict, and a dismissed approval no longer stands")
                    .contains(commented.getId(), dismissed.getId())
                    .doesNotContain(
                            direct.getId(),
                            own.getId(),
                            approved.getId(),
                            sentBack.getId(),
                            drafted.getId(),
                            elsewhere.getId());
            assertThat(zoes.reviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .contains(direct.getId());
        }

        @Test
        void shouldKeepATeamRequestWhenTheTeamIsHiddenFromWorkspaceActivity() {
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(web));
            teamSettingsService.updateTeamVisibility(workspace, web.getId(), true);
            teamSettingsService.updateTeamVisibility(workspace, platform.getId(), true);

            assertThat(openWork(zoe).teamReviewRequests().content())
                    .as("hiding a team shapes workspace activity, not what Zoe is asked to do")
                    .extracting(WorkItemDTO::id)
                    .contains(asking.getId());
        }

        @Test
        void shouldLeaveOutARequestToATeamOutsideTheWorkspaceWhenTheMemberIsInIt() {
            Team outside = team("outside", null);
            outside.setOrganization("other-org");
            outside = teamRepository.save(outside);
            teamMembershipRepository.save(new TeamMembership(outside, zoe, TeamMembership.Role.MEMBER));
            PullRequest asking = pullRequest(ada, monitored, requestingTeam(outside));

            assertThat(openWork(zoe).teamReviewRequests().content())
                    .extracting(WorkItemDTO::id)
                    .doesNotContain(asking.getId());
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
        void shouldListReviewersByWhereTheyStandThenByNameWhenAPullRequestIsOpen() {
            User bob = member("bob", "Bob");
            User cleo = member("cleo", "Cleo");
            User dan = member("dan", "Dan");
            PullRequest open = pullRequest(ada, monitored, requesting(dan));
            review(open, cleo, PullRequestReview.State.COMMENTED);
            review(open, bob, PullRequestReview.State.APPROVED);
            review(open, zoe, PullRequestReview.State.APPROVED);
            review(open, zoe, PullRequestReview.State.CHANGES_REQUESTED, DAY.plusSeconds(60));

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(zoe.getId(), ReviewerState.CHANGES_REQUESTED),
                            tuple(bob.getId(), ReviewerState.APPROVED),
                            tuple(cleo.getId(), ReviewerState.COMMENTED),
                            tuple(dan.getId(), ReviewerState.REQUESTED));
            assertThat(reviewersOf(openWork(dan).reviewRequests().content(), open))
                    .as("the review request lists the same reviewers")
                    .hasSize(4);
        }

        @Test
        void shouldShowTheReviewerAsRequestedWhenAReviewIsAskedAgainAfterTheyApproved() {
            PullRequest open = pullRequest(ada, monitored, requesting(zoe));
            review(open, zoe, PullRequestReview.State.APPROVED);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(zoe.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldShowTheLatestVerdictWhenTheReviewerCommentedAfterIt() {
            User bob = member("bob", "Bob");
            PullRequest open = pullRequest(ada, monitored, work -> work);
            review(open, zoe, PullRequestReview.State.APPROVED);
            review(open, zoe, PullRequestReview.State.COMMENTED, DAY.plusSeconds(60));
            review(open, bob, PullRequestReview.State.CHANGES_REQUESTED);
            review(open, bob, PullRequestReview.State.APPROVED, DAY.plusSeconds(60));
            review(open, bob, PullRequestReview.State.COMMENTED, DAY.plusSeconds(120));

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(bob.getId(), ReviewerState.APPROVED), tuple(zoe.getId(), ReviewerState.APPROVED));
        }

        @Test
        void shouldShowTheReviewOverTheRequestWhenGitLabStatedNoReviewerState() {
            User bob = member("bob", "Bob");
            PullRequest mergeRequest = pullRequest(
                    ada, monitored, onGitLab().andThen(requesting(zoe)).andThen(requesting(bob)));
            review(mergeRequest, zoe, PullRequestReview.State.APPROVED);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), mergeRequest))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(zoe.getId(), ReviewerState.APPROVED), tuple(bob.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldKeepAMergeRequestWithTheReadersOwnReviewWhenGitLabStatedNoReviewerState() {
            PullRequest approved = pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada)));
            review(approved, ada, PullRequestReview.State.APPROVED);
            PullRequest commented = pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada)));
            review(commented, ada, PullRequestReview.State.COMMENTED);
            PullRequest waiting = pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada)));

            List<WorkItemDTO> requests = openWork(ada).reviewRequests().content();

            assertThat(requests)
                    .as("GitLab keeps a reviewer listed after they reviewed, so the request stays in the list")
                    .extracting(WorkItemDTO::id)
                    .contains(approved.getId(), commented.getId(), waiting.getId());
            assertThat(reviewersOf(requests, approved))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.APPROVED));
            assertThat(reviewersOf(requests, commented))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.COMMENTED));
            assertThat(reviewersOf(requests, waiting))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldShowTheReviewerWhereGitLabSaysTheyStandWhenGitLabStatesIt() {
            User bob = member("bob", "Bob");
            User cleo = member("cleo", "Cleo");
            User dan = member("dan", "Dan");
            PullRequest mergeRequest = pullRequest(
                    ada,
                    monitored,
                    onGitLab()
                            .andThen(requesting(zoe, ReviewState.APPROVED))
                            .andThen(requesting(bob, ReviewState.REVIEWED))
                            .andThen(requesting(cleo, ReviewState.REQUESTED_CHANGES))
                            .andThen(requesting(dan, ReviewState.REVIEW_STARTED)));
            review(mergeRequest, dan, PullRequestReview.State.APPROVED);

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), mergeRequest))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(
                            tuple(cleo.getId(), ReviewerState.CHANGES_REQUESTED),
                            tuple(zoe.getId(), ReviewerState.APPROVED),
                            tuple(bob.getId(), ReviewerState.COMMENTED),
                            tuple(dan.getId(), ReviewerState.REQUESTED));
        }

        @Test
        void shouldShowTheReviewAsRequestedAgainWhenGitLabSetsTheReviewerBackToUnreviewed() {
            PullRequest reRequested =
                    pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada, ReviewState.UNREVIEWED)));
            review(reRequested, ada, PullRequestReview.State.APPROVED);
            PullRequest withdrawn =
                    pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada, ReviewState.UNAPPROVED)));
            PullRequest approved =
                    pullRequest(zoe, monitored, onGitLab().andThen(requesting(ada, ReviewState.APPROVED)));

            List<WorkItemDTO> requests = openWork(ada).reviewRequests().content();

            assertThat(reviewersOf(requests, reRequested))
                    .as("the approval came before GitLab asked again")
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.REQUESTED));
            assertThat(reviewersOf(requests, withdrawn))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.REQUESTED));
            assertThat(reviewersOf(requests, approved))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(ada.getId(), ReviewerState.APPROVED));
        }

        @Test
        void shouldIgnoreADismissedOrPendingReviewWhenListingReviewers() {
            User bob = member("bob", "Bob");
            PullRequest open = pullRequest(ada, monitored, work -> work);
            review(open, zoe, PullRequestReview.State.COMMENTED);
            PullRequestReview zoesApproval = review(open, zoe, PullRequestReview.State.APPROVED, DAY.plusSeconds(60));
            zoesApproval.setDismissed(true);
            reviewRepository.save(zoesApproval);
            PullRequestReview bobsApproval = review(open, bob, PullRequestReview.State.APPROVED);
            bobsApproval.setDismissed(true);
            bobsApproval.setState(PullRequestReview.State.DISMISSED);
            reviewRepository.save(bobsApproval);
            review(open, bob, PullRequestReview.State.PENDING, DAY.plusSeconds(120));

            assertThat(reviewersOf(openWork(ada).pullRequests().content(), open))
                    .extracting(reviewer -> reviewer.user().id(), ReviewerDTO::state)
                    .containsExactly(tuple(zoe.getId(), ReviewerState.COMMENTED));
        }

        @Test
        void shouldLeaveOutTheAuthorAndBotsWhenListingReviewers() {
            User member = member("review-bot", "Review bot");
            member.setType(User.Type.BOT);
            User bot = userRepository.save(member);
            PullRequest open = pullRequest(ada, monitored, requesting(ada).andThen(requesting(bot)));
            review(open, ada, PullRequestReview.State.COMMENTED);
            review(open, bot, PullRequestReview.State.COMMENTED);
            Issue assigned = issue(monitored, Issue.State.OPEN, ada);

            OpenWorkDTO openWork = openWork(ada);

            assertThat(reviewersOf(openWork.pullRequests().content(), open)).isEmpty();
            assertThat(openWork.issues().content())
                    .filteredOn(item -> item.id().equals(assigned.getId()))
                    .singleElement()
                    .satisfies(item -> assertThat(item.reviewers()).isNull());
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

        private static List<ReviewerDTO> reviewersOf(List<WorkItemDTO> work, PullRequest pullRequest) {
            return Objects.requireNonNull(work.stream()
                    .filter(item -> item.id().equals(pullRequest.getId()))
                    .findFirst()
                    .orElseThrow()
                    .reviewers());
        }

        private Function<PullRequest, PullRequest> onGitLab() {
            return work -> {
                work.setProvider(ensureGitLabProvider());
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
        return requesting(reviewer, null);
    }

    private static Function<PullRequest, PullRequest> requesting(User reviewer, @Nullable ReviewState state) {
        return work -> {
            Map<User, @Nullable ReviewState> reviewers = new HashMap<>();
            work.getRequestedReviewers().forEach(listed -> reviewers.put(listed.getUser(), listed.getReviewState()));
            reviewers.put(reviewer, state);
            work.replaceRequestedReviewers(reviewers, null);
            return work;
        };
    }

    private static Function<PullRequest, PullRequest> requestingTeam(Team team) {
        return work -> {
            Set<Team> teams = new HashSet<>();
            work.getRequestedTeams().forEach(listed -> teams.add(listed.getTeam()));
            teams.add(team);
            work.replaceRequestedTeams(teams);
            return work;
        };
    }

    private ActivitySummaryDTO summary(Function<UriBuilder, UriBuilder> query) {
        return overview(query).summary();
    }

    private ActivityOverviewDTO overview(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(summaryStatus(query)
                .isOk()
                .expectBody(ActivityOverviewDTO.class)
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

    private ActivityWorkPageDTO work(Function<UriBuilder, UriBuilder> query) {
        return Objects.requireNonNull(get("/work", query)
                .isOk()
                .expectBody(ActivityWorkPageDTO.class)
                .returnResult()
                .getResponseBody());
    }

    /**
     * Walks every page of work two entries at a time, for all kinds and for a few, and checks that the actions
     * add up to the summary of the same scope.
     */
    private Set<ActivityKind> assertWorkAddsUpToTheSummary(Function<UriBuilder, UriBuilder> scope) {
        ActivitySummaryDTO summary = summary(scope);
        Map<ActivityKind, Integer> listed = listedActions(scope, List.of());
        Map<ActivityKind, Integer> reviewsAndComments =
                listedActions(scope, List.of(ActivityKind.REVIEW_APPROVED, ActivityKind.COMMENTED));

        assertThat(summary).as("the scope has activity to add up").isNotEqualTo(sum(List.of()));
        assertThat(summaryOf(listed)).isEqualTo(summary);
        assertThat(summaryOf(reviewsAndComments))
                .isEqualTo(new ActivitySummaryDTO(0, 0, 0, summary.approvals(), 0, 0, summary.comments(), 0, 0, 0));
        return listed.keySet();
    }

    private Map<ActivityKind, Integer> listedActions(Function<UriBuilder, UriBuilder> scope, List<ActivityKind> kinds) {
        Map<ActivityKind, Integer> listed = new EnumMap<>(ActivityKind.class);
        List<String> ids = new ArrayList<>();
        @Nullable String cursor = null;
        do {
            @Nullable String after = cursor;
            ActivityWorkPageDTO page = work(uri -> {
                UriBuilder query = scope.apply(uri).queryParam("size", 2);
                kinds.forEach(kind -> query.queryParam("kinds", kind.name()));
                return after == null ? query : query.queryParam("cursor", after);
            });
            page.content().forEach(entry -> {
                ids.add(entry.id());
                entry.actions().forEach(action -> listed.merge(action.kind(), action.count(), Integer::sum));
            });
            cursor = page.nextCursor();
        } while (cursor != null);
        assertThat(ids).doesNotHaveDuplicates();
        return listed;
    }

    /**
     * Activity of every kind, some of it counted and some not, spread over the range and over pull requests,
     * issues and activity whose work is unknown. Recorded in {@code repository} when one is given.
     */
    private void seedMixedActivity(@Nullable Repository repository) {
        PullRequest zoesWork = pullRequest(zoe, monitored, work -> work);
        PullRequest adasWork = pullRequest(ada, monitored, work -> work);
        PullRequestReview approval = review(zoesWork, ada, PullRequestReview.State.APPROVED);
        PullRequestReview changes = review(adasWork, zoe, PullRequestReview.State.CHANGES_REQUESTED);
        PullRequestReview ownReview = review(adasWork, ada, PullRequestReview.State.COMMENTED);
        PullRequestReview zoesComments = review(adasWork, zoe, PullRequestReview.State.COMMENTED);
        PullRequest abandoned = pullRequest(zoe, monitored, work -> work);
        record(
                zoe,
                ActivityEventType.PULL_REQUEST_CLOSED,
                ActivityTargetType.PULL_REQUEST,
                abandoned.getId(),
                DAY.plus(Duration.ofDays(2)),
                repository);
        record(
                zoe,
                ActivityEventType.REVIEW_COMMENTED,
                ActivityTargetType.REVIEW,
                zoesComments.getId(),
                DAY.plusSeconds(240),
                repository);
        record(
                zoe,
                ActivityEventType.PULL_REQUEST_OPENED,
                ActivityTargetType.PULL_REQUEST,
                zoesWork.getId(),
                DAY,
                repository);
        record(
                ada,
                ActivityEventType.PULL_REQUEST_OPENED,
                ActivityTargetType.PULL_REQUEST,
                adasWork.getId(),
                DAY,
                repository);
        record(
                ada,
                ActivityEventType.PULL_REQUEST_MERGED,
                ActivityTargetType.PULL_REQUEST,
                adasWork.getId(),
                DAY.plus(Duration.ofDays(3)),
                repository);
        record(
                ada,
                ActivityEventType.REVIEW_APPROVED,
                ActivityTargetType.REVIEW,
                approval.getId(),
                DAY.plusSeconds(60),
                repository);
        record(
                zoe,
                ActivityEventType.REVIEW_CHANGES_REQUESTED,
                ActivityTargetType.REVIEW,
                changes.getId(),
                DAY.plusSeconds(120),
                repository);
        record(
                ada,
                ActivityEventType.REVIEW_COMMENTED,
                ActivityTargetType.REVIEW,
                ownReview.getId(),
                DAY.plusSeconds(180),
                repository);
        for (int i = 0; i < 3; i++) {
            record(
                    ada,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    comment(zoesWork, ada).getId(),
                    DAY.plus(Duration.ofHours(i)),
                    repository);
            record(
                    zoe,
                    ActivityEventType.COMMENT_CREATED,
                    ActivityTargetType.ISSUE_COMMENT,
                    comment(adasWork, zoe).getId(),
                    DAY.plus(Duration.ofDays(i)),
                    repository);
        }
        record(
                ada,
                ActivityEventType.COMMENT_CREATED,
                ActivityTargetType.ISSUE_COMMENT,
                -160L,
                DAY.plus(Duration.ofDays(5)),
                repository);
        record(
                zoe,
                ActivityEventType.REVIEW_COMMENT_CREATED,
                ActivityTargetType.REVIEW_COMMENT,
                -161L,
                DAY.plus(Duration.ofDays(6)),
                repository);
        record(
                zoe,
                ActivityEventType.ISSUE_CREATED,
                ActivityTargetType.ISSUE,
                -162L,
                DAY.plus(Duration.ofDays(7)),
                repository);
        record(
                zoe,
                ActivityEventType.ISSUE_CLOSED,
                ActivityTargetType.ISSUE,
                -162L,
                DAY.plus(Duration.ofDays(8)),
                repository);
        record(ada, ActivityEventType.ISSUE_CREATED, ActivityTargetType.ISSUE, -163L, Instant.parse(TO), repository);
    }

    private static ActivitySummaryDTO summaryOf(Map<ActivityKind, Integer> counts) {
        return new ActivitySummaryDTO(
                counts.getOrDefault(ActivityKind.PULL_REQUEST_OPENED, 0),
                counts.getOrDefault(ActivityKind.PULL_REQUEST_MERGED, 0),
                counts.getOrDefault(ActivityKind.PULL_REQUEST_CLOSED, 0),
                counts.getOrDefault(ActivityKind.REVIEW_APPROVED, 0),
                counts.getOrDefault(ActivityKind.REVIEW_CHANGES_REQUESTED, 0),
                counts.getOrDefault(ActivityKind.REVIEW_COMMENTED, 0),
                counts.getOrDefault(ActivityKind.COMMENTED, 0),
                counts.getOrDefault(ActivityKind.CODE_COMMENTED, 0),
                counts.getOrDefault(ActivityKind.ISSUE_OPENED, 0),
                counts.getOrDefault(ActivityKind.ISSUE_CLOSED, 0));
    }

    private static ActivitySummaryDTO sum(List<ActivitySummaryDTO> summaries) {
        return summaries.stream()
                .reduce(
                        new ActivitySummaryDTO(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        (a, b) -> new ActivitySummaryDTO(
                                a.pullRequestsOpened() + b.pullRequestsOpened(),
                                a.pullRequestsMerged() + b.pullRequestsMerged(),
                                a.pullRequestsClosed() + b.pullRequestsClosed(),
                                a.approvals() + b.approvals(),
                                a.changeRequests() + b.changeRequests(),
                                a.commentReviews() + b.commentReviews(),
                                a.comments() + b.comments(),
                                a.codeComments() + b.codeComments(),
                                a.issuesOpened() + b.issuesOpened(),
                                a.issuesClosed() + b.issuesClosed()));
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
        // Shaped once stored: a reviewer request is keyed by the pull request's id.
        return pullRequestRepository.save(shape.apply(pullRequestRepository.save(work)));
    }

    private PullRequestReview review(PullRequest pullRequest, User author, PullRequestReview.State state) {
        return review(pullRequest, author, state, DAY);
    }

    private PullRequestReview review(
            PullRequest pullRequest, User author, PullRequestReview.State state, Instant submittedAt) {
        PullRequestReview review = new PullRequestReview();
        review.setNativeId(nativeIds.incrementAndGet());
        review.setProvider(ensureGitHubProvider());
        review.setState(state);
        review.setPullRequest(pullRequest);
        review.setAuthor(author);
        review.setSubmittedAt(submittedAt);
        review.setHtmlUrl(pullRequest.getHtmlUrl() + "#review-" + review.getNativeId());
        return reviewRepository.save(review);
    }

    private IssueComment comment(Issue on, User author) {
        IssueComment comment = new IssueComment();
        comment.setNativeId(nativeIds.incrementAndGet());
        comment.setProvider(ensureGitHubProvider());
        comment.setBody("A comment");
        comment.setHtmlUrl(on.getHtmlUrl() + "#issuecomment-" + comment.getNativeId());
        comment.setAuthorAssociation(AuthorAssociation.MEMBER);
        comment.setAuthor(author);
        comment.setIssue(on);
        return issueCommentRepository.save(comment);
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
