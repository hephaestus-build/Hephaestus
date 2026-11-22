package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.MirroredThreadStateQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
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
        var disputed = new HashSet<String>();
        // Two mirrored rows with one thread ref that disagree say nothing about the thread: it stays unknown.
        for (var row : rows.states(workspaceId, itemIds, threadRefs)) {
            Boolean seen = states.putIfAbsent(row.getThreadRef(), row.isResolved());
            if (seen != null && seen != row.isResolved()) disputed.add(row.getThreadRef());
        }
        disputed.forEach(states::remove);
        return Map.copyOf(states);
    }
}
