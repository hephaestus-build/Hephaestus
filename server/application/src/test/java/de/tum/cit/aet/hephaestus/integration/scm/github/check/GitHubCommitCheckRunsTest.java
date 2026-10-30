package de.tum.cit.aet.hephaestus.integration.scm.github.check;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.HeadCheckRuns;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.GitHubGraphQlTestMapper;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCheckSuiteConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusCheckRollup;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GitHubCommitCheckRunsTest extends BaseUnitTest {

    private static final String SHA = "d".repeat(40);
    private static final Instant READ_AT = Instant.parse("2026-10-26T12:00:00Z");
    private static final long NO_APP = 0;
    private static final long OWN_APP = 4242;

    private static final String LINT_RUNNING = "\"status\": \"IN_PROGRESS\", \"conclusion\": null";
    private static final String LINT_PASSED = "\"status\": \"COMPLETED\", \"conclusion\": \"SUCCESS\"";

    @Test
    void shouldReadCheckRunsAndCommitStatusesAsTheCommitsRuns() {
        HeadCheckRuns runs = GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(false, "FAILURE", LINT_RUNNING), suites("COMPLETED", 3), NO_APP, READ_AT)
                .orElseThrow();

        assertThat(runs.sha()).isEqualTo(SHA);
        assertThat(runs.observedAt()).isEqualTo(READ_AT);
        assertThat(runs.runs())
                .containsExactly(
                        new HeadCheckRuns.Run(
                                "server tests",
                                "GitHub Actions",
                                "failure",
                                true,
                                "https://github.com/acme/web/actions/runs/1/job/2",
                                Instant.parse("2026-10-26T11:50:00Z"),
                                Instant.parse("2026-10-26T11:58:00Z")),
                        new HeadCheckRuns.Run("lint", "Linter App", "in_progress", false, null, null, null),
                        new HeadCheckRuns.Run(
                                "ci/jenkins",
                                null,
                                "success",
                                true,
                                "https://jenkins.example/job/7",
                                null,
                                Instant.parse("2026-10-26T11:59:00Z")));
        // GitHub reports a failure while lint still runs: the list is not all that is to come.
        assertThat(runs.complete()).isFalse();
        assertThat(runs.finished()).isFalse();
    }

    @Test
    void shouldCallAListCompleteOnlyWhenNothingGitHubKnowsOfIsStillToCome() {
        assertThat(GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(false, "FAILURE", LINT_PASSED), suites("COMPLETED", 3), NO_APP, READ_AT))
                .hasValueSatisfying(runs -> assertThat(runs.finished()).isTrue());
        // Every listed run ended, but a suite with runs is still under way and can add a run that waits for another.
        assertThat(GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(false, "FAILURE", LINT_PASSED), suites("IN_PROGRESS", 2), NO_APP, READ_AT))
                .hasValueSatisfying(runs -> assertThat(runs.complete()).isFalse());
        // An app that never posts a run leaves its empty suite queued for ever.
        assertThat(GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(false, "SUCCESS", LINT_PASSED), suites("QUEUED", 0), NO_APP, READ_AT))
                .hasValueSatisfying(runs -> assertThat(runs.complete()).isTrue());
        assertThat(GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(true, "FAILURE", LINT_PASSED), suites("COMPLETED", 3), NO_APP, READ_AT))
                .hasValueSatisfying(runs -> assertThat(runs.complete()).isFalse());
        assertThat(GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(false, "EXPECTED", LINT_PASSED), suites("COMPLETED", 3), NO_APP, READ_AT))
                .hasValueSatisfying(runs -> assertThat(runs.complete()).isFalse());
        assertThat(GitHubCommitCheckRuns.toRuns(SHA, rollup(false, "SUCCESS", LINT_PASSED), null, NO_APP, READ_AT))
                .hasValueSatisfying(runs -> assertThat(runs.complete()).isFalse());
    }

    @Test
    void shouldLeaveHephaestusOwnSuiteAndRunOutOfTheCommitsRuns() {
        // Hephaestus's own check run is still under way, and GitHub's rollup is pending because of it.
        HeadCheckRuns runs = GitHubCommitCheckRuns.toRuns(
                        SHA, rollupWithOwnRun("PENDING"), suitesWithOwn("IN_PROGRESS"), OWN_APP, READ_AT)
                .orElseThrow();

        assertThat(runs.runs()).extracting(HeadCheckRuns.Run::name).containsExactly("server tests");
        assertThat(runs.finished()).isTrue();
        // Without the app id configured, the same run keeps the list unfinished.
        assertThat(GitHubCommitCheckRuns.toRuns(
                        SHA, rollupWithOwnRun("PENDING"), suitesWithOwn("IN_PROGRESS"), NO_APP, READ_AT))
                .hasValueSatisfying(unknown -> assertThat(unknown.finished()).isFalse());
    }

    @Test
    void shouldCallTheListIncompleteWhileASuiteWaitsForApprovalOfItsWorkflows() {
        // A fork pull request: the gated suites complete as ACTION_REQUIRED with no runs, and the rollup reads success.
        String json = """
                {"pageInfo": {"hasNextPage": false},
                 "nodes": [
                   {"status": "COMPLETED", "conclusion": "SUCCESS", "checkRuns": {"totalCount": 2}},
                   {"status": "COMPLETED", "conclusion": "ACTION_REQUIRED", "checkRuns": {"totalCount": 0}}]}
                """;
        var awaiting = GitHubGraphQlTestMapper.create().readValue(json, GHCheckSuiteConnection.class);

        HeadCheckRuns runs = GitHubCommitCheckRuns.toRuns(
                        SHA, rollup(false, "SUCCESS", LINT_PASSED), awaiting, NO_APP, READ_AT)
                .orElseThrow();

        assertThat(runs.complete()).isFalse();
        assertThat(runs.finished()).isFalse();
        assertThat(runs.limitation()).isEqualTo(HeadCheckRuns.AWAITING_APPROVAL);
        // A suite whose runs ended asking for action ran its CI: its runs say so, and nothing waits for approval.
        var ran = GitHubGraphQlTestMapper.create()
                .readValue(json.replace("\"totalCount\": 0", "\"totalCount\": 1"), GHCheckSuiteConnection.class);
        assertThat(GitHubCommitCheckRuns.toRuns(SHA, rollup(false, "SUCCESS", LINT_PASSED), ran, NO_APP, READ_AT))
                .hasValueSatisfying(read -> assertThat(read.limitation()).isNull());
    }

    @Test
    void shouldReadNothingWhenGitHubReturnsNoRollupForTheCommit() {
        assertThat(GitHubCommitCheckRuns.toRuns(SHA, null, suites("COMPLETED", 3), NO_APP, READ_AT))
                .isEmpty();
    }

    private static GHCheckSuiteConnection suitesWithOwn(String ownStatus) {
        String json = """
                {"pageInfo": {"hasNextPage": false},
                 "nodes": [
                   {"status": "COMPLETED", "app": {"databaseId": 15368}, "checkRuns": {"totalCount": 1}},
                   {"status": "%s", "app": {"databaseId": 4242}, "checkRuns": {"totalCount": 1}}]}
                """.formatted(ownStatus);
        return GitHubGraphQlTestMapper.create().readValue(json, GHCheckSuiteConnection.class);
    }

    private static GHStatusCheckRollup rollupWithOwnRun(String state) {
        String json = """
                {
                  "state": "%s",
                  "contexts": {
                    "pageInfo": {"hasNextPage": false},
                    "nodes": [
                      {
                        "__typename": "CheckRun",
                        "name": "server tests",
                        "status": "COMPLETED",
                        "conclusion": "SUCCESS",
                        "detailsUrl": null,
                        "startedAt": null,
                        "completedAt": null,
                        "checkSuite": {"app": {"name": "GitHub Actions", "databaseId": 15368}}
                      },
                      {
                        "__typename": "CheckRun",
                        "name": "Hephaestus",
                        "status": "IN_PROGRESS",
                        "conclusion": null,
                        "detailsUrl": null,
                        "startedAt": null,
                        "completedAt": null,
                        "checkSuite": {"app": {"name": "Hephaestus", "databaseId": 4242}}
                      }
                    ]
                  }
                }
                """.formatted(state);
        return GitHubGraphQlTestMapper.create().readValue(json, GHStatusCheckRollup.class);
    }

    private static GHCheckSuiteConnection suites(String status, int runs) {
        String json = """
                {"pageInfo": {"hasNextPage": false},
                 "nodes": [
                   {"status": "COMPLETED", "checkRuns": {"totalCount": 2}},
                   {"status": "%s", "checkRuns": {"totalCount": %d}}]}
                """.formatted(status, runs);
        return GitHubGraphQlTestMapper.create().readValue(json, GHCheckSuiteConnection.class);
    }

    private static GHStatusCheckRollup rollup(boolean hasNextPage, String state, String lint) {
        String json = """
                {
                  "state": "%s",
                  "contexts": {
                    "pageInfo": {"hasNextPage": %s},
                    "nodes": [
                      {
                        "__typename": "CheckRun",
                        "name": "server tests",
                        "status": "COMPLETED",
                        "conclusion": "FAILURE",
                        "detailsUrl": "https://github.com/acme/web/actions/runs/1/job/2",
                        "startedAt": "2026-10-26T11:50:00Z",
                        "completedAt": "2026-10-26T11:58:00Z",
                        "checkSuite": {"app": {"name": "GitHub Actions"}}
                      },
                      {
                        "__typename": "CheckRun",
                        "name": "lint",
                        %s,
                        "detailsUrl": null,
                        "startedAt": null,
                        "completedAt": null,
                        "checkSuite": {"app": {"name": "Linter App"}}
                      },
                      {
                        "__typename": "StatusContext",
                        "context": "ci/jenkins",
                        "state": "SUCCESS",
                        "targetUrl": "https://jenkins.example/job/7",
                        "createdAt": "2026-10-26T11:59:00Z"
                      }
                    ]
                  }
                }
                """.formatted(state, hasNextPage, lint);
        return GitHubGraphQlTestMapper.create().readValue(json, GHStatusCheckRollup.class);
    }
}
