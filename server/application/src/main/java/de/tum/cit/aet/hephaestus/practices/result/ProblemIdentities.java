package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.spi.ProblemIdentityQuery;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
class ProblemIdentities implements ProblemIdentityQuery {
    private final ProblemRepository problems;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> identities(long workspaceId, Collection<UUID> problemIds) {
        if (problemIds.isEmpty()) return Map.of();
        var identities = new HashMap<UUID, String>();
        problems.findIdentities(workspaceId, problemIds)
                .forEach(problem -> identities.put(problem.id(), problem.identity()));
        return Map.copyOf(identities);
    }
}
