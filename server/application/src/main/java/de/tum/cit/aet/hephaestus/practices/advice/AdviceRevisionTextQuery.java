package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.practices.spi.AdviceRevisionTexts;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class AdviceRevisionTextQuery implements AdviceRevisionTexts {
    private final PracticeAdviceRevisionRepository revisions;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> lockWithText(long workspaceId, Collection<UUID> revisionIds) {
        if (revisionIds.isEmpty()) return List.of();
        return revisions.lockWithText(
                workspaceId, revisionIds.stream().distinct().toArray(UUID[]::new));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean anyWithoutText(long workspaceId, Collection<UUID> revisionIds) {
        return !revisionIds.isEmpty() && revisions.anyWithoutText(workspaceId, revisionIds.toArray(UUID[]::new));
    }
}
