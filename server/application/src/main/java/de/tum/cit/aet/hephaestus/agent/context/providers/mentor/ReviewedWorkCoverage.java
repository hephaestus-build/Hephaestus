package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.providers.IssueContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorContextQueryRepository.StoredWork;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewedWorkRow;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceActorSelector;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How the work a review captured relates to Hephaestus's stored copy of that work now, for observations the caller
 * has already authorized. It compares with the mirror, never the provider, and only the fields it names — a match
 * says nothing about comments, checks, approvals, linked work or behaviour. The source the comparison reads must be
 * usable in conversation under the review's contract; anything it cannot establish is {@code UNKNOWN}, and neither
 * text nor digests leave it.
 */
@Component
@RequiredArgsConstructor
class ReviewedWorkCoverage {

    enum CoreCoverage {
        MATCHES_STORED_WORK,
        DIFFERS_FROM_STORED_WORK,
        UNKNOWN,
    }

    private static final String DESCRIPTION_PATH = "$.manifest.sources[*] ? (@.state.availability == \"AVAILABLE\""
            + " && (@.kind == \"" + PullRequestContentSource.CORE + "\" || @.kind == \"" + IssueContentSource.CORE
            + "\")).artifacts[*] ? (@.path == \"" + PullRequestContentSource.DESCRIPTION_FILE + "\").sha256";
    private static final String CHANGE_PATH = "$.manifest.sources[*] ? (@.kind == \"" + PullRequestContentSource.DIFF
            + "\" && @.state.availability == \"AVAILABLE\").state.facts.immutableIdentity";
    private static final List<String> PULL_REQUEST_FIELDS = List.of("title", "description", "head");
    private static final List<String> ISSUE_FIELDS = List.of("title", "description");

    private final AgentJobRepository jobRepository;
    private final MentorContextQueryRepository queryRepository;
    private final WorkspaceActorSelector actorSelector;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;
    private final ObjectMapper objectMapper;

