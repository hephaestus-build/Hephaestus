package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MirroredThreadStateQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the state of review threads from the pull and merge request mirror. GitHub threads change state by their
 * resolve and unresolve events. GitLab threads change state by a read of the merge request's discussions.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ScmMirroredThreadStateQuery implements MirroredThreadStateQuery {
    private final ScmFeedbackThreadStateRepository rows;

    @Override
    @Transactional(readOnly = true)
    public Map<String, Boolean> resolved(long workspaceId, Collection<Long> itemIds, Collection<String> threadRefs) {
        if (itemIds.isEmpty() || threadRefs.isEmpty()) return Map.of();
        var states = new HashMap<String, Boolean>();
        for (var row : rows.states(workspaceId, itemIds, threadRefs)) states.put(row.getThreadRef(), row.isResolved());
        return Map.copyOf(states);
    }
}
