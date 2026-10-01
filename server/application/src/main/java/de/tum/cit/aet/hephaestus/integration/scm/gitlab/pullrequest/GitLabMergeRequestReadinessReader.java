package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabGraphQlResponseHandler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncConstants;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabSyncException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.stereotype.Service;

/**
 * Reads one merge request's merge readiness from GitLab: its head, merge status, head pipeline, reviewers and
 * approvers. A webhook stores what it carries and none of these, so the message handler reads them after the event,
 * outside its transaction, and the processor applies them only while they still describe the stored head
 * ({@link GitLabMergeRequestProcessor#applyReadiness}).
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabMergeRequestReadinessReader {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestReadinessReader.class);

    static final String DOCUMENT = "GetMergeRequestReadiness";
    private static final String MERGE_REQUEST = "project.mergeRequest";

    /** One more request after GitLab asks to wait out its rate limit; the next event or sync reads again. */
    private static final int ATTEMPTS = 2;

    private final GitLabGraphQlClientProvider graphQlClientProvider;
    private final GitLabGraphQlResponseHandler responseHandler;
    private final GitLabProperties gitLabProperties;

    public GitLabMergeRequestReadinessReader(
            GitLabGraphQlClientProvider graphQlClientProvider,
            GitLabGraphQlResponseHandler responseHandler,
            GitLabProperties gitLabProperties) {
        this.graphQlClientProvider = graphQlClientProvider;
        this.responseHandler = responseHandler;
        this.gitLabProperties = gitLabProperties;
    }

    /**
     * What GitLab said about one merge request. Each field GitLab failed to give is {@code null}, or
     * {@link GitLabHeadPipeline#NOT_CAPTURED}, never a value: an approver list that failed to load is not an empty
     * one. A reviewer or approver list longer than one page is not read here and is {@code null} as well.
     *
     * @param projectNativeId the project GitLab resolved the path to, which must be the stored repository
     * @param state GitLab's {@code MergeRequestState}, such as {@code opened}
     */
    public record Facts(
            long projectNativeId,
            long mergeRequestNativeId,
            String state,
            Instant updatedAt,
            String headSha,
            @Nullable Boolean mergeable,
            @Nullable String detailedMergeStatus,
            @Nullable Boolean approved,
            GitLabHeadPipeline headPipeline,
            @Nullable List<GitLabMergeRequestProcessor.SyncReviewerData> reviewers,
            @Nullable List<GitLabMergeRequestProcessor.SyncUserData> approvers,
            Merge merge) {}

    /**
     * What GitLab says about a merged merge request's merge: who merged it, when, and the commit it left. Each is
     * {@code null} where GitLab named none or failed to give it — a fast-forward merge can leave no merge commit, and a
     * missing merger is never someone else, such as the author or whoever sent the hook.
     *
     * @see <a href="https://docs.gitlab.com/api/merge_requests/#retrieve-a-merge-request">GitLab merge request
     *     fields</a>
     */
    public record Merge(
            GitLabMergeRequestProcessor.@Nullable SyncUserData user,
            @Nullable Instant mergedAt,
            @Nullable String commitSha) {
        public static final Merge UNKNOWN = new Merge(null, null, null);
    }

    /**
     * Reads merge request {@code iid} of {@code projectPath} with the scope's connection.
     *
     * @return what GitLab said, or {@code null} when the read failed or GitLab did not name the merge request, its
     *     project, head and version: then nothing is known
     */
    public @Nullable Facts read(Long scopeId, String projectPath, int iid) {
        String context = sanitizeForLog(projectPath) + "!" + iid;
        HttpGraphQlClient client;
        try {
            graphQlClientProvider.acquirePermission();
            client = graphQlClientProvider.forScope(scopeId);
        } catch (RuntimeException e) {
            log.warn(
                    "Skipped merge request readiness read: context={}, reason={}",
                    context,
                    sanitizeForLog(e.getMessage()));
            return null;
        }
        try {
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                ClientGraphQlResponse response = client.documentName(DOCUMENT)
                        .variable("fullPath", projectPath)
                        .variable("iid", String.valueOf(iid))
                        .execute()
                        .block(gitLabProperties.graphqlTimeout());
                var handled = responseHandler.handle(response, "merge request readiness for " + context, log);
                if (handled.action() == GitLabGraphQlResponseHandler.HandleResult.Action.RETRY) {
                    continue;
                }
                if (handled.action() == GitLabGraphQlResponseHandler.HandleResult.Action.ABORT) {
                    graphQlClientProvider.recordFailure(new GitLabSyncException("Invalid GraphQL response"));
                    return null;
                }
                graphQlClientProvider.recordSuccess();
                Facts facts = decode(Objects.requireNonNull(response));
                if (facts == null) {
                    log.warn("GitLab did not describe the merge request whole: context={}", context);
                }
                return facts;
            }
            return null;
        } catch (RuntimeException e) {
            graphQlClientProvider.recordFailure(e);
            log.warn(
                    "Could not read merge request readiness: context={}, reason={}",
                    context,
                    sanitizeForLog(e.getMessage()));
            return null;
        }
    }

    /** The facts in a response to {@link #DOCUMENT}, or {@code null} where it does not identify the merge request. */
    @SuppressWarnings("unchecked")
    static @Nullable Facts decode(ClientGraphQlResponse response) {
        Object projectId = response.field("project.id").getValue();
        Object value = response.field(MERGE_REQUEST).getValue();
        if (!(projectId instanceof String projectGid) || !(value instanceof Map<?, ?>)) {
            return null;
        }
        Map<String, Object> node = (Map<String, Object>) value;
        String mergeRequestGid = identity(response, node, "id");
        String state = identity(response, node, "state");
        String headSha = identity(response, node, "diffHeadSha");
        Instant updatedAt = BaseGitLabProcessor.parseGitLabTimestamp(identity(response, node, "updatedAt"));
        if (mergeRequestGid == null || state == null || headSha == null || updatedAt == null) {
            return null;
        }
        long projectNativeId;
        long mergeRequestNativeId;
        try {
            projectNativeId = GitLabSyncConstants.extractNumericId(projectGid);
            mergeRequestNativeId = GitLabSyncConstants.extractNumericId(mergeRequestGid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Boolean mergeable = GitLabMergeRequestFields.failed(response, MERGE_REQUEST + ".mergeable")
                ? null
                : node.get("mergeable") instanceof Boolean known ? known : null;
        String detailedMergeStatus = GitLabMergeRequestFields.failed(response, MERGE_REQUEST + ".detailedMergeStatus")
                ? null
                : node.get("detailedMergeStatus") instanceof String status ? status : null;
        return new Facts(
                projectNativeId,
                mergeRequestNativeId,
                state,
                updatedAt,
                headSha,
                mergeable,
                detailedMergeStatus,
                GitLabMergeRequestFields.approved(response, MERGE_REQUEST, node),
                GitLabMergeRequestFields.headPipeline(response, MERGE_REQUEST, node),
                GitLabMergeRequestFields.wholePage(
                        response, MERGE_REQUEST, node, "reviewers", GitLabMergeRequestFields::reviewer),
                GitLabMergeRequestFields.wholePage(
                        response, MERGE_REQUEST, node, "approvedBy", GitLabMergeRequestFields::user),
                merge(response, node));
    }

    /** The merge's facts, each read without an error or left unknown. */
    @SuppressWarnings("unchecked")
    private static Merge merge(ClientGraphQlResponse response, Map<String, Object> node) {
        GitLabMergeRequestProcessor.SyncUserData user = null;
        if (!GitLabMergeRequestFields.failed(response, MERGE_REQUEST + ".mergeUser")
                && node.get("mergeUser") instanceof Map<?, ?> mergeUser
                && mergeUser.get("id") instanceof String) {
            user = GitLabMergeRequestFields.user((Map<String, Object>) mergeUser);
        }
        Instant mergedAt = GitLabMergeRequestFields.failed(response, MERGE_REQUEST + ".mergedAt")
                ? null
                : BaseGitLabProcessor.parseGitLabTimestamp(node.get("mergedAt") instanceof String text ? text : null);
        String commitSha = GitLabMergeRequestFields.failed(response, MERGE_REQUEST + ".mergeCommitSha")
                ? null
                : node.get("mergeCommitSha") instanceof String text && !text.isBlank() ? text : null;
        return new Merge(user, mergedAt, commitSha);
    }

    /** A field the facts are identified by: present, a non-blank string, and read without an error. */
    private static @Nullable String identity(ClientGraphQlResponse response, Map<String, Object> node, String field) {
        if (GitLabMergeRequestFields.failed(response, MERGE_REQUEST + "." + field)) {
            return null;
        }
        return node.get(field) instanceof String text && !text.isBlank() ? text : null;
    }
}
