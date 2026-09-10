package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.json.JsonMapper;

class ReviewMemberAiPolicyTest extends BaseUnitTest {
    @Mock
    private MemberAiRoutingAdapter routing;

    @Mock
    private MemberAiPreferences preferences;

    @Mock
    private IssueRepository issues;

    @Mock
    private ReviewableArtifactOwnershipRepository ownership;

    private final JsonMapper mapper = JsonMapper.builder().build();
    private ReviewMemberAiPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ReviewMemberAiPolicy(routing, mapper, preferences, issues, ownership);
    }

    @Test
    void shouldUseTheReviewedAuthorRatherThanWhoRequestedTheReview() {
        var metadata = mapper.createObjectNode().put("pull_request_id", 9L).put("requested_by_user_id", 99L);
        var author = new User();
        author.setId(20L);
        var issue = new Issue();
        issue.setAuthor(author);
        when(ownership.pullRequestBelongsToWorkspace(1L, 9L)).thenReturn(true);
        when(issues.findById(9L)).thenReturn(Optional.of(issue));
        policy.binding(1L, AgentJobType.PULL_REQUEST_REVIEW, metadata);
        verify(routing).binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L);
    }

    @Test
    void shouldNeverReadAnArtifactFromAnotherWorkspaceToChooseTheDeveloper() {
        var metadata = mapper.createObjectNode().put("issue_id", 9L);
        when(preferences.forDeveloper(1L, null)).thenReturn(new MemberAiPreferences.Decision(true, null));
        assertThat(policy.permitsReview(1L, AgentJobType.ISSUE_REVIEW, metadata))
                .isFalse();
        verifyNoInteractions(issues);
    }

    @Test
    void shouldUseTheReviewerSubjectForAReviewerOccasion() {
        var metadata = mapper.createObjectNode()
                .put("pull_request_id", 9L)
                .put("about_user_id", 20L)
                .put("subject_role", "REVIEWER");
        policy.binding(1L, AgentJobType.PULL_REQUEST_REVIEW, metadata);
        verify(routing).binding(1L, AgentPurpose.PRACTICE_REVIEW, 20L);
        verifyNoInteractions(issues, ownership);
    }

    @Test
    void shouldWithholdCompletedResultsWhenDeveloperHasSinceChosenNoAi() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(mapper.createObjectNode().put("about_user_id", 20L));
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.NO_AI));
        assertThat(policy.allowsResult(job)).isFalse();
        verifyNoInteractions(routing);
    }

    @Test
    void shouldRejectMissingSnapshotInsteadOfAssumingItsProcessingLocation() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(mapper.createObjectNode().put("about_user_id", 20L));
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.ON_PREMISES));
        assertThat(policy.allowsResult(job)).isFalse();
        verifyNoInteractions(routing);
    }
}
