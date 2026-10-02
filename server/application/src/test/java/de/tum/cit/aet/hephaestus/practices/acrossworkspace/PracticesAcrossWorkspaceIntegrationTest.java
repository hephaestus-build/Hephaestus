package de.tum.cit.aet.hephaestus.practices.acrossworkspace;

import static de.tum.cit.aet.hephaestus.practices.model.Outcome.MET;
import static de.tum.cit.aet.hephaestus.practices.model.Outcome.NOT_MET;

import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.AbstractPracticeReviewIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.PracticeGroupRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeGroup;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.TestAuthUtils;
import de.tum.cit.aet.hephaestus.testconfig.WithMentorUser;
import de.tum.cit.aet.hephaestus.testconfig.WithUser;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * {@code GET /practices/workspace-overview} over a workspace this class seeds: twenty developers besides the
 * reader, split evenly over one group, thinly over a second, barely over a third and over all but one in a fourth,
 * so the three shapes the privacy rule allows appear, the last for both of its reasons. Every count asserted is one
 * of these rows.
 */
class PracticesAcrossWorkspaceIntegrationTest extends AbstractPracticeReviewIntegrationTest {

    private static final String URI = "/workspaces/{workspaceSlug}/practices/workspace-overview";

    private static final Instant OLDEST = NOW.minus(Duration.ofDays(20));
    private static final Instant MIDDLE = NOW.minus(Duration.ofDays(10));
    private static final Instant NEWEST = NOW.minus(Duration.ofDays(2));

    @Autowired
    private PracticeGroupRepository groupRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private Workspace workspace;
    private User reader;
    private Practice packaging;
    private Practice testing;
    private Practice issues;
    private Practice craft;
    private int nextNumber = 100;

    @BeforeEach
    void seedWorkspace() {
        User owner = persistUser("across-owner");
        workspace = createWorkspace("across-ws", "Across WS", "across-org", AccountType.ORG, owner);
        packaging = persistPractice(
                workspace, group(workspace, "review-ready-work", "Packaging"), "explain", "Explain", null);
        testing = persistPractice(workspace, group(workspace, "testing-discipline", "Testing"), "tests", "Tests", null);
        issues = persistPractice(workspace, group(workspace, "actionable-issues", "Issues"), "issue", "Issue", null);
        craft = persistPractice(workspace, group(workspace, "code-craftsmanship", "Craft"), "craft", "Craft", null);

        reader = member("testuser"); // matches @WithUser
        strength(packaging, reader, NEWEST);
        for (int index = 0; index < 20; index++) {
            User developer = member("across-dev-" + index);
            // Packaging: five each at Needs attention, Mixed feedback and Going well, five with none.
            if (index < 15) {
                switch (index % 3) {
                    case 0 -> problem(packaging, developer, NEWEST);
                    case 1 -> mixed(packaging, developer);
                    default -> strength(packaging, developer, NEWEST);
                }
            }
            // Testing: five with a standing, none at Mixed feedback, fifteen with none.
            if (index >= 18) {
                strength(testing, developer, MIDDLE);
            } else if (index >= 15) {
                problem(testing, developer, MIDDLE);
            }
            // Issues: three with a standing, so not even the collapsed split.
            if (index < 3) {
                strength(issues, developer, MIDDLE);
            }
            // Craft: six or seven at each standing and one with none, whom the observed total would name.
            if (index > 0) {
                switch (index % 3) {
                    case 0 -> problem(craft, developer, NEWEST);
                    case 1 -> mixed(craft, developer);
                    default -> strength(craft, developer, NEWEST);
                }
            }
        }
    }

