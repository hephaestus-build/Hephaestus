package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.context.providers.IssueContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The title, description and, for a pull request, head a review captured, as an identity stored work can be compared
 * with. Persisted as {@code evidence_snapshot.reviewedWork} and derived only from the staged metadata and the pinned
 * change, never from the work read again later: completion or admission times say nothing about which revision a
 * review read. It covers exactly these fields — not comments, checks, approvals, linked work or behaviour.
 */
public record ReviewedWork(
        String artifactKind,
        long artifactId,
        String titleAndDescriptionRevision,
        @Nullable String head,
        Instant capturedAt) {

    public static final String SNAPSHOT_KEY = "reviewedWork";

    private static final String METADATA = SandboxLayout.CONTEXT_PREFIX + "metadata.json";

    /** Read back from a stored snapshot, so a malformed one fails here rather than reading as an identity. */
    public ReviewedWork {
        ArtifactKind.of(Objects.requireNonNull(artifactKind, "artifactKind"));
        if (artifactId <= 0) {
            throw new IllegalArgumentException("artifactId must be positive");
        }
        Objects.requireNonNull(titleAndDescriptionRevision, "titleAndDescriptionRevision");
        if (new SignalRevision(titleAndDescriptionRevision).scheme().orElse(null) != RevisionScheme.CONTENT_DIGEST) {
            throw new IllegalArgumentException("titleAndDescriptionRevision must be a content digest");
        }
        if (head != null && !head.matches(CitationVerification.GIT_OBJECT_ID)) {
            throw new IllegalArgumentException("head must be a commit id");
        }
        Objects.requireNonNull(capturedAt, "capturedAt");
    }

    /**
     * The identity of what {@code manifestBytes} staged for artifact {@code artifactId}; empty when it staged no core.
     *
     * @throws JobPreparationException when the staged metadata names another commit than the pinned change, which is
     *     a capture that must not be reviewed
     */
    public static Optional<ReviewedWork> captured(
            byte[] manifestBytes, Map<String, byte[]> staged, long artifactId, ObjectMapper mapper) {
        JobFolderIndex manifest;
        try {
            manifest = mapper.readValue(manifestBytes, JobFolderIndex.class);
        } catch (JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        }
        ArtifactKind kind = ArtifactKind.of(manifest.artifactKind());
        boolean pullRequest = ArtifactKinds.PULL_REQUEST.equals(kind);
        if (!pullRequest && !ArtifactKinds.ISSUE.equals(kind)) {
            return Optional.empty();
        }
        SourceKind core = pullRequest ? PullRequestContentSource.CORE : IssueContentSource.CORE;
        byte[] metadata = staged.get(METADATA);
        if (available(manifest, core) == null || metadata == null) {
            return Optional.empty();
        }
        JsonNode staging;
        try {
            staging = mapper.readTree(metadata);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        JsonNode title = staging.path("title");
        JsonNode body = staging.path("body");
        if (!textOrNull(title) || !textOrNull(body)) {
            return Optional.empty();
        }
        String head = null;
        if (pullRequest) {
            head = pinnedHead(manifest);
            if (head != null && !head.equals(staging.path("commit_sha").asString(""))) {
                throw new JobPreparationException(
                        "Staged pull request metadata names another commit than the pinned change");
            }
        }
        return Optional.of(new ReviewedWork(
                kind.value(),
                artifactId,
                revision(kind, title.isNull() ? null : title.asString(), body.isNull() ? null : body.asString()),
                head,
                manifest.capturedAt()));
    }

    /**
     * The one framing of a title and description, under the null convention each capture stages: an issue's missing
     * description is staged as empty text, a pull request's as absent.
     */
    public static String revision(ArtifactKind kind, @Nullable String title, @Nullable String body) {
        String staged = ArtifactKinds.ISSUE.equals(kind) && body == null ? "" : body;
        return SignalRevision.ofContentDigest(title, staged).value();
    }

    /** The SHA-256 of the {@code description.md} a capture stages for {@code body}, as its manifest records it. */
    public static String descriptionDigest(@Nullable String body) {
        return ProvenanceDigest.sha256Hex((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
    }

    /** The head of a pinned {@code base:head} change identity, or {@code null} for one that is not. */
    public static @Nullable String headOf(@Nullable String range) {
        String[] parts = range == null ? new String[0] : range.split(":", -1);
        return parts.length == 2
                        && parts[0].matches(CitationVerification.GIT_OBJECT_ID)
                        && parts[1].matches(CitationVerification.GIT_OBJECT_ID)
                ? parts[1]
                : null;
    }

    private static @Nullable String pinnedHead(JobFolderIndex manifest) {
        SourceCaptureState.Available diff = available(manifest, PullRequestContentSource.DIFF);
        return diff == null ? null : headOf(diff.facts().immutableIdentity());
    }

    private static SourceCaptureState.@Nullable Available available(JobFolderIndex manifest, SourceKind kind) {
        for (SourceCapture source : manifest.sources()) {
            if (source.kind().equals(kind) && source.state() instanceof SourceCaptureState.Available available) {
                return available;
            }
        }
        return null;
    }

    private static boolean textOrNull(JsonNode node) {
        return node.isNull() || node.isString();
    }
}
