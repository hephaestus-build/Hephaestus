package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.context.providers.IssueContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** Manifests and staged metadata shaped exactly as a pull request or issue capture writes them. */
public final class ReviewedWorkFixtures {

    public static final String BASE = "a".repeat(40);

    private ReviewedWorkFixtures() {}

    /** A pull request capture staging {@code body} as its description, with the change pinned at {@code base:head}. */
    public static ArtifactSourceManifest pullRequestManifest(
            Instant capturedAt, @Nullable String body, @Nullable String head) {
        List<SourceCapture> sources = new ArrayList<>();
        sources.add(core(PullRequestContentSource.CORE, capturedAt, body));
        if (head != null) {
            sources.add(new SourceCapture(
                    PullRequestContentSource.DIFF,
                    new SourceCaptureState.Available(
                            SourceContentState.NON_EMPTY,
                            SourceCompleteness.COMPLETE,
                            new SourceCaptureFacts(capturedAt, null, null, BASE + ":" + head)),
                    List.of(new SourceArtifact(
                            PullRequestContentSource.CHANGE_FILE, "application/json", "c".repeat(64), 1))));
        }
        return new ArtifactSourceManifest(
                ArtifactSourceCatalogRegistry.CURRENT_VERSION,
                "0".repeat(64),
                ArtifactKinds.PULL_REQUEST.value(),
                capturedAt,
                sources);
    }

    public static ArtifactSourceManifest issueManifest(Instant capturedAt, @Nullable String body) {
        return new ArtifactSourceManifest(
                ArtifactSourceCatalogRegistry.CURRENT_VERSION,
                "0".repeat(64),
                ArtifactKinds.ISSUE.value(),
                capturedAt,
                List.of(core(IssueContentSource.CORE, capturedAt, body)));
    }

    /** The {@code metadata.json} a pull request capture stages; an issue's carries no commit. */
    public static byte[] metadata(
            ObjectMapper mapper, @Nullable String title, @Nullable String body, @Nullable String commit) {
        var node = mapper.createObjectNode().put("title", title).put("body", body);
        if (commit != null) {
            node.put("commit_sha", commit);
        }
        return mapper.writeValueAsBytes(node);
    }

    private static SourceCapture core(SourceKind kind, Instant capturedAt, @Nullable String body) {
        return new SourceCapture(
                kind,
                new SourceCaptureState.Available(
                        SourceContentState.NON_EMPTY,
                        SourceCompleteness.COMPLETE,
                        new SourceCaptureFacts(capturedAt, null, null, null)),
                List.of(
                        new SourceArtifact(
                                SandboxLayout.CONTEXT_PREFIX + "metadata.json", "application/json", "d".repeat(64), 1),
                        new SourceArtifact(
                                PullRequestContentSource.DESCRIPTION_FILE,
                                "text/markdown",
                                ReviewedWork.descriptionDigest(body),
                                1)));
    }
}
