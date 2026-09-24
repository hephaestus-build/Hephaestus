package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHCommit;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHIssueConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestCommit;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestCommitConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusCheckRollup;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHStatusState;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GitHubPullRequestDTOHeadChecksTest extends BaseUnitTest {

    private static final String HEAD = "c".repeat(40);

    @ParameterizedTest
    @CsvSource({"SUCCESS, SUCCESS", "ERROR, FAILURE", "FAILURE, FAILURE", "EXPECTED, PENDING", "PENDING, PENDING"})
    void shouldMapTheRollupStateToOneCheckState(GHStatusState state, CheckState expected) {
        assertThat(GitHubPullRequestDTO.toCheckState(state)).isEqualTo(expected);
    }

    @Test
    void shouldReadTheHeadAndItsRollupAndCallNoRollupNone() {
        GHPullRequest pr = new GHPullRequest();
        pr.setCommits(commits(HEAD, GHStatusState.FAILURE));

        GitHubPullRequestDTO.HeadChecks checks = GitHubPullRequestDTO.extractHeadChecks(pr);

        assertThat(checks).isNotNull();
        assertThat(checks.sha()).isEqualTo(HEAD);
        assertThat(checks.state()).isEqualTo(CheckState.FAILURE);

        pr.setCommits(commits(HEAD, null));
        GitHubPullRequestDTO.HeadChecks none = GitHubPullRequestDTO.extractHeadChecks(pr);
        assertThat(none).isNotNull();
        assertThat(none.state()).isEqualTo(CheckState.NONE);
    }

    @Test
    void shouldReadNothingWhenTheQueryDidNotSelectTheHead() {
        GHPullRequest pr = new GHPullRequest();

        assertThat(GitHubPullRequestDTO.extractHeadChecks(pr)).isNull();
        assertThat(GitHubPullRequestDTO.extractClosingIssueNumbers(pr)).isNull();
    }

    @Test
    void shouldKeepOnlyTheClosingReferencesIntoThisRepository() {
        GHPullRequest pr = new GHPullRequest();
        pr.setRepository(repository("acme/web"));
        GHIssueConnection references = new GHIssueConnection();
        references.setNodes(List.of(issue(12, "acme/web"), issue(7, "acme/other"), issue(30, "acme/web")));
        pr.setClosingIssuesReferences(references);

        assertThat(GitHubPullRequestDTO.extractClosingIssueNumbers(pr)).containsExactly(12, 30);
    }

    private static GHPullRequestCommitConnection commits(String oid, @Nullable GHStatusState state) {
        GHCommit commit = new GHCommit();
        commit.setOid(oid);
        if (state != null) {
            GHStatusCheckRollup rollup = new GHStatusCheckRollup();
            rollup.setState(state);
            commit.setStatusCheckRollup(rollup);
        }
        GHPullRequestCommit node = new GHPullRequestCommit();
        node.setCommit(commit);
        GHPullRequestCommitConnection connection = new GHPullRequestCommitConnection();
        connection.setTotalCount(3);
        connection.setNodes(List.of(node));
        return connection;
    }

    private static GHRepository repository(String nameWithOwner) {
        GHRepository repository = new GHRepository();
        repository.setNameWithOwner(nameWithOwner);
        return repository;
    }

    private static GHIssue issue(int number, String nameWithOwner) {
        GHIssue issue = new GHIssue();
        issue.setNumber(number);
        issue.setRepository(repository(nameWithOwner));
        return issue;
    }
}
