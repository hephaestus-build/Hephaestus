package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * Resolves a GitLab MR's global ID + diffRefs in a single GraphQL round-trip
 * ({@code GetMergeRequestGlobalId}). Shared by the GitLab feedback adapters so per-channel mutations
 * don't re-roundtrip the same lookup.
 *
 * <p>Gated on {@code hephaestus.integration.gitlab.enabled=true} to track
 * {@link GitLabGraphQlClientProvider} — the channel beans only load when the GitLab
 * GraphQL provider does.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
class GitLabMrResolver {

    static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(15);

    private final GitLabGraphQlClientProvider gitLabProvider;

    GitLabMrResolver(GitLabGraphQlClientProvider gitLabProvider) {
        this.gitLabProvider = gitLabProvider;
    }

    MrInfo resolve(long scopeId, String projectPath, int mrIid) {
        ClientGraphQlResponse response = gitLabProvider
                .forScope(scopeId)
                .documentName("GetMergeRequestGlobalId")
                .variable("fullPath", projectPath)
                .variable("iid", String.valueOf(mrIid))
                .execute()
                .block(GRAPHQL_TIMEOUT);

        if (response == null) {
            throw new FeedbackDeliveryException("Null response resolving MR info: " + projectPath + "!" + mrIid);
        }

        String globalId = Objects.requireNonNull(response)
                .field("project.mergeRequest.id")
                .getValue();
        if (globalId == null) {
            List<?> errors = Objects.requireNonNull(response).getErrors();
            throw new FeedbackDeliveryException("MR not found via GraphQL: " + projectPath
                    + "!"
                    + mrIid
                    + (errors.isEmpty() ? "" : ", errors=" + errors));
        }

        // diffRefs may be null if the MR has no diffs yet
        String baseSha = Objects.requireNonNull(response)
                .field("project.mergeRequest.diffRefs.baseSha")
                .getValue();
        String headSha = Objects.requireNonNull(response)
                .field("project.mergeRequest.diffRefs.headSha")
                .getValue();
        String startSha = Objects.requireNonNull(response)
                .field("project.mergeRequest.diffRefs.startSha")
                .getValue();

        return new MrInfo(globalId, baseSha, headSha, startSha);
    }

    /**
     * Splits {@code "project/full/path!42"} (GitLab MR external-id convention) into
     * the path + iid components needed by GraphQL.
     */
    static MrCoordinates parseSubjectExternalId(@Nullable String subjectExternalId) {
        if (subjectExternalId == null || subjectExternalId.isBlank()) {
            throw new FeedbackDeliveryException("subjectExternalId is required for GitLab MR feedback");
        }
        int bangIdx = subjectExternalId.lastIndexOf('!');
        if (bangIdx <= 0 || bangIdx == subjectExternalId.length() - 1) {
            throw new FeedbackDeliveryException(
                    "Invalid GitLab MR subjectExternalId (expected project/path!iid): " + subjectExternalId);
        }
        String projectPath = subjectExternalId.substring(0, bangIdx);
        int iid;
        try {
            iid = Integer.parseInt(subjectExternalId.substring(bangIdx + 1));
        } catch (NumberFormatException e) {
            throw new FeedbackDeliveryException(
                    "Invalid GitLab MR subjectExternalId — iid must be integer: " + subjectExternalId, e);
        }
        return new MrCoordinates(projectPath, iid);
    }

    /**
     * Resolves an issue's GraphQL global gid ({@code GetIssueGlobalId}). GitLab's {@code createNote}
     * mutation takes any {@code NoteableID}, so the same note-posting path serves issues once the gid
     * is resolved — only this lookup differs from the MR path.
     */
    String resolveIssueGid(long scopeId, String projectPath, int issueIid) {
        ClientGraphQlResponse response = gitLabProvider
                .forScope(scopeId)
                .documentName("GetIssueGlobalId")
                .variable("fullPath", projectPath)
                .variable("iid", String.valueOf(issueIid))
                .execute()
                .block(GRAPHQL_TIMEOUT);

        if (response == null) {
            throw new FeedbackDeliveryException("Null response resolving issue gid: " + projectPath + "#" + issueIid);
        }
        String gid = Objects.requireNonNull(response).field("project.issue.id").getValue();
        if (gid == null) {
            List<?> errors = Objects.requireNonNull(response).getErrors();
            throw new FeedbackDeliveryException("Issue not found via GraphQL: " + projectPath
                    + "#"
                    + issueIid
                    + (errors.isEmpty() ? "" : ", errors=" + errors));
        }
        return gid;
    }

    /** Splits {@code "project/full/path#42"} (GitLab issue external-id convention) into path + iid. */
    static MrCoordinates parseIssueSubjectExternalId(String subjectExternalId) {
        if (subjectExternalId == null || subjectExternalId.isBlank()) {
            throw new FeedbackDeliveryException("subjectExternalId is required for GitLab issue feedback");
        }
        int hashIdx = subjectExternalId.lastIndexOf('#');
        if (hashIdx <= 0 || hashIdx == subjectExternalId.length() - 1) {
            throw new FeedbackDeliveryException(
                    "Invalid GitLab issue subjectExternalId (expected project/path#iid): " + subjectExternalId);
        }
        try {
            return new MrCoordinates(
                    subjectExternalId.substring(0, hashIdx),
                    Integer.parseInt(subjectExternalId.substring(hashIdx + 1)));
        } catch (NumberFormatException e) {
            throw new FeedbackDeliveryException(
                    "Invalid GitLab issue subjectExternalId — iid must be integer: " + subjectExternalId, e);
        }
    }

    record MrInfo(
            String globalId,
            @Nullable String baseSha,
            @Nullable String headSha,
            @Nullable String startSha) {}

    record MrCoordinates(String projectPath, int iid) {}
}
