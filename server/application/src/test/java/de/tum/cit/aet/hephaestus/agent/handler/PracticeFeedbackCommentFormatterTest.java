package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceSummaryQuery;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class PracticeFeedbackCommentFormatterTest extends BaseUnitTest {

    @Test
    void formatsSummaryMarkerBodyMetadataAndSettingsLink() {
        AgentJob job = job();
        PracticeFeedbackCommentFormatter formatter = formatter("https://hephaestus.example.com");

        String result = formatter.format("Test body content", job);

        assertThat(result)
                .contains(PullRequestCommentPoster.summaryMarkerFor(job))
                .contains("Test body content")
                .contains(
                        "<sub>Practice review &middot; model&lt;&amp;&gt; &middot; AI-generated and can be inaccurate."
                                + " Answer or dispute it in"
                                + " [Hephaestus](https://hephaestus.example.com/w/team/feedback/scm.pull_request/42).</sub>")
                .doesNotContain("React with")
                .contains(
                        "[Why you're seeing this and how to stop it](https://hephaestus.example.com/settings#practice-feedback)");
    }

    @Test
    void preservesBasePathAndNormalizesTrailingSlash() {
        PracticeFeedbackCommentFormatter formatter = formatter("https://hephaestus.example/app/");

        String result = formatter.format("Body", job());

        assertThat(result)
                .contains(
                        "[Why you're seeing this and how to stop it](https://hephaestus.example/app/settings#practice-feedback)");
    }

    @Test
    void appendsDisclosureAndSettingsLink() {
        String result = formatter("https://hephaestus.example").appendDisclosure("Approved feedback", job());

        assertThat(result)
                .startsWith("Approved feedback\n\n")
                .contains(
                        "<sub>Practice review &middot; model&lt;&amp;&gt; &middot; AI-generated and can be inaccurate."
                                + " Answer or dispute it in"
                                + " [Hephaestus](https://hephaestus.example/w/team/feedback/scm.pull_request/42).</sub>")
                .endsWith(
                        "<sub>[Why you're seeing this and how to stop it](https://hephaestus.example/settings#practice-feedback)</sub>\n");
    }

    @Test
    void appendsFeedbackPromptToInlineComment() {
        String result = formatter("https://hephaestus.example").appendInlineFeedbackPrompt("Inline feedback", job());

        assertThat(result)
                .isEqualTo("Inline feedback\n\n"
                        + "<sub>AI-generated &middot; Answer or dispute it in"
                        + " [Hephaestus](https://hephaestus.example/w/team/feedback/scm.pull_request/42).</sub>\n");
    }

    /** An issue comment links to the reader's reviews of the issue, the same place for every review of it. */
    @Test
    void linksAnIssueCommentToTheIssue() {
        AgentJob job = job();
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        job.setMetadata(new ObjectMapper().createObjectNode().put("issue_id", 7L));

        String result = formatter("https://hephaestus.example").format("Body", job);

        assertThat(result).contains("[Hephaestus](https://hephaestus.example/w/team/feedback/scm.issue/7)");
    }

    private static PracticeFeedbackCommentFormatter formatter(String webappUrl) {
        return new PracticeFeedbackCommentFormatter(
                new ApplicationProperties(null, new ApplicationProperties.Webapp(webappUrl)), teamWorkspace());
    }

    private static AgentJob job() {
        AgentJob job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setConfigSnapshot(new ObjectMapper().createObjectNode().put("upstreamModelId", "model<&>"));
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(new ObjectMapper().createObjectNode().put("pull_request_id", 42L));
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        job.setWorkspace(workspace);
        return job;
    }

    /** Every workspace is "team": what the footer's link needs of the workspace. */
    private static WorkspaceSummaryQuery teamWorkspace() {
        WorkspaceSummaryQuery workspaces = org.mockito.Mockito.mock(WorkspaceSummaryQuery.class);
        org.mockito.Mockito.when(workspaces.findById(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.of(new WorkspaceSummaryQuery.WorkspaceSummary(1L, "team", "Team")));
        return workspaces;
    }
}
