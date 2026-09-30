package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.ReviewedWork;
import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
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
            if (stored == null) continue;
            try {
                ReviewedWork captured = mapper.readValue(stored, ReviewedWork.class);
                if (captured != null
                        && ArtifactKinds.PULL_REQUEST.value().equals(captured.artifactKind())
                        && captured.artifactId() == current.artifactId()
                        && (!captured.titleAndDescriptionRevision().equals(revision)
                                || (captured.head() != null
                                        && head != null
                                        && head.matches(CitationVerification.GIT_OBJECT_ID)
                                        && !captured.head().equals(head)))) {
                    changed.add(row.getId());
                }
            } catch (JacksonException | IllegalArgumentException e) {
                // An unreadable identity is unknown, not evidence that the developer changed the work.
            }
        }
        return Set.copyOf(changed);
    }
}
