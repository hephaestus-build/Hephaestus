package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewRunTargetRow;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

final class ReviewRunTargetMapper {

    private ReviewRunTargetMapper() {}

    static Target from(AgentJob job) {
        return from(job.getJobType(), job.getIntegrationKind(), job.getMetadata());
    }

    static Target from(ReviewRunTargetRow row) {
        return from(row.getJobType(), row.getIntegrationKind(), row.getMetadata());
    }

    private static Target from(
            AgentJobType jobType, @Nullable IntegrationKind integrationKind, @Nullable JsonNode metadata) {
        return switch (jobType) {
            case PULL_REQUEST_REVIEW ->
                new Target(
                        ArtifactKinds.PULL_REQUEST,
                        longValue(metadata, "pull_request_id"),
                        integrationKind,
                        integerValue(metadata, "pr_number"),
                        requiredTextValue(metadata, "title", "Pull request"),
                        textValue(metadata, "repository_full_name", null),
                        null,
                        textValue(metadata, "pr_url", null));
            case ISSUE_REVIEW ->
                new Target(
                        ArtifactKinds.ISSUE,
                        longValue(metadata, "issue_id"),
                        integrationKind,
                        integerValue(metadata, "issue_number"),
                        requiredTextValue(metadata, "title", "Issue"),
                        textValue(metadata, "repository_full_name", null),
                        null,
                        textValue(metadata, "issue_url", null));
            case CONVERSATION_REVIEW ->
                new Target(
                        ArtifactKinds.CONVERSATION_THREAD,
                        longValue(metadata, "slack_thread_id"),
                        integrationKind,
                        null,
                        "Conversation",
                        null,
                        textValue(metadata, "slack_channel_name", null),
                        null);
            // A document has no number; it sits in its collection the way a pull request sits in its
            // repository. No URL: the mirror stores a slug, and the server it hangs off is connection state
            // this mapper cannot reach — a link built from half of it would be a broken one.
            case DOCUMENT_REVIEW ->
                new Target(
                        ArtifactKinds.DOCUMENT,
                        longValue(metadata, "docs_document_id"),
                        integrationKind,
                        null,
                        requiredTextValue(metadata, "title", "Document"),
                        textValue(metadata, "docs_collection_name", null),
                        null,
                        null);
        };
    }

    private static @Nullable Long longValue(@Nullable JsonNode metadata, String field) {
        if (metadata == null || !metadata.path(field).isIntegralNumber()) return null;
        return metadata.path(field).asLong();
    }

    private static @Nullable Integer integerValue(@Nullable JsonNode metadata, String field) {
        if (metadata == null || !metadata.path(field).isIntegralNumber()) return null;
        return metadata.path(field).asInt();
    }

    private static @Nullable String textValue(@Nullable JsonNode metadata, String field, @Nullable String fallback) {
        if (metadata == null || !metadata.path(field).isString()) return fallback;
        String value = metadata.path(field).asString();
        return value.isBlank() ? fallback : value;
    }

    private static String requiredTextValue(@Nullable JsonNode metadata, String field, String fallback) {
        return Objects.requireNonNull(textValue(metadata, field, fallback));
    }
}
