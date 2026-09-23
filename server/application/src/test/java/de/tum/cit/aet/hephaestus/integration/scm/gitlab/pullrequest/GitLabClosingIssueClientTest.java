package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitLabClosingIssueClientTest extends BaseUnitTest {

    @Test
    void shouldKeepOnlyTheIidsOfThisProjectsIssues() {
        List<Map<String, Object>> issues = List.of(
                Map.of("iid", 41, "project_id", 246765),
                Map.of("iid", 3, "project_id", 999),
                Map.of("iid", 42L, "project_id", 246765L));

        assertThat(GitLabClosingIssueClient.ownIids(issues, 246765L)).containsExactly(41, 42);
    }

    @Test
    void shouldReadAnEmptyAnswerAsNoLinks() {
        assertThat(GitLabClosingIssueClient.ownIids(List.of(), 246765L)).isEmpty();
        assertThat(GitLabClosingIssueClient.ownIids(null, 246765L)).isEmpty();
    }
}
