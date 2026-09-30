package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.providers.mentor.MentorContextQueryRepository.StoredWork;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository.ReviewedWorkRow;
import de.tum.cit.aet.hephaestus.agent.job.ReviewedWorkComparison;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * How the work a review captured relates to Hephaestus's stored copy of that work now, for observations the caller
 * has already authorized. It compares with the mirror, never the provider, and only the fields it names — a match
 * says nothing about comments, checks, approvals, linked work or behaviour. The source the comparison reads must be
 * usable in conversation under the review's contract; anything it cannot establish is {@code UNKNOWN}, and neither
 * text nor digests leave it. Title and description are compared as one, so a difference there does not say which of
 * them changed, and a head comparison says nothing about which commit, if any, changed them.
 */
@Component
@RequiredArgsConstructor
class ReviewedWorkCoverage {

    enum CoreCoverage {
        MATCHES_STORED_WORK,
        DIFFERS_FROM_STORED_WORK,
        UNKNOWN,
    }

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
                : jobRepository
                        .findReviewedWork(
                                workspaceId,
                                jobIds,
                                ReviewedWorkComparison.DESCRIPTION_PATH,
                                ReviewedWorkComparison.CHANGE_PATH)
                        .stream()
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
        ArtifactKind kind = observation.getArtifactKind();
        Long artifactId = observation.getArtifactId();
        ReviewedWorkComparison comparison = kind == null || artifactId == null || stored == null
                ? ReviewedWorkComparison.NOTHING_COMPARED
                : ReviewedWorkComparison.of(
                        capture,
                        kind,
                        artifactId,
                        stored.getTitle(),
                        stored.getBody(),
                        stored.getHead(),
                        SourceUsePurpose.CONVERSATIONAL_MENTORING,
                        sourceCatalogs,
                        objectMapper);
        if (comparison.capturedAt() != null) {
            node.put("capturedAt", comparison.capturedAt());
        }
        List<String> required = pullRequest ? PULL_REQUEST_FIELDS : ISSUE_FIELDS;
        CoreCoverage coverage = comparison.differs()
                ? CoreCoverage.DIFFERS_FROM_STORED_WORK
                : comparison.checkedFields().containsAll(required)
                        ? CoreCoverage.MATCHES_STORED_WORK
                        : CoreCoverage.UNKNOWN;
        node.put("coreCoverage", coverage.name());
        node.put(
                "titleAndDescriptionCoverage",
                coverage(comparison.titleAndDescription()).name());
        node.put("headCoverage", coverage(comparison.head()).name());
        ArrayNode checked = objectMapper.createArrayNode();
        comparison.checkedFields().forEach(checked::add);
        node.set("checkedFields", checked);
        node.put("providerFreshness", "UNKNOWN");
        return node;
    }

    private static CoreCoverage coverage(ReviewedWorkComparison.Field field) {
        return switch (field) {
            case MATCHES -> CoreCoverage.MATCHES_STORED_WORK;
            case DIFFERS -> CoreCoverage.DIFFERS_FROM_STORED_WORK;
            case UNKNOWN -> CoreCoverage.UNKNOWN;
        };
    }

    private static Map<Long, StoredWork> byId(List<StoredWork> rows) {
        // Two monitor rows with one path return the same work twice.
        return rows.stream()
                .collect(Collectors.toMap(StoredWork::getId, Function.identity(), (first, second) -> first));
    }
}
