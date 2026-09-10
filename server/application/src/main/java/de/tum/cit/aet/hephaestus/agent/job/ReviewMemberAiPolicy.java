package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The reviewed developer, not the administrator or bot who requested the review, controls its model route. */
@Service
@RequiredArgsConstructor
public class ReviewMemberAiPolicy {
    private final MemberAiRoutingAdapter routing;
    private final ObjectMapper objectMapper;
    private final MemberAiPreferences preferences;
    private final IssueRepository issues;
    private final ReviewableArtifactOwnershipRepository ownership;

    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> binding(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        return routing.binding(workspaceId, AgentPurpose.PRACTICE_REVIEW, subject(workspaceId, type, metadata));
    }

    @Transactional(readOnly = true)
    public boolean permitsReview(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        return preferences
                .forDeveloper(workspaceId, subject(workspaceId, type, metadata))
                .permitsAi();
    }

    @Transactional(readOnly = true)
    public boolean allows(AgentJob job, LlmModelResolver.ConnectionRef model) {
        return routing.allows(
                job.getWorkspace().getId(),
                subject(job.getWorkspace().getId(), job.getJobType(), job.getMetadata()),
                model);
    }

    @Transactional(readOnly = true)
    public boolean allowsResult(AgentJob job) {
        var decision = preferences.forDeveloper(
                job.getWorkspace().getId(), subject(job.getWorkspace().getId(), job.getJobType(), job.getMetadata()));
        if (!decision.permitsAi()) return false;
        if (decision.choice() == null && !decision.choiceRequired()) return true;
        if (job.getConfigSnapshot() == null) return false;
        try {
            var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
            return routing.allows(
                    job.getWorkspace().getId(),
                    subject(job.getWorkspace().getId(), job.getJobType(), job.getMetadata()),
                    new LlmModelResolver.ConnectionRef(
                            snapshot.connectionScope(),
                            snapshot.connectionId(),
                            snapshot.modelId(),
                            snapshot.workspaceId()));
        } catch (IllegalArgumentException | IllegalStateException | tools.jackson.core.JacksonException e) {
            return false;
        }
    }

    private @Nullable Long subject(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        Long explicit = id(metadata, "about_user_id");
        if (explicit != null) return explicit;
        Long artifact = id(metadata, type == AgentJobType.PULL_REQUEST_REVIEW ? "pull_request_id" : "issue_id");
        if (artifact == null) return null;
        boolean owned =
                switch (type) {
                    case PULL_REQUEST_REVIEW -> ownership.pullRequestBelongsToWorkspace(workspaceId, artifact);
                    case ISSUE_REVIEW -> ownership.issueBelongsToWorkspace(workspaceId, artifact);
                    default -> false;
                };
        return owned
                ? issues.findById(artifact)
                        .map(Issue::getAuthor)
                        .map(User::getId)
                        .orElse(null)
                : null;
    }

    private static @Nullable Long id(@Nullable JsonNode metadata, String key) {
        if (metadata == null) return null;
        JsonNode value = metadata.path(key);
        return value.isIntegralNumber() && value.canConvertToLong() && value.asLong() > 0 ? value.asLong() : null;
    }
}
