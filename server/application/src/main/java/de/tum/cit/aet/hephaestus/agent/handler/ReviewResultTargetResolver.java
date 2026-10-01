package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.providers.DocumentContentSource;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationSourceLiveness;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.ReviewTargetQuery;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
class ReviewResultTargetResolver {
    private final ReviewTargetQuery reviewTargets;
    private final ConversationSourceLiveness conversationSourceLiveness;
    private final DocumentProjection documentProjection;

    record Target(ArtifactKind type, Long id, Long aboutUserId) {}

    Target resolve(AgentJob job, JsonNode metadata) {
        String artifactKind = job.getArtifactKind() != null
                ? job.getArtifactKind().value()
                : metadata.has("artifact_kind") ? metadata.get("artifact_kind").asString() : null;
        if (artifactKind == null) {
            artifactKind = ArtifactKinds.PULL_REQUEST.value();
        }
        if (ArtifactKinds.CONVERSATION_THREAD.value().equals(artifactKind)) {
            JsonNode threadIdNode = metadata.get("slack_thread_id");
            if (threadIdNode == null || threadIdNode.isNull() || !threadIdNode.isNumber()) {
                throw new JobDeliveryException("Missing slack_thread_id in job metadata: jobId=" + job.getId());
            }
            JsonNode aboutUserNode = metadata.get("about_user_id");
            if (aboutUserNode == null || aboutUserNode.isNull() || !aboutUserNode.isNumber()) {
                throw new JobDeliveryException("Missing about_user_id in job metadata: jobId=" + job.getId());
            }
            String channelId = requiredMetadataText(metadata, "slack_channel_id", job);
            String threadTs = requiredMetadataText(metadata, "slack_thread_ts", job);
            long threadId = threadIdNode.asLong();
            long aboutUserId = aboutUserNode.asLong();
            if (!conversationSourceLiveness.isDeliverableThread(
                    job.getWorkspace().getId(), threadId, channelId, threadTs, aboutUserId)) {
                throw new JobDeliveryException(
                        "Conversation target is no longer authorized or does not match the job: jobId=" + job.getId());
            }
            return new Target(ArtifactKinds.CONVERSATION_THREAD, threadId, aboutUserId);
        }
        if (ArtifactKinds.DOCUMENT.value().equals(artifactKind)) {
            // Erasure during execution must prevent admission even when the captured evidence still exists.
            JsonNode documentIdNode = metadata.get(DocumentContentSource.DOCUMENT_ID_METADATA_KEY);
            if (documentIdNode == null || documentIdNode.isNull() || !documentIdNode.isNumber()) {
                throw new JobDeliveryException("Missing " + DocumentContentSource.DOCUMENT_ID_METADATA_KEY
                        + " in job metadata: jobId="
                        + job.getId());
            }
            JsonNode aboutUserNode = metadata.get("about_user_id");
            if (aboutUserNode == null || aboutUserNode.isNull() || !aboutUserNode.isNumber()) {
                throw new JobDeliveryException("Missing about_user_id in job metadata: jobId=" + job.getId());
            }
            long documentId = documentIdNode.asLong();
            boolean live = documentProjection
                    .documentById(job.getWorkspace().getId(), documentId)
                    .filter(document -> !document.deleted())
                    .isPresent();
            if (!live) {
                throw new JobDeliveryException(
                        "Document target is gone: documentId=" + documentId + ", jobId=" + job.getId());
            }
            return new Target(ArtifactKinds.DOCUMENT, documentId, aboutUserNode.asLong());
        }
        if (ArtifactKinds.ISSUE.value().equals(artifactKind)) {
            JsonNode issueIdNode = metadata.get("issue_id");
            if (issueIdNode == null || issueIdNode.isNull() || !issueIdNode.isNumber()) {
                throw new JobDeliveryException("Missing issue_id in job metadata: jobId=" + job.getId());
            }
            Long issueId = issueIdNode.asLong();
            ReviewTargetQuery.Target issue = reviewTargets
                    .findIssue(issueId)
                    .orElseThrow(() ->
                            new JobDeliveryException("Issue not found: issueId=" + issueId + ", jobId=" + job.getId()));
            if (issue.authorId() == null) {
                throw new JobDeliveryException("Issue has no author: issueId=" + issueId + ", jobId=" + job.getId());
            }
            requireMatchingArtifact(issue, metadata, "issue_number", job);
            return new Target(ArtifactKinds.ISSUE, issueId, issue.authorId());
        }
        if (!ArtifactKinds.PULL_REQUEST.value().equals(artifactKind)) {
            throw new JobDeliveryException(
                    "No delivery route for artifact kind: kind=" + artifactKind + ", jobId=" + job.getId());
        }
        JsonNode pullRequestIdNode = metadata.get("pull_request_id");
        if (pullRequestIdNode == null || pullRequestIdNode.isNull() || !pullRequestIdNode.isNumber()) {
            throw new JobDeliveryException("Missing pull_request_id in job metadata: jobId=" + job.getId());
        }
        Long pullRequestId = pullRequestIdNode.asLong();
        ReviewTargetQuery.Target pullRequest = reviewTargets
                .findPullRequest(pullRequestId)
                .orElseThrow(() -> new JobDeliveryException(
                        "Pull request not found: pullRequestId=" + pullRequestId + ", jobId=" + job.getId()));
        if (pullRequest.authorId() == null) {
            throw new JobDeliveryException(
                    "Pull request has no author: pullRequestId=" + pullRequestId + ", jobId=" + job.getId());
        }
        requireMatchingArtifact(pullRequest, metadata, "pr_number", job);
        if ("REVIEWER".equals(metadata.path("subject_role").asString())) {
            long reviewId = metadata.path("review_id").asLong(-1);
            long aboutUserId = metadata.path("about_user_id").asLong(-1);
            boolean matches = reviewTargets.reviewMatchesTarget(reviewId, pullRequestId, aboutUserId);
            if (!matches) {
                throw new JobDeliveryException(
                        "Submitted review no longer matches its PR and reviewer: reviewId=" + reviewId
                                + ", jobId="
                                + job.getId());
            }
            return new Target(ArtifactKinds.PULL_REQUEST, pullRequestId, aboutUserId);
        }
        return new Target(ArtifactKinds.PULL_REQUEST, pullRequestId, pullRequest.authorId());
    }

    private static String requiredMetadataText(JsonNode metadata, String field, AgentJob job) {
        String value = metadata.path(field).asString();
        if (value.isBlank()) {
            throw new JobDeliveryException("Missing " + field + " in job metadata: jobId=" + job.getId());
        }
        return value;
    }

    private static void requireMatchingArtifact(
            ReviewTargetQuery.Target artifact, JsonNode metadata, String numberKey, AgentJob job) {
        if (!PracticeFeedbackDeliveryPolicy.matchesArtifact(artifact, metadata, numberKey)) {
            throw new JobDeliveryException("Artifact metadata does not match the live target: jobId=" + job.getId());
        }
    }
}
