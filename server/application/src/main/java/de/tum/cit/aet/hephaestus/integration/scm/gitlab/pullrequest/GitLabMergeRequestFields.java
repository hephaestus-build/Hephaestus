package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.client.ClientGraphQlResponse;

/**
 * Reads the merge request fields that merge advice depends on, telling a value GitLab stated apart from one it failed
 * to give. GitLab answers a field it could not resolve with {@code null} and an error at that field's path, so a
 * {@code null} alone is not an answer: each read asks Spring for the errors at, above or below the exact field path
 * ({@link org.springframework.graphql.ResponseField#getErrors()}). A page read names the merge request by its index,
 * {@code project.mergeRequests.nodes[i]}, so one merge request's error leaves the others' fields alone.
 */
final class GitLabMergeRequestFields {

    private GitLabMergeRequestFields() {}

    /** Whether the response carries an error for {@code path}, or for a field above or below it. */
    static boolean failed(ClientGraphQlResponse response, String path) {
        return !response.field(path).getErrors().isEmpty();
    }

    /**
     * The head pipeline of the merge request at {@code mergeRequestPath}: no pipeline only where GitLab returned the
     * field, as {@code null}, with no error; a pipeline only with its status and SHA and no error at or below it.
     */
    static GitLabHeadPipeline headPipeline(
            ClientGraphQlResponse response, String mergeRequestPath, Map<String, Object> mergeRequest) {
        String path = mergeRequestPath + ".headPipeline";
        if (!mergeRequest.containsKey("headPipeline") || failed(response, path)) {
            return GitLabHeadPipeline.NOT_CAPTURED;
        }
        Object pipeline = mergeRequest.get("headPipeline");
        if (pipeline == null) {
            return GitLabHeadPipeline.NO_PIPELINE;
        }
        if (pipeline instanceof Map<?, ?> fields
                && fields.get("status") instanceof String status
                && fields.get("sha") instanceof String sha
                && !sha.isBlank()) {
            return GitLabHeadPipeline.reported(status, sha);
        }
        return GitLabHeadPipeline.NOT_CAPTURED;
    }

    /** GitLab's {@code approved}, or {@code null} where it did not give one: a failed read is not a refusal. */
    static @Nullable Boolean approved(
            ClientGraphQlResponse response, String mergeRequestPath, Map<String, Object> mergeRequest) {
        if (failed(response, mergeRequestPath + ".approved")) {
            return null;
        }
        return mergeRequest.get("approved") instanceof Boolean approved ? approved : null;
    }

    /**
     * The nodes of the connection {@code name} of the merge request at {@code mergeRequestPath}, or {@code null} where
     * GitLab failed to give them: an error at or below the connection, or no list of nodes. It says nothing about
     * further pages; see {@link #overflows}.
     */
    @SuppressWarnings("unchecked")
    static @Nullable List<Map<String, Object>> nodes(
            ClientGraphQlResponse response, String mergeRequestPath, Map<String, Object> mergeRequest, String name) {
        if (failed(response, mergeRequestPath + "." + name)
                || !(mergeRequest.get(name) instanceof Map<?, ?> connection)
                || !(connection.get("nodes") instanceof List<?> nodes)) {
            return null;
        }
        return (List<Map<String, Object>>) nodes;
    }

    /** Whether the connection {@code name} holds more than the page read: GitLab counts more, or says more follows. */
    static boolean overflows(Map<String, Object> mergeRequest, String name, int read) {
        if (!(mergeRequest.get(name) instanceof Map<?, ?> connection)) {
            return false;
        }
        boolean more = connection.get("pageInfo") instanceof Map<?, ?> pageInfo
                && Boolean.TRUE.equals(pageInfo.get("hasNextPage"));
        return more || (connection.get("count") instanceof Number count && count.intValue() > read);
    }

    /**
     * The whole connection {@code name} as read in one page, each node mapped; {@code null} where it failed or holds
     * more than one page.
     */
    static <T> @Nullable List<T> wholePage(
            ClientGraphQlResponse response,
            String mergeRequestPath,
            Map<String, Object> mergeRequest,
            String name,
            Function<Map<String, Object>, T> mapping) {
        List<Map<String, Object>> nodes = nodes(response, mergeRequestPath, mergeRequest, name);
        if (nodes == null || overflows(mergeRequest, name, nodes.size())) {
            return null;
        }
        List<T> mapped = new ArrayList<>(nodes.size());
        for (Map<String, Object> node : nodes) {
            mapped.add(mapping.apply(node));
        }
        return mapped;
    }

    /** Reads a {@link GitLabMergeRequestProcessor.SyncUserData} from a user node. */
    static GitLabMergeRequestProcessor.SyncUserData user(Map<String, Object> userMap) {
        return new GitLabMergeRequestProcessor.SyncUserData(
                (String) userMap.get("id"),
                (String) userMap.get("username"),
                (String) userMap.get("name"),
                (String) userMap.get("avatarUrl"),
                (String) userMap.get("webUrl"),
                (String) userMap.get("publicEmail"));
    }

    /**
     * Reads a reviewer node with GitLab's state for them. {@code mergeRequestInteraction} is null for a reviewer
     * who can no longer access the merge request.
     *
     * @see <a href="https://docs.gitlab.com/api/graphql/reference/#usermergerequestinteraction">GitLab
     *     UserMergeRequestInteraction</a>
     */
    @SuppressWarnings("unchecked")
    static GitLabMergeRequestProcessor.SyncReviewerData reviewer(Map<String, Object> reviewerMap) {
        Map<String, Object> interaction = (Map<String, Object>) reviewerMap.get("mergeRequestInteraction");
        return new GitLabMergeRequestProcessor.SyncReviewerData(
                user(reviewerMap), interaction == null ? null : (String) interaction.get("reviewState"));
    }
}
