package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import de.tum.cit.aet.hephaestus.workspace.MembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.settings.ReviewScopeMode;
import de.tum.cit.aet.hephaestus.workspace.settings.WorkspaceReviewScope;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PracticeReviewCoverageReplaceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri(root() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Autowired
    PracticeReviewCoverageService coverage;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MembershipRepository memberships;

    @Autowired
    WorkspaceRepository workspaces;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void selectedEmptyIsDifferentFromAll() {
        coverage.replace(
                scenario.workspace(),
                new WorkspaceReviewScope(ReviewScopeMode.SELECTED, ReviewScopeMode.SELECTED, List.of(), List.of()));
        assertThat(coverage.scope(scenario.workspace()).accountIds()).isEmpty();
        assertThat(coverage.scope(scenario.workspace())
                        .admitsPerson(scenario.account().getId()))
                .isFalse();
        coverage.replace(scenario.workspace(), WorkspaceReviewScope.ALL);
        assertThat(coverage.scope(scenario.workspace())
                        .admitsPerson(scenario.account().getId()))
                .isTrue();
    }

    @Test
    void selectedAccountsAreStoredOnMembership() {
        coverage.replace(
                scenario.workspace(),
                new WorkspaceReviewScope(
                        ReviewScopeMode.ALL,
                        ReviewScopeMode.SELECTED,
                        List.of(),
                        List.of(scenario.account().getId())));
        assertThat(coverage.scope(scenario.workspace()).accountIds())
                .containsExactly(scenario.account().getId());
        coverage.replace(scenario.workspace(), WorkspaceReviewScope.ALL);
        assertThat(coverage.scope(scenario.workspace()).accountIds()).isEmpty();
    }

    @Test
    void foreignAccountIsRejectedWithoutChangingScope() {
        var other = data.scenario();
        assertThatThrownBy(() -> coverage.replace(
                        scenario.workspace(),
                        new WorkspaceReviewScope(
                                ReviewScopeMode.ALL,
                                ReviewScopeMode.SELECTED,
                                List.of(),
                                List.of(other.account().getId()))))
                .isInstanceOf(InvalidReviewScopeException.class);
        assertThat(coverage.scope(scenario.workspace()).personMode()).isEqualTo(ReviewScopeMode.ALL);
    }

    @Test
    void hiddenAndNoAiMembersAreNotEligible() {
        assertThat(coverage.eligibleAccountIds(scenario.workspace().getId()))
                .contains(scenario.account().getId());
        jdbc.sql("UPDATE membership SET hidden=true WHERE workspace_id=:ws AND account_id=:id")
                .param("ws", scenario.workspace().getId())
                .param("id", scenario.account().getId())
                .update();
        assertThat(coverage.eligibleAccountIds(scenario.workspace().getId())).isEmpty();
        jdbc.sql("UPDATE membership SET hidden=false WHERE workspace_id=:ws AND account_id=:id")
                .param("ws", scenario.workspace().getId())
                .param("id", scenario.account().getId())
                .update();
        jdbc.sql("UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=:id")
                .param("id", scenario.account().getId())
                .update();
        assertThat(coverage.eligibleAccountIds(scenario.workspace().getId())).isEmpty();
    }

    @Test
    void shouldKeepLoadedMembershipsCoherentWhenSelectionIsReplacedAndReset() {
        long workspaceId = scenario.workspace().getId();
        long accountId = Objects.requireNonNull(scenario.account().getId());
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            var workspace = workspaces.findById(workspaceId).orElseThrow();
            var member = memberships
                    .findById(new Membership.Id(workspaceId, accountId))
                    .orElseThrow();
            assertThat(member.isReviewSelected()).isFalse();
            coverage.replace(
                    workspace,
                    new WorkspaceReviewScope(
                            ReviewScopeMode.ALL, ReviewScopeMode.SELECTED, List.of(), List.of(accountId)));
            assertThat(member.isReviewSelected()).isTrue();
            assertThat(coverage.scope(workspace).accountIds()).containsExactly(accountId);
            coverage.replace(workspace, WorkspaceReviewScope.ALL);
            assertThat(member.isReviewSelected()).isFalse();
        });
        assertThat(coverage.scope(scenario.workspace()).accountIds()).isEmpty();
    }
}
