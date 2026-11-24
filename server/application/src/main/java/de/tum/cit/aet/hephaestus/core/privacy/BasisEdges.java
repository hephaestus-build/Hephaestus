package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.BasisInvalidation;
import de.tum.cit.aet.hephaestus.core.privacy.spi.BasisKind;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
class BasisEdges implements BasisInvalidation {

    private final BasisEdgeRepository edges;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cite(long workspaceId, Ref dependent, Collection<Ref> sources) {
        for (Ref source : sources) {
            edges.cite(
                    workspaceId,
                    dependent.kind().name(),
                    dependent.id(),
                    source.kind().name(),
                    source.id());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Ref> stale(long workspaceId, Collection<Ref> dependents) {
        Map<BasisKind, List<UUID>> byKind = dependents.stream()
                .collect(Collectors.groupingBy(Ref::kind, Collectors.mapping(Ref::id, Collectors.toList())));
        Set<Ref> stale = new HashSet<>();
        byKind.forEach((kind, ids) ->
                edges.findStale(workspaceId, kind.name(), ids).forEach(id -> stale.add(new Ref(kind, id))));
        return Set.copyOf(stale);
    }
}
