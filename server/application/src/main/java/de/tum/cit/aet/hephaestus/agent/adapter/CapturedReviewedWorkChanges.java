package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.providers.LinkedWorkItemContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Material repair admission reads the staged identity, never admission or completion timestamps. */
@Component
@RequiredArgsConstructor
public class CapturedReviewedWorkChanges implements ReviewedWorkChanges {

    private final AgentJobRepository jobs;
    private final ObjectMapper mapper;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;
    private final PullRequestRepository pullRequests;
    private final IssueRepository issues;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    private static final SourceKind LINKED = new SourceKind("scm.linked-work-items");

    @Override
    public Set<UUID> materiallyChangedLinkedIssues(long workspaceId, Collection<UUID> runIds, long pullRequestId) {
        if (runIds.isEmpty()) return Set.of();
        var closing = pullRequests.findClosingIssuesById(pullRequestId);
        Set<UUID> changed = new HashSet<>();
        for (var row : jobs.findCapturedReviewedWork(workspaceId, runIds)) {
            var manifest = manifest(row);
            if (manifest != null
                    && sourceCatalogs.isSourceUsePermitted(
                            manifest.contractVersion(), LINKED, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)
                    && LinkedWorkItemContentSource.capturedClosingMaterialMatches(manifest, closing)
                            .filter(matches -> !matches)
                            .isPresent()) {
                changed.add(row.getId());
            }
        }
        return Set.copyOf(changed);
    }

    @Override
    @Transactional
    public boolean linkedCaptureCurrent(long workspaceId, UUID jobId, long pullRequestId, String signalRevision) {
        if (pullRequests.lockById(pullRequestId).isEmpty()) return false;
        var closingIds = pullRequests.findClosingIssueIdsById(pullRequestId);
        for (Long issueId : closingIds) {
            if (issues.lockForReview(issueId).isEmpty()) return false;
        }
        var closing = pullRequests.findClosingIssuesById(pullRequestId);
        if (!closing.stream()
                .map(issue -> issue.getId())
                .collect(java.util.stream.Collectors.toSet())
                .equals(Set.copyOf(closingIds))) return false;
        EntityManager managed = Objects.requireNonNull(entityManager);
        closing.forEach(managed::refresh);
        var pullRequest = pullRequests.findByIdWithAllForGate(pullRequestId).orElse(null);
        if (pullRequest == null) return false;
        managed.refresh(pullRequest);
        if (pullRequest.getDeletedAt() != null || pullRequest.getState() != Issue.State.MERGED) return false;
        var key = LinkedWorkItemContentSource.currentClosingMaterialKey(workspaceId, pullRequest, closing);
        if (key.isEmpty() || !key.get().revision().value().equals(signalRevision)) return false;
        return jobs.findCapturedReviewedWork(workspaceId, Set.of(jobId)).stream()
                .findFirst()
                .map(row -> manifest(row))
                .filter(manifest -> sourceCatalogs.isSourceUsePermitted(
                        manifest.contractVersion(), LINKED, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .flatMap(manifest -> LinkedWorkItemContentSource.capturedClosingMaterialMatches(manifest, closing))
                .orElse(false);
    }

    private @Nullable JobFolderIndex manifest(AgentJobRepository.CapturedReviewedWorkRow row) {
        if (row.getManifest() == null) return null;
        try {
            return mapper.readValue(row.getManifest(), JobFolderIndex.class);
        } catch (JacksonException | IllegalArgumentException ignored) {
            return null;
        }
    }

    @Override
    public Set<UUID> materiallyChanged(long workspaceId, Collection<UUID> runIds, PullRequestRevision current) {
        if (runIds.isEmpty()) {
            return Set.of();
        }
        String revision = ReviewedWork.revision(ArtifactKinds.PULL_REQUEST, current.title(), current.body());
        String head = current.head();
        Set<UUID> changed = new HashSet<>();
        for (var row : jobs.findCapturedReviewedWork(workspaceId, runIds)) {
            String stored = row.getReviewedWork();
            String version = row.getContractVersion();
            if (stored == null || version == null) continue;
            try {
                ReviewedWork captured = mapper.readValue(stored, ReviewedWork.class);
                SourceContractVersion contract = new SourceContractVersion(version);
                if (captured == null
                        || !ArtifactKinds.PULL_REQUEST.value().equals(captured.artifactKind())
                        || captured.artifactId() != current.artifactId()) {
                    continue;
                }
                boolean textChanged = sourceCatalogs.isSourceUsePermitted(
                                contract, PullRequestContentSource.CORE, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)
                        && !captured.titleAndDescriptionRevision().equals(revision);
                String capturedHead = captured.head();
                boolean headChanged = capturedHead != null
                        && head != null
                        && head.matches(CitationVerification.GIT_OBJECT_ID)
                        && sourceCatalogs.isSourceUsePermitted(
                                contract, PullRequestContentSource.DIFF, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW)
                        && !capturedHead.equals(head);
                if (textChanged || headChanged) {
                    changed.add(row.getId());
                }
            } catch (JacksonException | IllegalArgumentException e) {
                // An unreadable identity is unknown, not evidence that the developer changed the work.
            }
        }
        return Set.copyOf(changed);
    }
}
