package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.context.providers.PullRequestContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkChanges;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Material repair admission reads the staged identity, never admission or completion timestamps. */
@Component
@RequiredArgsConstructor
public class CapturedReviewedWorkChanges implements ReviewedWorkChanges {

    private final AgentJobRepository jobs;
    private final ObjectMapper mapper;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;

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
