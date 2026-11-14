package de.tum.cit.aet.hephaestus.workspace.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Workspace review scope")
class WorkspaceReviewScopeTest extends BaseUnitTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void allModesAdmitAnyRepositoryAndEligiblePerson() {
        assertThat(WorkspaceReviewScope.ALL.admits(101L, "main", 7L)).isTrue();
        assertThat(WorkspaceReviewScope.ALL.admits(102L, null, 7L)).isTrue();
        assertThat(WorkspaceReviewScope.ALL.admits(101L, "main", null)).isFalse();
    }

    @Test
    void allPeopleAdmitEveryEligibleMemberWhateverTheirSelectionFlag() {
        assertThat(WorkspaceReviewScope.admitsPerson(ReviewScopeMode.ALL, false))
                .isTrue();
        assertThat(WorkspaceReviewScope.admitsPerson(ReviewScopeMode.ALL, true)).isTrue();
        assertThat(WorkspaceReviewScope.admitsPerson(ReviewScopeMode.SELECTED, true))
                .isTrue();
        assertThat(WorkspaceReviewScope.admitsPerson(ReviewScopeMode.SELECTED, false))
                .isFalse();
    }

    @Test
    void selectedEmptyMeansNobodyOnEitherAxis() {
        WorkspaceReviewScope scope =
                new WorkspaceReviewScope(ReviewScopeMode.SELECTED, ReviewScopeMode.SELECTED, List.of(), List.of());

        assertThat(scope.admits(101L, "main", 7L)).isFalse();
        assertThat(scope.admitsRepository(101L, "main")).isFalse();
        assertThat(scope.admitsPerson(7L)).isFalse();
    }

    @Test
    void repositoryAndPersonSelectionsAreIntersected() {
        WorkspaceReviewScope scope = selected(List.of(new ReviewRepositoryTarget(101L, List.of())), List.of(7L));

        assertThat(scope.admits(101L, "any", 7L)).isTrue();
        assertThat(scope.admits(102L, "any", 7L)).isFalse();
        assertThat(scope.admits(101L, "any", 8L)).isFalse();
    }

    @Test
    void allRepositoriesCanBeCombinedWithSelectedPeople() {
        WorkspaceReviewScope scope =
                new WorkspaceReviewScope(ReviewScopeMode.ALL, ReviewScopeMode.SELECTED, List.of(), List.of(7L));

        assertThat(scope.admits(103L, "any-branch", 7L)).isTrue();
        assertThat(scope.admits(103L, "any-branch", 8L)).isFalse();
    }

    @Test
    void selectedRepositoriesCanBeCombinedWithAllEligiblePeople() {
        WorkspaceReviewScope scope = new WorkspaceReviewScope(
                ReviewScopeMode.SELECTED,
                ReviewScopeMode.ALL,
                List.of(new ReviewRepositoryTarget(101L, List.of())),
                List.of());

        assertThat(scope.admits(101L, "any-branch", 7L)).isTrue();
        assertThat(scope.admits(102L, "any-branch", 7L)).isFalse();
    }

    @Test
    void exactBaseBranchesAreScopedPerRepository() {
        WorkspaceReviewScope scope = selected(
                List.of(
                        new ReviewRepositoryTarget(104L, List.of("main", "release")),
                        new ReviewRepositoryTarget(105L, List.of("develop")),
                        new ReviewRepositoryTarget(106L, List.of())),
                List.of(7L));

        assertThat(scope.admits(104L, "main", 7L)).isTrue();
        assertThat(scope.admits(104L, "develop", 7L)).isFalse();
        assertThat(scope.admits(105L, "develop", 7L)).isTrue();
        assertThat(scope.admits(105L, "main", 7L)).isFalse();
        assertThat(scope.admits(106L, null, 7L)).isTrue();
    }

    @Test
    void issueCoverageIgnoresPullRequestBranchRestrictions() {
        WorkspaceReviewScope scope = selected(List.of(new ReviewRepositoryTarget(104L, List.of("main"))), List.of(7L));

        assertThat(scope.admits(104L, null, 7L, false)).isTrue();
        assertThat(scope.admits(104L, null, 7L, true)).isFalse();
    }

    @Test
    void repositoryTargetsCanonicalizeBranchesAndPeople() {
        ReviewRepositoryTarget target = new ReviewRepositoryTarget(101L, Arrays.asList(" main ", "main", "develop"));
        WorkspaceReviewScope scope = new WorkspaceReviewScope(
                ReviewScopeMode.SELECTED, ReviewScopeMode.SELECTED, List.of(target), Arrays.asList(7L, 7L));

        assertThat(scope.repositories()).containsExactly(target);
        assertThat(target.baseBranches()).containsExactly("develop", "main");
        assertThat(scope.accountIds()).containsExactly(7L);
    }

    @Test
    void missingPolicyAxesAreRejectedInsteadOfWidened() {
        assertThatThrownBy(() -> MAPPER.readValue("""
            {"personMode":"SELECTED","repositories":[],"accountIds":[]}
            """, WorkspaceReviewScope.class))
                .hasRootCauseMessage("repositoryMode");
        assertThatThrownBy(() -> MAPPER.readValue("{\"repositoryItemId\":101}", ReviewRepositoryTarget.class))
                .hasRootCauseMessage("baseBranches");
    }

    @Test
    void duplicateRepositoryTargetsAreRejectedInsteadOfSilentlyOverwriting() {
        ReviewRepositoryTarget first = new ReviewRepositoryTarget(101L, List.of("main"));
        ReviewRepositoryTarget second = new ReviewRepositoryTarget(101L, List.of("develop"));

        assertThatThrownBy(() -> new WorkspaceReviewScope(
                        ReviewScopeMode.SELECTED, ReviewScopeMode.SELECTED, List.of(first, second), List.of(7L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void malformedRepositoryTargetsAreRejectedAtTheDomainBoundary() {
        assertThatThrownBy(() -> new ReviewRepositoryTarget(0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewRepositoryTarget(101L, List.of("x".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsThroughJsonWithExplicitModes() {
        WorkspaceReviewScope scope = selected(List.of(new ReviewRepositoryTarget(101L, List.of("main"))), List.of(7L));

        String json = MAPPER.writeValueAsString(scope);

        assertThat(json).contains("repositoryMode", "personMode", "repositoryItemId", "baseBranches", "accountIds");
        assertThat(MAPPER.readValue(json, WorkspaceReviewScope.class)).isEqualTo(scope);
    }

    @Test
    void shouldKeepDistinctRepositoryItemsWhenProviderRepositoryNamesMatch() {
        var scope = selected(
                List.of(
                        new ReviewRepositoryTarget(101L, List.of("main")),
                        new ReviewRepositoryTarget(102L, List.of("release"))),
                List.of(7L));

        assertThat(scope.admits(101L, "main", 7L)).isTrue();
        assertThat(scope.admits(102L, "main", 7L)).isFalse();
        assertThat(scope.admits(102L, "release", 7L)).isTrue();
        assertThat(scope.admits(null, "main", 7L)).isFalse();
    }

    private static WorkspaceReviewScope selected(List<ReviewRepositoryTarget> repositories, List<Long> people) {
        return new WorkspaceReviewScope(ReviewScopeMode.SELECTED, ReviewScopeMode.SELECTED, repositories, people);
    }
}
