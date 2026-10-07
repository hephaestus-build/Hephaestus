package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.model.Outcome.MET;
import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_MET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLink;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityLinkRepository;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.Team;
import de.tum.cit.aet.hephaestus.integration.scm.domain.team.TeamRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupService;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspaceGroupSplitDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspacePracticeSplitDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeGroupStandingDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.feedback.inapp.InAppFeedbackService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeGroupStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.PracticeStandingService;
import de.tum.cit.aet.hephaestus.practices.observation.ReviewResultsChangedEvent;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.practices.spi.CurrentDeveloperLookup;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettings;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceTeamRepositorySettingsRepository;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code GET /practices/workspace-overview} over a workspace this class seeds: the owner, the reader and twenty six
 * developers, every one with a standing. Every group shows all its parts: Issues has only four developers with a
 * standing and Craft only three without one. Every count asserted is one of these rows.
 */
class PracticesAcrossWorkspaceIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String URI = "/workspaces/{workspaceSlug}/practices/workspace-overview";

    private static final int DEVELOPERS = 26;
    private static final Instant OLDEST = NOW.minus(Duration.ofDays(20));
    private static final Instant MIDDLE = NOW.minus(Duration.ofDays(10));
    private static final Instant NEWEST = NOW.minus(Duration.ofDays(2));

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PracticesAcrossWorkspaceService acrossWorkspaceService;

    @Autowired
    private PracticeGroupStandingService practiceGroupStandingService;

    @Autowired
    private PracticeStandingService practiceStandingService;

    @Autowired
    private PracticeGroupService practiceGroupService;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private IdentityLinkRepository identityLinkRepository;

    @Autowired
    private RepositoryRepository repositoryRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ThreadPoolTaskScheduler taskScheduler;

    @Autowired
    private WorkspaceTeamRepositorySettingsRepository settingsRepository;

    @Autowired
    private PullRequestRepository pullRequestRepository;

    @Autowired
    private PracticesAcrossWorkspaceCache cache;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private CurrentDeveloperLookup currentDeveloperLookup;

    @Autowired
    private InAppFeedbackService inAppFeedbackService;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Workspace workspace;
    private User reader;
    private PracticeGroup packagingGroup;
    private Practice packaging;
    private Practice testing;
    private Practice issues;
    private Practice craft;
    private int nextNumber = 100;

    @BeforeEach
    void seedWorkspace() {
        // Another test's workspace may have had this one's id.
        cache.invalidateAll();
        User owner = persistUser("across-owner");
        workspace = createWorkspace("across-ws", "Across WS", "across-org", AccountType.ORG, owner);
        packagingGroup = group(workspace, "review-ready-work", "Packaging");
        packaging = persistPractice(workspace, packagingGroup, "explain", "Explain", null);
        testing = persistPractice(workspace, group(workspace, "testing-discipline", "Testing"), "tests", "Tests", null);
        issues = persistPractice(workspace, group(workspace, "actionable-issues", "Issues"), "issue", "Issue", null);
        craft = persistPractice(workspace, group(workspace, "code-craftsmanship", "Craft"), "craft", "Craft", null);

        reader = member("testuser"); // matches @WithUser
        strength(packaging, reader, NEWEST);
        // The owner has a standing too, so every eligible developer has one.
        strength(issues, owner, MIDDLE);
        for (int index = 0; index < DEVELOPERS; index++) {
            User developer = member("across-dev-" + index);
            // Packaging: six each at Needs attention, Mixed feedback and Going well, eight with none.
            if (index < 18) {
                standing(packaging, developer, index);
            }
            // Testing: thirteen developers, four or five at each standing. Five of them are in Packaging too, and
            // only the owner is in neither, so the two groups add up to four more developers than the page total.
            if (index >= 13) {
                standing(testing, developer, index);
            }
            // Issues: three and the owner with a standing, all Going well.
            if (index < 3) {
                strength(issues, developer, MIDDLE);
            }
            // Craft: eight or nine at each standing and three with none.
            if (index > 0) {
                standing(craft, developer, index);
            }
        }
    }

    @Test
    @WithUser
    @DisplayName("every group shows all its parts with the reader counted, however few a part holds")
    void shouldSplitEveryGroupByTheCountsOfDevelopersWithAStanding() {
        // The owner, the reader and twenty six developers are eligible, and every one of them was reviewed.
        read().jsonPath("$.groups[0].split.developers")
                .isEqualTo(28)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourStanding")
                .isEqualTo("STRENGTH")
                .jsonPath(groupPart("review-ready-work", "DEVELOPING"))
                .isEqualTo(6)
                .jsonPath(groupPart("review-ready-work", "MIXED"))
                .isEqualTo(6)
                .jsonPath(groupPart("review-ready-work", "STRENGTH"))
                .isEqualTo(7)
                // The eight developers and the owner without a standing are a fourth part of their own.
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.noneYet")
                .isEqualTo(9)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].yourStanding")
                .isEqualTo("NOT_OBSERVED")
                .jsonPath(groupPart("testing-discipline", "STRENGTH"))
                .isEqualTo(4)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].split.noneYet")
                .isEqualTo(15)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.developers")
                .isEqualTo(28)
                // Four developers with a standing and two empty parts: shown as they are.
                .jsonPath(groupPart("actionable-issues", "STRENGTH"))
                .isEqualTo(4)
                .jsonPath(groupPart("actionable-issues", "DEVELOPING"))
                .isEqualTo(0)
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].split.noneYet")
                .isEqualTo(24)
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].split.developers")
                .isEqualTo(28)
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].yourStanding")
                .isEqualTo("NOT_OBSERVED")
                // Only three at none yet, the reader among them: shown as they are.
                .jsonPath(groupPart("code-craftsmanship", "STRENGTH"))
                .isEqualTo(8)
                .jsonPath(groupPart("code-craftsmanship", "MIXED"))
                .isEqualTo(9)
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].split.noneYet")
                .isEqualTo(3)
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].yourStanding")
                .isEqualTo("NOT_OBSERVED");
        // The reader's own figures, then the middle half of all twenty eight.
        tiles("ALL_TIME")
                .jsonPath("$.window")
                .isEqualTo("ALL_TIME")
                .jsonPath("$.developersWithAStandingInWindow")
                .isEqualTo(28)
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1)
                .jsonPath("$.practicesGoingWell.yours")
                .isEqualTo(1)
                // Sixteen developers with no practice going well, four with one, five with two, three with three.
                .jsonPath("$.practicesGoingWell.middle.low")
                .isEqualTo(0)
                .jsonPath("$.practicesGoingWell.middle.high")
                .isEqualTo(2)
                .jsonPath("$.practicesNeedingAttention.yours")
                .isEqualTo(0);
    }

    @Test
    @WithUser
    @DisplayName("each practice of a group splits on its own over the same developers, the reader in their own bucket")
    void shouldSplitEachPracticeOfAGroupOnItsOwn() {
        Practice small = persistPractice(workspace, packagingGroup, "small", "Small", null);
        // The second practice says of everyone what the first says, so together they single nobody out.
        strength(small, reader, NEWEST);
        for (int index = 0; index < 18; index++) {
            standing(small, developer("across-dev-" + index), index);
        }

        read().jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices.length()")
                .isEqualTo(2)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'explain')]"
                        + ".yourStanding")
                .isEqualTo("STRENGTH")
                .jsonPath(practicePart("review-ready-work", "explain", "STRENGTH"))
                .isEqualTo(7)
                .jsonPath(practicePart("review-ready-work", "small", "DEVELOPING"))
                .isEqualTo(6)
                // A group with one practice names it, split as the group is.
                .jsonPath(practicePart("actionable-issues", "issue", "STRENGTH"))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("every reader, and a caller nothing was read for, sees the same practices in catalog order")
    void shouldListTheSamePracticesWhenReadersDifferInTheirOwnEvidence() {
        Practice atomic = persistPractice(workspace, packagingGroup, "atomic", "Atomic commits", null);
        for (int index = 0; index < 18; index++) {
            standing(atomic, developer("across-dev-" + index), index);
        }
        // Only the reader has evidence on a practice review is no longer admitted for.
        Practice retired = persistPractice(workspace, packagingGroup, "retired", "Retired", null);
        strength(retired, reader, NEWEST);
        retired.setAutonomy(PracticeAutonomy.OFF);
        practiceRepository.saveAndFlush(retired);

        List<List<Object>> withEvidence = shapes(readAs(reader));
        List<List<Object>> without = shapes(readAs(developer("across-dev-1")));
        List<List<Object>> nothingRead = shapes(readAs(null));

        assertThat(withEvidence).isEqualTo(without).isEqualTo(nothingRead);
        PracticesAcrossWorkspaceDTO page = readAs(null);
        assertThat(page.groups())
                .filteredOn(group -> group.groupSlug().equals("review-ready-work"))
                .singleElement()
                .satisfies(group -> assertThat(group.practices())
                        .extracting(WorkspacePracticeSplitDTO::practiceSlug, WorkspacePracticeSplitDTO::yourStanding)
                        .containsExactly(tuple("atomic", null), tuple("explain", null)));
    }

    @Test
    @WithUser
    @DisplayName("a practice whose split falls short of its group's by fewer than three shows all its parts")
    void shouldSplitAPracticeThatFallsShortOfItsGroupByFewerThanThree() {
        Practice small = persistPractice(workspace, packagingGroup, "small", "Small", null);
        // The second practice says of seventeen developers what the first says and nothing of the eighteenth or the
        // reader: the group less it counts the two it leaves out, and the page shows it all the same.
        for (int index = 0; index < 17; index++) {
            standing(small, developer("across-dev-" + index), index);
        }

        read().jsonPath(practicePart("review-ready-work", "explain", "STRENGTH"))
                .isEqualTo(7)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'small')]"
                        + ".yourStanding")
                .isEqualTo("NOT_OBSERVED")
                .jsonPath(practicePart("review-ready-work", "small", "DEVELOPING"))
                .isEqualTo(6)
                .jsonPath(practicePart("review-ready-work", "small", "MIXED"))
                .isEqualTo(6)
                .jsonPath(practicePart("review-ready-work", "small", "STRENGTH"))
                .isEqualTo(5)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'small')]"
                        + ".split.noneYet")
                .isEqualTo(11);
    }

    @Test
    @WithUser
    @DisplayName("open feedback counts what the reader's profile shows open, and reading it delivers nothing")
    void shouldCountOpenFeedbackByTheProfilesRuleWithoutDeliveringIt() {
        AgentJob run = persistPullRequestReview(workspace, nextNumber, NEWEST);
        UUID slip = observe(craft, run, nextNumber++, reader, NOT_MET, Severity.MAJOR, NEWEST);
        Feedback open = persistInAppFeedback(
                run,
                reader,
                1,
                FeedbackDeliveryState.PREPARED,
                InAppFeedbackBody.render("Open", "Open.", "Fix."),
                NEWEST);
        bind(open, slip);
        Feedback addressed = persistInAppFeedback(
                run,
                reader,
                2,
                FeedbackDeliveryState.DELIVERED,
                InAppFeedbackBody.render("Addressed", "Addressed.", "Fix."),
                NEWEST);
        bind(addressed, slip);
        markAddressed(addressed, reader, NEWEST.plus(Duration.ofHours(1)));

        read().jsonPath("$.openFeedback.yours")
                .isEqualTo(1)
                // Nobody else has feedback, so the middle half of every eligible developer is none, now.
                .jsonPath("$.openFeedback.middle.low")
                .isEqualTo(0)
                .jsonPath("$.openFeedback.middle.high")
                .isEqualTo(0);
        assertThat(feedbackRepository.findById(open.getId()))
                .map(Feedback::getDeliveryState)
                .contains(FeedbackDeliveryState.PREPARED);
    }

    @Test
    @WithUser
    @DisplayName("each window moves only the tiles; the splits count the current standing")
    void shouldCountTheSplitsByTheCurrentStandingWhenTheWindowChanges() {
        // Testing and issue standings move forty days back, in order, before the last 30 days but inside the
        // profile's ninety; packaging and craft stay inside both.
        jdbc.update(
                "UPDATE observation SET observed_at = observed_at - interval '40 days'"
                        + " WHERE workspace_id = ? AND practice_id IN (?, ?)",
                workspace.getId(),
                testing.getId(),
                issues.getId());

        tiles("DAYS_30")
                .jsonPath("$.window")
                .isEqualTo("DAYS_30")
                // The owner's only standing moved out of the window, which the tiles count by.
                .jsonPath("$.developersWithAStandingInWindow")
                .isEqualTo(27);
        tiles("DAYS_90").jsonPath("$.developersWithAStandingInWindow").isEqualTo(28);
        // The splits still count the owner, whose profile still shows the standing.
        read().jsonPath("$.groups[0].split.developers")
                .isEqualTo(28)
                .jsonPath(groupPart("testing-discipline", "STRENGTH"))
                .isEqualTo(4)
                .jsonPath(groupPart("review-ready-work", "STRENGTH"))
                .isEqualTo(7);
    }

    @Test
    @WithUser
    @DisplayName("evidence older than ninety days counts in the all time tiles but in no split")
    void shouldLeaveEvidenceOlderThanTheProfileOutOfTheSplitsWhenTheWindowIsAllTime() {
        // Every review moves a hundred days back, in order: the ninety day window reads nobody, all time everyone, and
        // no profile shows a standing any more.
        jdbc.update(
                "UPDATE observation SET observed_at = observed_at - interval '100 days' WHERE workspace_id = ?",
                workspace.getId());

        tiles("DAYS_90")
                .jsonPath("$.developersWithAStandingInWindow")
                .isEqualTo(0)
                .jsonPath("$.reviewedWork.middle")
                .doesNotExist();
        tiles("ALL_TIME").jsonPath("$.developersWithAStandingInWindow").isEqualTo(28);
        read().jsonPath("$.groups[?(@.split.developers != 0)]")
                .isEmpty()
                .jsonPath("$.groups[*].yourStanding")
                .isEmpty();
    }

    @Test
    @DisplayName("the reader's marker is the standing their practice profile shows")
    void shouldMarkTheReaderByTheirProfileStandingWhenTheWindowIsShorterThanTheProfile() {
        // Two slips sixty and fifty days back: inside the profile's ninety days, outside the last 30 days.
        problem(packaging, reader, NOW.minus(Duration.ofDays(60)));
        problem(packaging, reader, NOW.minus(Duration.ofDays(50)));

        PracticesAcrossWorkspaceDTO page = readAs(reader);
        CurrentScmIdentityHolder.set(reader.getId(), reader.getLogin(), Set.of(reader.getId()));
        Map<String, PracticeGroupStandingDTO.Standing> profileGroups;
        Map<String, PracticeStandingDTO.Standing> profilePractices;
        try {
            profileGroups = practiceGroupStandingService
                    .getGroupStandings(
                            workspace.getId(),
                            practiceGroupService.listGroups(
                                    WorkspaceContext.fromWorkspace(workspace, null, null), true))
                    .stream()
                    .collect(Collectors.toMap(PracticeGroupStandingDTO::groupSlug, PracticeGroupStandingDTO::standing));
            profilePractices = practiceStandingService.getStandings(workspace.getId()).stream()
                    .collect(Collectors.toMap(PracticeStandingDTO::slug, PracticeStandingDTO::standing));
        } finally {
            CurrentScmIdentityHolder.clear();
        }

        assertThat(page.groups())
                .isNotEmpty()
                .allSatisfy(group -> assertThat(group.yourStanding()).isEqualTo(profileGroups.get(group.groupSlug())));
        assertThat(page.groups().stream().flatMap(group -> group.practices().stream()))
                .isNotEmpty()
                .allSatisfy(practice ->
                        assertThat(practice.yourStanding()).isEqualTo(profilePractices.get(practice.practiceSlug())));
        // Read over the last 30 days alone the slips would be missing and the marker would say Going well.
        assertThat(page.groups())
                .filteredOn(group -> group.groupSlug().equals("review-ready-work"))
                .singleElement()
                .extracting(WorkspaceGroupSplitDTO::yourStanding)
                .isEqualTo(PracticeGroupStandingDTO.Standing.MIXED);
    }

    @Test
    @WithUser
    @DisplayName("two others and the reader see every split and every band, a part of one included")
    void shouldShowEverythingWhenOnlyTwoOthersHaveAStanding() {
        jdbc.update("""
                DELETE FROM observation WHERE workspace_id = ? AND about_user_id IN (
                    SELECT id FROM "user" WHERE login LIKE 'across-%' AND login NOT IN
                    ('across-dev-0', 'across-dev-1'))
                """, workspace.getId());

        read().jsonPath("$.groups[0].split.developers")
                .isEqualTo(3)
                .jsonPath(groupPart("review-ready-work", "DEVELOPING"))
                .isEqualTo(1)
                .jsonPath(groupPart("review-ready-work", "MIXED"))
                .isEqualTo(1)
                .jsonPath(groupPart("review-ready-work", "STRENGTH"))
                .isEqualTo(1)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.noneYet")
                .isEqualTo(0)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourStanding")
                .isEqualTo("STRENGTH");
        tiles("ALL_TIME")
                .jsonPath("$.developersWithAStandingInWindow")
                .isEqualTo(3)
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1)
                .jsonPath("$.reviewedWork.middle")
                .exists()
                // Each of the three has one practice going well.
                .jsonPath("$.practicesGoingWell.middle.low")
                .isEqualTo(1)
                .jsonPath("$.practicesGoingWell.middle.high")
                .isEqualTo(1);
    }

    @Test
    @WithUser
    @DisplayName("hidden members and other workspaces' developers stay out of every count")
    void shouldCountNeitherHiddenMembersNorAnotherWorkspace() {
        jdbc.update("""
                UPDATE workspace_membership SET hidden = true WHERE workspace_id = ? AND user_id IN (
                    SELECT id FROM "user" WHERE login IN ('across-dev-0', 'across-dev-3'))
                """, workspace.getId());
        Workspace elsewhere = createWorkspace(
                "across-elsewhere", "Elsewhere", "elsewhere-org", AccountType.ORG, persistUser("x-owner"));
        Practice elsewherePractice = persistPractice(
                elsewhere, group(elsewhere, "review-ready-work", "Packaging"), "explain", "Explain", null);
        for (int index = 0; index < 6; index++) {
            User developer = persistUser("elsewhere-dev-" + index);
            ensureWorkspaceMembership(elsewhere, developer, WorkspaceMembership.WorkspaceRole.MEMBER);
            AgentJob run = persistPullRequestReview(elsewhere, nextNumber, NEWEST);
            observe(elsewherePractice, run, nextNumber++, developer, NOT_MET, Severity.MAJOR, NEWEST);
        }

        read().jsonPath("$.groups[0].split.developers")
                .isEqualTo(26)
                // Two of the six at Needs attention are hidden, which leaves four there.
                .jsonPath(groupPart("review-ready-work", "DEVELOPING"))
                .isEqualTo(4)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.noneYet")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("an instance administrator viewing as the developer reads the developer's own page")
    void shouldReadTheViewedDevelopersPageWhenAnAdministratorViewsIt() {
        readAsUserView(URI, workspace, reader)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourStanding")
                .isEqualTo("STRENGTH");
    }

    /**
     * Both reads scan the workspace once, so six more developers with a standing add no statement to either: the
     * count grows with the workspace's practices and groups, never with its developers.
     */
    @Test
    @WithUser
    @DisplayName("the overview and the tiles run as many statements for six more developers as without them")
    void shouldRunTheSameStatementsWhenTheWorkspaceCountsMoreDevelopers() throws InterruptedException {
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        // The statement count spans the whole session factory, so a scheduled task would be counted with the reads.
        boolean wasRunning = taskScheduler.isRunning();
        try {
            if (wasRunning) {
                var paused = new CountDownLatch(1);
                taskScheduler.stop(paused::countDown);
                assertThat(paused.await(10, TimeUnit.SECONDS))
                        .as("scheduled tasks already running finish")
                        .isTrue();
            }
            statistics.setStatisticsEnabled(true);
            List<Long> before = statementsPerRead(statistics);
            for (int index = 0; index < 6; index++) {
                User developer = member("across-more-" + index);
                standing(packaging, developer, index);
                standing(craft, developer, index);
            }

            assertThat(statementsPerRead(statistics)).isEqualTo(before);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
            if (wasRunning) {
                taskScheduler.start();
            }
        }
    }

    /**
     * Evidence that cites a source, as every review since frozen workspace folders does, is checked against the
     * source's owner once per read, not once per citation: six more developers whose every observation cites a
     * repository add no statement either.
     */
    @Test
    @WithUser
    @DisplayName("the overview and the tiles check a cited repository once per read, however many observations cite it")
    void shouldRunTheSameStatementsWhenMoreObservationsCiteARepository() throws InterruptedException {
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        boolean wasRunning = taskScheduler.isRunning();
        try {
            if (wasRunning) {
                var paused = new CountDownLatch(1);
                taskScheduler.stop(paused::countDown);
                assertThat(paused.await(10, TimeUnit.SECONDS))
                        .as("scheduled tasks already running finish")
                        .isTrue();
            }
            statistics.setStatisticsEnabled(true);
            citingRepository(member("across-citing-first"));
            List<Long> before = statementsPerRead(statistics);
            for (int index = 0; index < 6; index++) {
                citingRepository(member("across-citing-" + index));
            }

            assertThat(statementsPerRead(statistics)).isEqualTo(before);
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
            if (wasRunning) {
                taskScheduler.start();
            }
        }
    }

    /** Three reviews of the developer's work whose evidence cites the reviewed repository. */
    private void citingRepository(User developer) {
        String evidence = "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":"
                + "\"context/diff.patch\",\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":1,"
                + "\"endLine\":1,\"quote\":\"example\",\"quoteRedacted\":false,\"sourceReference\":"
                + "{\"records\":[{\"type\":\"repository\",\"id\":7}]}}]}";
        for (Instant at : List.of(OLDEST, MIDDLE, NEWEST)) {
            AgentJob run = persistPullRequestReview(workspace, nextNumber, at);
            observe(packaging, run, nextNumber++, developer, MET, null, at, evidence);
        }
    }

    /**
     * The golden comparison: the counts every reader shares, read off for each reader, show each reader what
     * counting the whole workspace for that reader alone showed before the counts were shared. Covered are a
     * counted reader, developers at each standing, a hidden member, a developer the page does not count, a caller
     * who is no developer, and evidence that cites history, a person and a repository.
     */
    @Test
    @DisplayName("every reader reads in every window what counting the workspace for them alone shows")
    void shouldShowEveryReaderWhatCountingTheWorkspaceForThemAloneShows() {
        User hidden = developer("across-dev-4");
        jdbc.update(
                "UPDATE workspace_membership SET hidden = true WHERE workspace_id = ? AND user_id = ?",
                workspace.getId(),
                hidden.getId());
        User outsider = persistUser("across-outsider");
        strength(packaging, outsider, NEWEST);
        // Evidence that cites the developer's earlier observations and the developer.
        // across-dev-4 is hidden, so a citation of their history or of them is refused.
        for (String login : List.of("across-dev-4", "across-dev-5", "across-dev-6", "across-dev-7", "across-dev-8")) {
            citingHistory(developer(login));
        }
        AgentJob run = persistPullRequestReview(workspace, nextNumber, NEWEST);
        bind(
                persistInAppFeedback(
                        run,
                        reader,
                        1,
                        FeedbackDeliveryState.DELIVERED,
                        InAppFeedbackBody.render("Open", "Open.", "Fix."),
                        NEWEST),
                observe(craft, run, nextNumber++, reader, NOT_MET, Severity.MAJOR, NEWEST));
        var reference = new PracticesAcrossWorkspaceReference(
                practiceStandingService,
                practiceGroupStandingService,
                practiceGroupService,
                workspaceMembershipService,
                currentDeveloperLookup,
                inAppFeedbackService,
                clock);
        var context = WorkspaceContext.fromWorkspace(workspace, null, null);
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        List<@Nullable User> readers = new ArrayList<>(List.of(
                reader,
                developer("across-owner"),
                developer("across-dev-0"),
                developer("across-dev-1"),
                developer("across-dev-5"),
                developer("across-dev-20"),
                hidden,
                outsider));
        readers.add(null);

        for (@Nullable User developer : readers) {
            String who = developer == null ? "no developer" : developer.getLogin();
            assertThat(as(developer, () -> acrossWorkspaceService.read(context)))
                    .as("the overview %s reads", who)
                    .isEqualTo(as(developer, () -> transaction.execute(status -> reference.read(context))));
            for (PracticesAcrossWorkspaceWindow window : PracticesAcrossWorkspaceWindow.values()) {
                assertThat(as(developer, () -> acrossWorkspaceService.readTiles(context, window)))
                        .as("the %s tiles %s reads", window, who)
                        .isEqualTo(as(
                                developer, () -> transaction.execute(status -> reference.readTiles(context, window))));
            }
        }
    }

    @Test
    @WithUser
    @DisplayName("new review results are counted again in the background; a hidden member drops the counts at once")
    void shouldCountAgainWhenReviewResultsOrAMembersPrivacyChange() {
        read().jsonPath("$.groups[0].split.developers").isEqualTo(28);
        User late = member("across-late");
        strength(packaging, late, NEWEST);
        // Recorded without a review run's admission, so nothing tells the page yet.
        read().jsonPath("$.groups[0].split.developers").isEqualTo(28);

        // New results keep the counts readable until the workspace is counted again in the background.
        eventPublisher.publishEvent(new ReviewResultsChangedEvent(workspace.getId(), false));
        read().jsonPath("$.groups[0].split.developers").isEqualTo(28);
        cache.recountNow(workspace.getId());
        read().jsonPath("$.groups[0].split.developers").isEqualTo(29);

        workspaceMembershipService.updateMemberVisibility(workspace.getId(), late.getId(), true);
        read().jsonPath("$.groups[0].split.developers").isEqualTo(28);
    }

    /**
     * Three reviews of the developer's work, each newer one citing the one before through the developer's history,
     * the newest also citing the developer: a chain the history checks follow back to the oldest review.
     */
    private void citingHistory(User developer) {
        AgentJob oldest = persistPullRequestReview(workspace, nextNumber, OLDEST);
        UUID first = observe(testing, oldest, nextNumber++, developer, MET, null, OLDEST);
        AgentJob middle = persistPullRequestReview(workspace, nextNumber, MIDDLE);
        UUID second = observe(
                testing, middle, nextNumber++, developer, MET, null, MIDDLE, citingHistory(developer, first, false));
        AgentJob newest = persistPullRequestReview(workspace, nextNumber, NEWEST);
        observe(
                testing,
                newest,
                nextNumber++,
                developer,
                NOT_MET,
                Severity.MAJOR,
                NEWEST,
                citingHistory(developer, second, true));
    }

    /** A diff citation, a citation of one of the developer's observations, and of the developer when asked. */
    private static String citingHistory(User developer, UUID observation, boolean citingThePerson) {
        String person = "{\"sourceKind\":\"workspace.project-inventory\",\"artifactPath\":\"context/people/"
                + developer.getId() + "/person.json\",\"path\":\"person.json\",\"startLine\":1,\"endLine\":1,"
                + "\"quote\":\"x\",\"quoteRedacted\":false,\"sourceReference\":{\"records\":[{\"type\":"
                + "\"person\",\"person\":" + developer.getId() + "}]}},";
        return "{\"citations\":[{\"sourceKind\":\"scm.pull-request.diff\",\"artifactPath\":"
                + "\"context/diff.patch\",\"path\":\"src/Main.java\",\"side\":\"NEW\",\"startLine\":1,"
                + "\"endLine\":1,\"quote\":\"example\",\"quoteRedacted\":false},"
                + (citingThePerson ? person : "")
                + "{\"sourceKind\":\"hephaestus.observation-history\",\"artifactPath\":\"context/people/"
                + developer.getId() + "/observations.jsonl\",\"path\":\"observations.jsonl\",\"startLine\":1,"
                + "\"endLine\":1,\"quote\":\"x\",\"quoteRedacted\":false,\"sourceReference\":{\"records\":"
                + "[{\"type\":\"observation\",\"id\":\"" + observation + "\",\"person\":" + developer.getId()
                + "}]}}]}";
    }

    /** What {@code read} returns for {@code developer}, or for a caller who is no developer when null. */
    private <T> T as(@Nullable User developer, Supplier<@Nullable T> read) {
        if (developer != null) {
            CurrentScmIdentityHolder.set(developer.getId(), developer.getLogin(), Set.of(developer.getId()));
        }
        try {
            return Objects.requireNonNull(read.get());
        } finally {
            CurrentScmIdentityHolder.clear();
        }
    }

    /** The JDBC statements one overview read and one tiles read prepare, after a read that warms the context. */
    private List<Long> statementsPerRead(Statistics statistics) {
        read();
        tiles("ALL_TIME");
        // Each read counts the workspace again, as the first reader after new review results does.
        cache.invalidate(workspace.getId());
        statistics.clear();
        read();
        long overview = statistics.getPrepareStatementCount();
        cache.invalidate(workspace.getId());
        statistics.clear();
        tiles("ALL_TIME");
        long tiles = statistics.getPrepareStatementCount();
        // A reader after the first reads the workspace's counts as they stand: only their own figures cost a read.
        statistics.clear();
        read();
        tiles("ALL_TIME");
        return List.of(overview, tiles, statistics.getPrepareStatementCount());
    }

    @Test
    @WithUser
    @DisplayName("a repository hidden from contributions drops exactly the developers whose only standing came from it")
    void shouldDropExactlyTheDevelopersWhoseOnlyStandingCameFromAHiddenRepository() {
        long hiddenWork = hiddenPullRequest().getId();
        User onlyHidden = member("across-only-hidden");
        User alsoVisible = member("across-also-visible");
        for (User developer : List.of(onlyHidden, alsoVisible)) {
            AgentJob run = persistPullRequestReview(workspace, nextNumber++, NEWEST);
            observe(issues, run, hiddenWork, developer, MET, null, NEWEST);
        }
        strength(craft, alsoVisible, NEWEST);

        // The twenty eight seeded, and the developer with a standing outside the hidden repository.
        read().jsonPath("$.groups[0].split.developers").isEqualTo(29);
    }

    /** A pull request in a repository the workspace hides from contributions. */
    private PullRequest hiddenPullRequest() {
        IdentityProvider provider = ensureGitHubProvider();
        Repository repository = new Repository();
        repository.setNativeId(9_001L);
        repository.setProvider(provider);
        repository.setName("hidden");
        repository.setNameWithOwner("across-org/hidden");
        repository.setHtmlUrl("https://github.com/across-org/hidden");
        repository.setDefaultBranch("main");
        repository.setCreatedAt(NOW);
        repository.setUpdatedAt(NOW);
        repository.setPushedAt(NOW);
        repository = repositoryRepository.save(repository);
        Team team = new Team();
        team.setNativeId(9_001L);
        team.setProvider(provider);
        team.setName("hidden-team");
        team.setSlug("hidden-team");
        team.setPrivacy(Team.Privacy.VISIBLE);
        WorkspaceTeamRepositorySettings settings =
                new WorkspaceTeamRepositorySettings(workspace, teamRepository.save(team), repository);
        settings.setHiddenFromContributions(true);
        settingsRepository.save(settings);
        PullRequest pullRequest = new PullRequest();
        pullRequest.setNativeId(9_001L);
        pullRequest.setProvider(provider);
        pullRequest.setNumber(9_001);
        pullRequest.setTitle("Hidden work");
        pullRequest.setState(PullRequest.State.OPEN);
        pullRequest.setRepository(repository);
        pullRequest.setCreatedAt(NOW);
        pullRequest.setUpdatedAt(NOW);
        return pullRequestRepository.save(pullRequest);
    }

    @Test
    @WithUser
    @DisplayName("two readers with different standings read the same overview but for their own markers")
    void shouldShowEveryReaderTheSameOverviewButTheirOwnMarkers() {
        // The reader is going well in Packaging; the first developer needs attention there.
        Account account = persistAccount("across-dev-0");
        User other = developer("across-dev-0");
        IdentityLink link = new IdentityLink();
        link.setAccount(account);
        link.setProviderId(Objects.requireNonNull(other.getProvider().getId()));
        link.setSubject(other.getNativeId().toString());
        identityLinkRepository.saveAndFlush(link);

        PracticesAcrossWorkspaceDTO yours = overviewAs(TestAuthUtils.getCurrentUserToken());
        PracticesAcrossWorkspaceDTO theirs = overviewAs("mock-jwt-member-" + account.getId());

        assertThat(List.of(yours, theirs))
                .extracting(page -> page.groups().stream()
                        .filter(group -> group.groupSlug().equals("review-ready-work"))
                        .findFirst()
                        .orElseThrow()
                        .yourStanding())
                .containsExactly(
                        PracticeGroupStandingDTO.Standing.STRENGTH, PracticeGroupStandingDTO.Standing.DEVELOPING);
        assertThat(withoutMarkers(theirs)).isEqualTo(withoutMarkers(yours));
    }

    private PracticesAcrossWorkspaceDTO overviewAs(String token) {
        return Objects.requireNonNull(webTestClient
                .get()
                .uri(URI, workspace.getWorkspaceSlug())
                .headers(headers -> headers.setBearerAuth(token))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(PracticesAcrossWorkspaceDTO.class)
                .returnResult()
                .getResponseBody());
    }

    /** The overview with every reader marker cleared: what must read the same whoever reads it. */
    private static PracticesAcrossWorkspaceDTO withoutMarkers(PracticesAcrossWorkspaceDTO page) {
        return new PracticesAcrossWorkspaceDTO(
                page.openFeedback(),
                page.groups().stream()
                        .map(group -> new WorkspaceGroupSplitDTO(
                                group.groupSlug(),
                                group.groupName(),
                                group.groupIcon(),
                                group.groupColor(),
                                null,
                                group.split(),
                                group.practices().stream()
                                        .map(practice -> new WorkspacePracticeSplitDTO(
                                                practice.practiceSlug(),
                                                practice.practiceName(),
                                                null,
                                                practice.split()))
                                        .toList()))
                        .toList());
    }

    @Test
    @WithMentorUser
    @DisplayName("a caller outside the workspace is refused")
    void shouldRefuseANonMemberWhenTheyAreNotInTheWorkspace() {
        persistUser("mentor");
        webTestClient
                .get()
                .uri(URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isForbidden()
                .expectBody(Void.class);
    }

    private WebTestClient.BodyContentSpec read() {
        return webTestClient
                .get()
                .uri(URI, workspace.getWorkspaceSlug())
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    private WebTestClient.BodyContentSpec tiles(String window) {
        return webTestClient
                .get()
                .uri(builder -> builder.path(URI + "/tiles")
                        .queryParam("window", window)
                        .build(workspace.getWorkspaceSlug()))
                .headers(TestAuthUtils.withCurrentUser())
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody();
    }

    /** The page as the service composes it for {@code developer}, or for a caller who is no developer when null. */
    private PracticesAcrossWorkspaceDTO readAs(@Nullable User developer) {
        if (developer != null) {
            CurrentScmIdentityHolder.set(developer.getId(), developer.getLogin(), Set.of(developer.getId()));
        }
        try {
            return acrossWorkspaceService.read(WorkspaceContext.fromWorkspace(workspace, null, null));
        } finally {
            CurrentScmIdentityHolder.clear();
        }
    }

    /** The path to the developers at one verdict in a group's split. */
    private static String groupPart(String groupSlug, String standing) {
        return "$.groups[?(@.groupSlug == '%s')].split.parts[?(@.standing == '%s')].developers"
                .formatted(groupSlug, standing);
    }

    /** The path to the developers at one verdict in a practice's split. */
    private static String practicePart(String groupSlug, String practiceSlug, String standing) {
        return ("$.groups[?(@.groupSlug == '%s')].practices[?(@.practiceSlug == '%s')]"
                        + ".split.parts[?(@.standing == '%s')].developers")
                .formatted(groupSlug, practiceSlug, standing);
    }

    /** Every group's split and its practices' names and splits: what reads the same whoever reads it. */
    private static List<List<Object>> shapes(PracticesAcrossWorkspaceDTO page) {
        return page.groups().stream()
                .map(group -> List.<Object>of(
                        group.groupSlug(),
                        group.split(),
                        group.practices().stream()
                                .map(practice ->
                                        List.of(practice.practiceSlug(), practice.practiceName(), practice.split()))
                                .toList()))
                .toList();
    }

    private PracticeGroup group(Workspace in, String slug, String name) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(in);
        group.setSlug(slug);
        group.setName(name);
        return groupRepository.save(group);
    }

    private User developer(String login) {
        return userRepository.findByLogin(login).orElseThrow();
    }

    private User member(String login) {
        User user = persistUser(login);
        ensureWorkspaceMembership(workspace, user, WorkspaceMembership.WorkspaceRole.MEMBER);
        return user;
    }

    private void strength(Practice practice, User developer, Instant at) {
        AgentJob run = persistPullRequestReview(workspace, nextNumber, at);
        observe(practice, run, nextNumber++, developer, MET, null, at);
    }

    private void problem(Practice practice, User developer, Instant at) {
        AgentJob run = persistPullRequestReview(workspace, nextNumber, at);
        observe(practice, run, nextNumber++, developer, NOT_MET, Severity.MAJOR, at);
    }

    /** Needs attention, Mixed feedback or Going well by {@code index}, a third of the developers each. */
    private void standing(Practice practice, User developer, int index) {
        switch (index % 3) {
            case 0 -> needsAttention(practice, developer);
            case 1 -> mixed(practice, developer);
            default -> strength(practice, developer, NEWEST);
        }
    }

    /** A slip on each of the two newest pieces of work: a pattern, not one setback. */
    private void needsAttention(Practice practice, User developer) {
        problem(practice, developer, MIDDLE);
        problem(practice, developer, NEWEST);
    }

    /** Clean, then a slip, then clean again on the newest: a share between the two bars. */
    private void mixed(Practice practice, User developer) {
        strength(practice, developer, OLDEST);
        problem(practice, developer, MIDDLE);
        strength(practice, developer, NEWEST);
    }
}
