package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.model.Outcome.MET;
import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_MET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.core.security.CurrentScmIdentityHolder;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.PracticesAcrossWorkspaceDTO;
import de.tum.cit.aet.hephaestus.practices.acrossworkspace.dto.WorkspacePracticeSplitDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.InAppFeedbackBody;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.practices.observation.dto.PracticeStandingDTO;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.context.WorkspaceContext;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /practices/workspace-overview} over a workspace this class seeds: the owner and twenty six developers
 * besides the reader, split evenly over two groups, barely over a third and over all but three in a fourth, so both
 * shapes the privacy rule allows appear, the withheld one for a small standing and for a small none yet. A part
 * shows only with four developers in it, the reader counted, so every reader sees the same shape. Every count
 * asserted is one of these rows.
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
        User owner = persistUser("across-owner");
        workspace = createWorkspace("across-ws", "Across WS", "across-org", AccountType.ORG, owner);
        packagingGroup = group(workspace, "review-ready-work", "Packaging");
        packaging = persistPractice(workspace, packagingGroup, "explain", "Explain", null);
        testing = persistPractice(workspace, group(workspace, "testing-discipline", "Testing"), "tests", "Tests", null);
        issues = persistPractice(workspace, group(workspace, "actionable-issues", "Issues"), "issue", "Issue", null);
        craft = persistPractice(workspace, group(workspace, "code-craftsmanship", "Craft"), "craft", "Craft", null);

        reader = member("testuser"); // matches @WithUser
        strength(packaging, reader, NEWEST);
        // The owner has a standing too, so every eligible developer is observed.
        strength(issues, owner, MIDDLE);
        for (int index = 0; index < DEVELOPERS; index++) {
            User developer = member("across-dev-" + index);
            // Packaging: six each at Needs attention, Mixed feedback and Going well, eight with none.
            if (index < 18) {
                standing(packaging, developer, index);
            }
            // Testing: four at each standing, fourteen developers, the owner and the reader with none.
            if (index >= 14) {
                standing(testing, developer, index);
            }
            // Issues: three and the owner with a standing, all Going well, so no split.
            if (index < 3) {
                strength(issues, developer, MIDDLE);
            }
            // Craft: eight or nine at each standing and three with none, so the split fails on none yet.
            if (index > 0) {
                standing(craft, developer, index);
            }
        }
    }

    @Test
    @WithUser
    @DisplayName("an even group splits with the reader counted; a bare or nearly full one is withheld")
    void shouldSplitAndWithholdByTheCountsOfObservedDevelopers() {
        read("ALL_TIME")
                .jsonPath("$.window")
                .isEqualTo("ALL_TIME")
                .jsonPath("$.minimumOthers")
                .isEqualTo(3)
                // The owner, the reader and twenty six developers are eligible, and every one of them was reviewed.
                .jsonPath("$.observedDevelopers")
                .isEqualTo(28)
                .jsonPath("$.readerCounted")
                .isEqualTo(true)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourStanding")
                .isEqualTo("STRENGTH")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.needsAttention")
                .isEqualTo(6)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.mixedFeedback")
                .isEqualTo(6)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.goingWell")
                .isEqualTo(7)
                // The eight developers and the owner without a standing are a fourth part of their own.
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.noneYet")
                .isEqualTo(9)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].yourStanding")
                .isEqualTo("NOT_OBSERVED")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].split.goingWell")
                .isEqualTo(4)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].split.noneYet")
                .isEqualTo(16)
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].split.shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].split.goingWell")
                .doesNotExist()
                // Every standing holds eight or more, but only three are left at none yet, the reader among them.
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].split.shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].split.goingWell")
                .doesNotExist()
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].split.noneYet")
                .doesNotExist()
                // The reader's own figures, then the middle half of all twenty eight.
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1)
                .jsonPath("$.practicesGoingWell.yours")
                .isEqualTo(1)
                .jsonPath("$.practicesGoingWell.middleLow")
                .isNumber()
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

        read("ALL_TIME")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices.length()")
                .isEqualTo(2)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'explain')]"
                        + ".yourStanding")
                .isEqualTo("STRENGTH")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'explain')]"
                        + ".split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'explain')]"
                        + ".split.goingWell")
                .isEqualTo(7)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'small')]"
                        + ".split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'small')]"
                        + ".split.needsAttention")
                .isEqualTo(6)
                // A group with one practice names it, split as the group is.
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].practices[0].split.shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourDirection")
                .exists();
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
                        .containsExactly(
                                tuple("atomic", PracticeStandingDTO.Standing.NOT_OBSERVED),
                                tuple("explain", PracticeStandingDTO.Standing.NOT_OBSERVED)));
    }

    @Test
    @WithUser
    @DisplayName("a practice whose split falls short of its group's by fewer than three is held back")
    void shouldHoldBackAPracticeThatFallsShortOfItsGroupByFewerThanThree() {
        Practice small = persistPractice(workspace, packagingGroup, "small", "Small", null);
        // The second practice says of seventeen developers what the first says and nothing of the eighteenth or the
        // reader: on its own a split, but the group less it would count the two it leaves out.
        for (int index = 0; index < 17; index++) {
            standing(small, developer("across-dev-" + index), index);
        }

        read("ALL_TIME")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'explain')]"
                        + ".split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'small')]"
                        + ".yourStanding")
                .isEqualTo("NOT_OBSERVED")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].practices[?(@.practiceSlug == 'small')]"
                        + ".split.shape")
                .isEqualTo("WITHHELD");
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

        read("ALL_TIME")
                .jsonPath("$.openFeedback.yours")
                .isEqualTo(1)
                // Nobody else has feedback, so the middle half of every eligible developer is none, now.
                .jsonPath("$.openFeedback.middleLow")
                .isEqualTo(0)
                .jsonPath("$.openFeedback.middleHigh")
                .isEqualTo(0);
        assertThat(feedbackRepository.findById(open.getId()))
                .map(Feedback::getDeliveryState)
                .contains(FeedbackDeliveryState.PREPARED);
    }

    @Test
    @WithUser
    @DisplayName("each window counts only its own evidence, and a window with too few developers shows no split")
    void shouldCheckEachWindowOnItsOwn() {
        // Testing and issue standings move forty days back, in order, before the last 30 days; packaging and craft
        // stay inside it.
        jdbc.update(
                "UPDATE observation SET observed_at = observed_at - interval '40 days'"
                        + " WHERE workspace_id = ? AND practice_id IN (?, ?)",
                workspace.getId(),
                testing.getId(),
                issues.getId());

        read("DAYS_30")
                .jsonPath("$.window")
                .isEqualTo("DAYS_30")
                // The owner's only standing moved out of the window.
                .jsonPath("$.observedDevelopers")
                .isEqualTo(27)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].split.shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.shape")
                .isEqualTo("SPLIT");
        read("DAYS_90")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].split.shape")
                .isEqualTo("SPLIT");
    }

    @Test
    @WithUser
    @DisplayName("all time reads evidence older than ninety days, which the ninety day window leaves out")
    void shouldReadEveryObservationWhenTheWindowIsAllTime() {
        // Every review moves a hundred days back, in order: the ninety day window reads nobody, all time everyone.
        jdbc.update(
                "UPDATE observation SET observed_at = observed_at - interval '100 days' WHERE workspace_id = ?",
                workspace.getId());

        read("DAYS_90").jsonPath("$.observedDevelopers").doesNotExist();
        read("ALL_TIME")
                .jsonPath("$.observedDevelopers")
                .isEqualTo(28)
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].split.shape")
                .isEqualTo("WITHHELD");
    }

    @Test
    @WithUser
    @DisplayName("the reader is not one of the three: two others and the reader leave every figure withheld")
    void shouldWithholdEverythingWhenOnlyTwoOthersAreObserved() {
        jdbc.update("""
                DELETE FROM observation WHERE workspace_id = ? AND about_user_id IN (
                    SELECT id FROM "user" WHERE login LIKE 'across-%' AND login NOT IN
                    ('across-dev-0', 'across-dev-1'))
                """, workspace.getId());

        read("ALL_TIME")
                .jsonPath("$.observedDevelopers")
                .doesNotExist()
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1)
                .jsonPath("$.reviewedWork.middleLow")
                .doesNotExist()
                .jsonPath("$.practicesGoingWell.middleHigh")
                .doesNotExist()
                .jsonPath("$.groups[?(@.split.shape != 'WITHHELD')]")
                .isEmpty();
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

        read("ALL_TIME")
                .jsonPath("$.observedDevelopers")
                .isEqualTo(26)
                // Two of the six at Needs attention are hidden, which leaves four there: still a part of its own.
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.needsAttention")
                .isEqualTo(4)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].split.noneYet")
                .isEqualTo(9);
    }

    @Test
    @DisplayName("an instance administrator viewing as the developer reads the developer's own page")
    void shouldReadTheViewedDevelopersPageWhenAnAdministratorViewsIt() {
        readAsUserView(URI, workspace, reader)
                .jsonPath("$.readerCounted")
                .isEqualTo(true)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourStanding")
                .isEqualTo("STRENGTH");
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

    private WebTestClient.BodyContentSpec read(String window) {
        return webTestClient
                .get()
                .uri(builder -> builder.path(URI).queryParam("window", window).build(workspace.getWorkspaceSlug()))
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
            return acrossWorkspaceService.read(
                    WorkspaceContext.fromWorkspace(workspace, null, null), PracticesAcrossWorkspaceWindow.ALL_TIME);
        } finally {
            CurrentScmIdentityHolder.clear();
        }
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
            case 0 -> problem(practice, developer, NEWEST);
            case 1 -> mixed(practice, developer);
            default -> strength(practice, developer, NEWEST);
        }
    }

    /** Clean, then a slip, then clean again on the newest: a share between the two bars. */
    private void mixed(Practice practice, User developer) {
        strength(practice, developer, OLDEST);
        problem(practice, developer, MIDDLE);
        strength(practice, developer, NEWEST);
    }
}