    /**
     * One {@code reviewedWork} node per observation id; an observation not about a pull request or issue gets one too.
     * Its own snapshot, so every capture is compared with the stored work as it stood at one moment, whatever the
     * caller's transaction writes.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Map<UUID, ObjectNode> of(long workspaceId, Collection<Observation> observations) {
        Set<UUID> jobIds = new LinkedHashSet<>();
        Set<Long> pullRequestIds = new LinkedHashSet<>();
        Set<Long> issueIds = new LinkedHashSet<>();
        for (Observation observation : observations) {
            if (observation.getArtifactId() == null) continue;
            if (ArtifactKinds.PULL_REQUEST.equals(observation.getArtifactKind())) {
                pullRequestIds.add(observation.getArtifactId());
            } else if (ArtifactKinds.ISSUE.equals(observation.getArtifactKind())) {
                issueIds.add(observation.getArtifactId());
            } else {
                continue;
            }
            jobIds.add(observation.getAgentJobId());
        }
        Map<UUID, ReviewedWorkRow> captures = jobIds.isEmpty()
                ? Map.of()
                : jobRepository.findReviewedWork(workspaceId, jobIds, DESCRIPTION_PATH, CHANGE_PATH).stream()
                        .collect(Collectors.toMap(ReviewedWorkRow::getId, Function.identity()));
        Optional<Long> providerId =
                jobIds.isEmpty() ? Optional.empty() : actorSelector.connectedProviderId(workspaceId);
        Map<Long, StoredWork> pullRequests = providerId
                .filter(id -> !pullRequestIds.isEmpty())
                .map(id -> byId(queryRepository.findStoredPullRequests(workspaceId, id, pullRequestIds)))
                .orElse(Map.of());
        Map<Long, StoredWork> issues = providerId
                .filter(id -> !issueIds.isEmpty())
                .map(id -> byId(queryRepository.findStoredIssues(workspaceId, id, issueIds)))
                .orElse(Map.of());

        Map<UUID, ObjectNode> nodes = new HashMap<>();
        for (Observation observation : observations) {
            boolean pullRequest = ArtifactKinds.PULL_REQUEST.equals(observation.getArtifactKind());
            Long artifactId = observation.getArtifactId();
            StoredWork stored = artifactId == null ? null : (pullRequest ? pullRequests : issues).get(artifactId);
            nodes.put(
                    observation.getId(),
                    describe(observation, captures.get(observation.getAgentJobId()), stored, pullRequest));
        }
        return nodes;
    }

    private ObjectNode describe(
            Observation observation,
            @Nullable ReviewedWorkRow capture,
            @Nullable StoredWork stored,
            boolean pullRequest) {
        ObjectNode node = objectMapper.createObjectNode();
        ArrayNode checked = objectMapper.createArrayNode();
        ArtifactKind kind = observation.getArtifactKind();
        Long artifactId = observation.getArtifactId();
        boolean differs = false;
        if ((pullRequest || ArtifactKinds.ISSUE.equals(kind))
                && kind != null
                && artifactId != null
                && capture != null
                && stored != null
                && permitted(capture, pullRequest ? PullRequestContentSource.CORE : IssueContentSource.CORE)) {
            boolean headPermitted = pullRequest && permitted(capture, PullRequestContentSource.DIFF);
            if (capture.getReviewedWork() != null) {
                ReviewedWork captured = parse(capture.getReviewedWork());
                if (captured != null
                        && kind.value().equals(captured.artifactKind())
                        && captured.artifactId() == artifactId) {
                    node.put("capturedAt", captured.capturedAt().toString());
                    checked.add("title").add("description");
                    differs = !captured.titleAndDescriptionRevision()
                            .equals(ReviewedWork.revision(kind, stored.getTitle(), stored.getBody()));
                    differs |= compareHead(captured.head(), stored.getHead(), headPermitted, checked);
                }
            } else if (reviewed(capture, pullRequest, artifactId)) {
                if (capture.getCapturedAt() != null) {
                    node.put("capturedAt", capture.getCapturedAt());
                }
                String description = capture.getDescriptionSha256();
                if (description != null) {
                    checked.add("description");
                    differs = !description.equals(ReviewedWork.descriptionDigest(stored.getBody()));
                }
                differs |= compareHead(
                        ReviewedWork.headOf(capture.getChangeRange()), stored.getHead(), headPermitted, checked);
            }
        }
        List<String> required = pullRequest ? PULL_REQUEST_FIELDS : ISSUE_FIELDS;
        CoreCoverage coverage = differs
                ? CoreCoverage.DIFFERS_FROM_STORED_WORK
                : checked.valueStream().map(JsonNode::asString).toList().containsAll(required)
                        ? CoreCoverage.MATCHES_STORED_WORK
                        : CoreCoverage.UNKNOWN;
        node.put("coreCoverage", coverage.name());
        node.set("checkedFields", checked);
        node.put("providerFreshness", "UNKNOWN");
        return node;
    }

    /** Whether a run from before {@code reviewedWork} was recorded reviewed this very pull request or issue. */
    private static boolean reviewed(ReviewedWorkRow capture, boolean pullRequest, long artifactId) {
        AgentJobType expected = pullRequest ? AgentJobType.PULL_REQUEST_REVIEW : AgentJobType.ISSUE_REVIEW;
        return expected.name().equals(capture.getJobType())
                && Long.toString(artifactId).equals(capture.getReviewedArtifactId());
    }

    private static boolean compareHead(
            @Nullable String capturedHead, @Nullable String storedHead, boolean permitted, ArrayNode checked) {
        if (!permitted || capturedHead == null || storedHead == null) {
            return false;
        }
        checked.add("head");
        return !capturedHead.equals(storedHead);
    }

    private boolean permitted(ReviewedWorkRow capture, SourceKind kind) {
        String version = capture.getContractVersion();
        if (version == null) {
            return false;
        }
        try {
            return sourceCatalogs.isSourceUsePermitted(
                    new SourceContractVersion(version), kind, SourceUsePurpose.CONVERSATIONAL_MENTORING);
        } catch (IllegalArgumentException unknownContract) {
            return false;
        }
    }

    private @Nullable ReviewedWork parse(@Nullable String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ReviewedWork.class);
        } catch (JacksonException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Map<Long, StoredWork> byId(List<StoredWork> rows) {
        // Two monitor rows with one path return the same work twice.
        return rows.stream()
                .collect(Collectors.toMap(StoredWork::getId, Function.identity(), (first, second) -> first));
    }
}
