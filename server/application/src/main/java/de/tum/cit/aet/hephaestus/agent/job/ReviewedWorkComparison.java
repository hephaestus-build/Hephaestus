package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.providers.IssueContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewedWorkRow;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * How what one run captured of a pull request or issue compares with given values of that work, for its title and
 * description as one and for its head, each on its own. Only a field the capture recorded, and whose source
 * {@code purpose} may use under the run's contract, is compared; a missing, malformed or foreign capture compares
 * nothing. A run from before {@link ReviewedWork} was recorded can show a description or head difference, never that
 * its title matched, so a matching description leaves its text unknown.
 */
public record ReviewedWorkComparison(
        Field titleAndDescription,
        Field head,
        List<String> checkedFields,
        @Nullable String capturedAt) {

    public enum Field {
        MATCHES,
        DIFFERS,
        UNKNOWN,
    }

    /** The jsonpaths {@link AgentJobRepository#findReviewedWork} takes for a legacy run's description and change. */
    public static final String DESCRIPTION_PATH = "$.manifest.sources[*] ? (@.state.availability == \"AVAILABLE\""
            + " && (@.kind == \"" + PullRequestContentSource.CORE + "\" || @.kind == \"" + IssueContentSource.CORE
            + "\")).artifacts[*] ? (@.path == \"" + PullRequestContentSource.DESCRIPTION_FILE + "\").sha256";

    public static final String CHANGE_PATH = "$.manifest.sources[*] ? (@.kind == \"" + PullRequestContentSource.DIFF
            + "\" && @.state.availability == \"AVAILABLE\").state.facts.immutableIdentity";

    public static final ReviewedWorkComparison NOTHING_COMPARED =
            new ReviewedWorkComparison(Field.UNKNOWN, Field.UNKNOWN, List.of(), null);

    public ReviewedWorkComparison {
        checkedFields = List.copyOf(checkedFields);
    }

    /** Whether a compared field differs; nothing uncompared ever counts as a difference. */
    public boolean differs() {
        return titleAndDescription == Field.DIFFERS || head == Field.DIFFERS;
    }

    public static ReviewedWorkComparison of(
            @Nullable ReviewedWorkRow capture,
            ArtifactKind kind,
            long artifactId,
            @Nullable String title,
            @Nullable String body,
            @Nullable String head,
            SourceUsePurpose purpose,
            ArtifactSourceCatalogRegistry sourceCatalogs,
            ObjectMapper objectMapper) {
        boolean pullRequest = ArtifactKinds.PULL_REQUEST.equals(kind);
        if (capture == null
                || !(pullRequest || ArtifactKinds.ISSUE.equals(kind))
                || !permitted(
                        capture,
                        pullRequest ? PullRequestContentSource.CORE : IssueContentSource.CORE,
                        purpose,
                        sourceCatalogs)) {
            return NOTHING_COMPARED;
        }
        boolean headPermitted =
                pullRequest && permitted(capture, PullRequestContentSource.DIFF, purpose, sourceCatalogs);
        List<String> checked = new ArrayList<>();
        Field text = Field.UNKNOWN;
        Field comparedHead = Field.UNKNOWN;
        String capturedAt = null;
        if (capture.getReviewedWork() != null) {
            ReviewedWork captured = parse(capture.getReviewedWork(), objectMapper);
            if (captured != null
                    && kind.value().equals(captured.artifactKind())
                    && captured.artifactId() == artifactId) {
                capturedAt = captured.capturedAt().toString();
                checked.add("title");
                checked.add("description");
                text = captured.titleAndDescriptionRevision().equals(ReviewedWork.revision(kind, title, body))
                        ? Field.MATCHES
                        : Field.DIFFERS;
                comparedHead = compareHead(captured.head(), head, headPermitted, checked);
            }
        } else if (reviewed(capture, pullRequest, artifactId)) {
            capturedAt = capture.getCapturedAt();
            String description = capture.getDescriptionSha256();
            if (description != null) {
                checked.add("description");
                if (!description.equals(ReviewedWork.descriptionDigest(body))) {
                    text = Field.DIFFERS;
                }
            }
            comparedHead = compareHead(ReviewedWork.headOf(capture.getChangeRange()), head, headPermitted, checked);
        }
        return new ReviewedWorkComparison(text, comparedHead, checked, capturedAt);
    }

    /** Whether a run from before {@code reviewedWork} was recorded reviewed this very pull request or issue. */
    private static boolean reviewed(ReviewedWorkRow capture, boolean pullRequest, long artifactId) {
        AgentJobType expected = pullRequest ? AgentJobType.PULL_REQUEST_REVIEW : AgentJobType.ISSUE_REVIEW;
        return expected.name().equals(capture.getJobType())
                && Long.toString(artifactId).equals(capture.getReviewedArtifactId());
    }

    private static Field compareHead(
            @Nullable String capturedHead, @Nullable String head, boolean permitted, List<String> checked) {
        if (!permitted || capturedHead == null || head == null) {
            return Field.UNKNOWN;
        }
        checked.add("head");
        return capturedHead.equals(head) ? Field.MATCHES : Field.DIFFERS;
    }

    private static boolean permitted(
            ReviewedWorkRow capture,
            SourceKind kind,
            SourceUsePurpose purpose,
            ArtifactSourceCatalogRegistry sourceCatalogs) {
        String version = capture.getContractVersion();
        if (version == null) {
            return false;
        }
        try {
            return sourceCatalogs.isSourceUsePermitted(new SourceContractVersion(version), kind, purpose);
        } catch (IllegalArgumentException unknownContract) {
            return false;
        }
    }

    private static @Nullable ReviewedWork parse(String json, ObjectMapper objectMapper) {
        try {
            return objectMapper.readValue(json, ReviewedWork.class);
        } catch (JacksonException | IllegalArgumentException malformed) {
            return null;
        }
    }
}
