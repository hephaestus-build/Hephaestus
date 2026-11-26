package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
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
    private final AccountRepository accounts;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cite(long workspaceId, long ownerAccountId, Ref dependent, Collection<Ref> sources) {
        // The owner's lifecycle lock comes before the reference, as for an export: an erasure that has begun refuses
        // it.
        boolean admitted = accounts.lockStatusForShare(ownerAccountId)
                .map(Account.Status::valueOf)
                .filter(status -> status != Account.Status.DELETING && status != Account.Status.DELETED)
                .isPresent();
        if (!admitted) {
            throw new IllegalStateException("The owner is gone or being erased: no reference is written.");
        }
        for (Ref source : sources) {
            edges.cite(
                    workspaceId,
                    ownerAccountId,
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
