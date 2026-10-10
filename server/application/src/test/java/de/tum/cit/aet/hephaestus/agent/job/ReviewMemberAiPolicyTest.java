package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiChoice;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.List;
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

    @Mock
    private PersonProcessingSuppression suppression;

    private ReviewMemberAiPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ReviewMemberAiPolicy(routing, mapper, preferences, issues, ownership, suppression);
    }

    @Test
    void shouldUseTheReviewedAuthorRatherThanWhoRequestedTheReview() {
        var metadata = mapper.createObjectNode().put("pull_request_id", 9L).put("requested_by_user_id", 99L);
        var author = new User();
        author.setId(20L);
        var issue = new Issue();
        issue.setAuthor(author);
        when(ownership.belongsToWorkspace(1L, PullRequest.class, 9L)).thenReturn(true);
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
    void shouldRejectMissingSnapshotInsteadOfAssumingItsDataHandlingTier() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(mapper.createObjectNode().put("about_user_id", 20L));
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.IN_HOUSE_ONLY));
        assertThat(policy.allowsResult(job)).isFalse();
        verifyNoInteractions(routing);
    }

    @Test
    void shouldRejectMissingSnapshotForUnansweredOptionalChoice() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(mapper.createObjectNode().put("about_user_id", 20L));
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        assertThat(policy.allowsResult(job)).isFalse();
        verifyNoInteractions(routing);
    }

    @Test
    void shouldRecheckSnapshotModelForUnansweredOptionalChoice() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setMetadata(mapper.createObjectNode().put("about_user_id", 20L));
        job.setConfigSnapshot(new ConfigSnapshot(
                        ConfigSnapshot.SCHEMA_VERSION,
                        "OPENAI",
                        "https://example.test/v1",
                        "model",
                        null,
                        null,
                        null,
                        null,
                        FundingSource.INSTANCE,
                        2L,
                        3L,
                        1L,
                        30,
                        false,
                        null,
                        null,
                        null)
                .toJson(mapper));
        when(preferences.forDeveloper(1L, 20L)).thenReturn(new MemberAiPreferences.Decision(false, null));

        assertThat(policy.allowsResult(job)).isFalse();
        verify(routing).allows(eq(1L), eq(20L), any());
    }

    @Test
    void shouldStopRepositorylessReviewWhenTheExactNativeSourceIsSuppressed() {
        var metadata = mapper.createObjectNode().put("slack_thread_id", 9L);
        when(suppression.isArtifactSuppressed(1L, "chat.conversation_thread", 9L))
                .thenReturn(true);
        assertThat(policy.isProcessingSuppressed(1L, AgentJobType.CONVERSATION_REVIEW, metadata))
                .isTrue();
        assertThat(policy.permitsReview(1L, AgentJobType.CONVERSATION_REVIEW, metadata))
                .isFalse();
    }

    /** The review's precompute models are routed for the reviewed developer and capped at the review's own tier. */
    @Test
    void shouldGiveNoCloudPrecomputeModelWhenTheCloudMembersReviewRunsInHouse() {
        var bindings = mock(WorkspaceAgentBindingRepository.class);
        var resolver = mock(LlmModelResolver.class);
        var routed = new ReviewMemberAiPolicy(
                new MemberAiRoutingAdapter(
                        bindings, preferences, resolver, mock(WorkspaceRepository.class), suppression),
                mapper,
                preferences,
                issues,
                ownership,
                suppression);
        when(preferences.forDeveloper(1L, 20L))
                .thenReturn(new MemberAiPreferences.Decision(true, MemberAiChoice.CLOUD));
        var review = readyBinding(resolver, AgentPurpose.PRACTICE_REVIEW, DataHandlingTier.IN_HOUSE);
        var decision = readyBinding(resolver, AgentPurpose.PRACTICE_DECISION, DataHandlingTier.CLOUD);
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(List.of(review));
        when(bindings.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_DECISION))
                .thenReturn(List.of(decision));
        var metadata = mapper.createObjectNode().put("about_user_id", 20L);

        assertThat(routed.binding(1L, AgentJobType.PULL_REQUEST_REVIEW, metadata))
                .contains(review);
        assertThat(routed.precomputeBinding(
                        1L, AgentPurpose.PRACTICE_DECISION, AgentJobType.PULL_REQUEST_REVIEW, metadata))
                .isEmpty();
    }

    private static WorkspaceAgentBinding readyBinding(
            LlmModelResolver resolver, AgentPurpose purpose, DataHandlingTier tier) {
        var binding = new WorkspaceAgentBinding();
        binding.setPurpose(purpose);
        binding.setDataHandlingTier(tier);
        binding.setEnabled(true);
        lenient().when(resolver.isAvailable(binding)).thenReturn(true);
        return binding;
    }
}
