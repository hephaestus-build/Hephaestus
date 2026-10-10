package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
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
    private final PersonProcessingSuppression suppression;

    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> binding(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        return routing.binding(workspaceId, AgentPurpose.PRACTICE_REVIEW, subject(workspaceId, type, metadata));
    }

    /** The binding that serves one precompute purpose of the review, routed for the same developer as the review. */
    @Transactional(readOnly = true)
    public Optional<WorkspaceAgentBinding> precomputeBinding(
            long workspaceId, AgentPurpose purpose, AgentJobType type, @Nullable JsonNode metadata) {
        return routing.precomputeBinding(workspaceId, purpose, subject(workspaceId, type, metadata));
    }

    @Transactional(readOnly = true)
    public boolean isProcessingSuppressed(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        return processingSuppressed(workspaceId, type, metadata);
    }

    private boolean processingSuppressed(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        Long developerId = subject(workspaceId, type, metadata);
        if (developerId != null && suppression.isUserSuppressed(developerId)) return true;
        String key;
        String kind;
        switch (type) {
            case PULL_REQUEST_REVIEW -> {
                key = "pull_request_id";
                kind = "scm.pull_request";
            }
            case ISSUE_REVIEW -> {
                key = "issue_id";
                kind = "scm.issue";
            }
            case CONVERSATION_REVIEW -> {
                key = "slack_thread_id";
                kind = "chat.conversation_thread";
            }
            case DOCUMENT_REVIEW -> {
                key = "docs_document_id";
                kind = "docs.document";
            }
            default -> {
                return false;
            }
        }
        Long artifactId = id(metadata, key);
        return artifactId != null && suppression.isArtifactSuppressed(workspaceId, kind, artifactId);
    }

    @Transactional(readOnly = true)
    public boolean permitsReview(long workspaceId, AgentJobType type, @Nullable JsonNode metadata) {
        if (processingSuppressed(workspaceId, type, metadata)) return false;
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
        if (processingSuppressed(job.getWorkspace().getId(), job.getJobType(), job.getMetadata())) return false;
        return evaluatePerson(job, subject(job.getWorkspace().getId(), job.getJobType(), job.getMetadata()));
    }

    /** The job's processor must also be permitted to read another developer's person-scoped history. */
    @Transactional(readOnly = true)
    public boolean allowsPerson(AgentJob job, @Nullable Long personId) {
        return evaluatePerson(job, personId);
    }

    private boolean evaluatePerson(AgentJob job, @Nullable Long personId) {
        if (personId != null && suppression.isUserSuppressed(personId)) return false;
        var decision = preferences.forDeveloper(job.getWorkspace().getId(), personId);
        if (!decision.permitsAi()) return false;
        if (job.getConfigSnapshot() == null) return false;
        try {
            var snapshot = ConfigSnapshot.fromJson(job.getConfigSnapshot(), objectMapper);
            return routing.allows(
                    job.getWorkspace().getId(),
                    personId,
                    new LlmModelResolver.ConnectionRef(
                            snapshot.connectionScope(),
                            snapshot.connectionId(),
                            snapshot.modelId(),
                            snapshot.workspaceId()));
        } catch (IllegalArgumentException | IllegalStateException | JacksonException e) {
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
                    case PULL_REQUEST_REVIEW -> ownership.belongsToWorkspace(workspaceId, PullRequest.class, artifact);
                    case ISSUE_REVIEW -> ownership.belongsToWorkspace(workspaceId, Issue.class, artifact);
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
