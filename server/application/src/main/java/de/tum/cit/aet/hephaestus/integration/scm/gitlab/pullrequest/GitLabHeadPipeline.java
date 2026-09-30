package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest;

import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.CheckState;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * What one read of a merge request showed about its head pipeline. GitLab answering that the head has no pipeline is
 * an observation; a read that failed, or that left the field or its status or SHA out, is not, and records nothing.
 *
 * @param status GitLab's {@code PipelineStatusEnum} name, for {@link Kind#REPORTED} only
 * @param sha the commit the reported pipeline ran for, for {@link Kind#REPORTED} only; it can be an earlier commit
 */
public record GitLabHeadPipeline(
        Kind kind, @Nullable String status, @Nullable String sha) {

    public enum Kind {
        /** GitLab answered {@code headPipeline: null}: the head has no pipeline. */
        NO_PIPELINE,
        /** GitLab reported a pipeline with its status and SHA. */
        REPORTED,
        /** The read did not capture the head pipeline. */
        NOT_CAPTURED,
    }

    public static final GitLabHeadPipeline NO_PIPELINE = new GitLabHeadPipeline(Kind.NO_PIPELINE, null, null);
    public static final GitLabHeadPipeline NOT_CAPTURED = new GitLabHeadPipeline(Kind.NOT_CAPTURED, null, null);

    public static GitLabHeadPipeline reported(String status, String sha) {
        return new GitLabHeadPipeline(Kind.REPORTED, status, sha);
    }

    /**
     * Records this observation as the provider's rollup for {@code pr}, read from GitLab by a request made at
     * {@code requestedAt}: a reported pipeline under the SHA it ran for, no pipeline under the head it was read for. An
     * observation stored since the request began stands ({@link PullRequest#observeHeadChecks(String, CheckState,
     * boolean, Instant)}).
     *
     * @return whether anything changed
     */
    public boolean observeOn(PullRequest pr, Instant requestedAt) {
        return switch (kind) {
            case REPORTED ->
                pr.observeHeadChecks(
                        Objects.requireNonNull(sha),
                        GitLabMergeRequestProcessor.mapPipelineStatus(Objects.requireNonNull(status)),
                        true,
                        requestedAt);
            case NO_PIPELINE -> {
                String head = pr.getHeadRefOid();
                yield head != null && pr.observeHeadChecks(head, CheckState.NO_PIPELINE, true, requestedAt);
            }
            case NOT_CAPTURED -> false;
        };
    }
}