    @Test
    @WithUser
    @DisplayName(
            "an even group splits with the reader counted; a thin one collapses; a bare or nearly full one is withheld")
    void shouldSplitCollapseAndWithholdByTheCountsOfOtherDevelopers() {
        read("TERM")
                .jsonPath("$.window")
                .isEqualTo("TERM")
                .jsonPath("$.minimumOthers")
                .isEqualTo(5)
                // The owner, the reader and twenty developers are eligible; the owner was never reviewed.
                .jsonPath("$.eligibleDevelopers")
                .isEqualTo(22)
                .jsonPath("$.observedDevelopers")
                .isEqualTo(21)
                .jsonPath("$.readerCounted")
                .isEqualTo(true)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].shape")
                .isEqualTo("SPLIT")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].yourStanding")
                .isEqualTo("STRENGTH")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].needsAttention")
                .isEqualTo(5)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].mixedFeedback")
                .isEqualTo(5)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].goingWell")
                .isEqualTo(6)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].shape")
                .isEqualTo("COLLAPSED")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].yourStanding")
                .isEqualTo("NOT_OBSERVED")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].hasStanding")
                .isEqualTo(5)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].noneYet")
                .isEqualTo(16)
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].goingWell")
                .doesNotExist()
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'actionable-issues')].hasStanding")
                .doesNotExist()
                // Every standing holds six others, but 19 of 21 with one would leave a single other at none yet.
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].goingWell")
                .doesNotExist()
                .jsonPath("$.groups[?(@.groupSlug == 'code-craftsmanship')].noneYet")
                .doesNotExist()
                // The reader's own figures, then the middle half of all twenty one.
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
    @DisplayName("each window counts only its own evidence, and a window with too few developers shows no split")
    void shouldCheckEachWindowOnItsOwn() {
        // Testing and issue standings move before the last 30 days; packaging and craft stay inside it.
        jdbc.update(
                "UPDATE observation SET observed_at = ? WHERE workspace_id = ? AND practice_id IN (?, ?)",
                Timestamp.from(NOW.minus(Duration.ofDays(45))),
                workspace.getId(),
                testing.getId(),
                issues.getId());

        read("DAYS_30")
                .jsonPath("$.window")
                .isEqualTo("DAYS_30")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].shape")
                .isEqualTo("WITHHELD")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].shape")
                .isEqualTo("SPLIT");
        read("DAYS_90")
                .jsonPath("$.groups[?(@.groupSlug == 'testing-discipline')].shape")
                .isEqualTo("COLLAPSED");
    }

    @Test
    @WithUser
    @DisplayName("the reader is not one of the five: four others and the reader leave every figure withheld")
    void shouldWithholdEverythingWhenOnlyFourOthersAreObserved() {
        jdbc.update("""
                DELETE FROM observation WHERE workspace_id = ? AND about_user_id IN (
                    SELECT id FROM "user" WHERE login LIKE 'across-dev-%' AND login NOT IN
                    ('across-dev-0', 'across-dev-1', 'across-dev-2', 'across-dev-3'))
                """, workspace.getId());

        read("TERM")
                .jsonPath("$.observedDevelopers")
                .isEqualTo(5)
                .jsonPath("$.reviewedWork.yours")
                .isEqualTo(1)
                .jsonPath("$.reviewedWork.middleLow")
                .doesNotExist()
                .jsonPath("$.practicesGoingWell.middleHigh")
                .doesNotExist()
                .jsonPath("$.groups[?(@.shape != 'WITHHELD')]")
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

        read("TERM")
                .jsonPath("$.eligibleDevelopers")
                .isEqualTo(20)
                .jsonPath("$.observedDevelopers")
                .isEqualTo(19)
                // Two of the five at Needs attention are hidden, so the split no longer holds five there and
                // collapses: thirteen others and the reader with a standing, five others with none.
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].shape")
                .isEqualTo("COLLAPSED")
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].hasStanding")
                .isEqualTo(14)
                .jsonPath("$.groups[?(@.groupSlug == 'review-ready-work')].noneYet")
                .isEqualTo(5);
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

    private PracticeGroup group(Workspace in, String slug, String name) {
        PracticeGroup group = new PracticeGroup();
        group.setWorkspace(in);
        group.setSlug(slug);
        group.setName(name);
        return groupRepository.save(group);
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

    /** Clean, then a slip, then clean again on the newest: a share between the two bars. */
    private void mixed(Practice practice, User developer) {
        strength(practice, developer, OLDEST);
        problem(practice, developer, MIDDLE);
        strength(practice, developer, NEWEST);
    }
}
