package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultProofAccess;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/** Delivery checks retained proof and live visibility without requiring a retained runtime job. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class EvidenceDeliveryAuthorization implements EvidenceAuthorization {
    private final ClasspathSourceCatalogRegistry sourceCatalogs;
    private final CitedSourceAccess access;
    private final ResultEvidenceQuery evidence;

    @Override
    public boolean permits(long workspaceId, Result result, SourceUsePurpose purpose) {
        return result.getId() != null
                && permitted(workspaceId, List.of(result), purpose, false).contains(result.getId());
    }

    @Override
    public Set<UUID> permitsAll(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        return permitted(workspaceId, results, purpose, false);
    }

    @Override
    public Set<UUID> permitsForNewDelivery(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        return permitted(workspaceId, results, purpose, true);
    }

    @Override
    public <T> T asOneRead(long workspaceId, Supplier<T> read) {
        return access.asOneRead(workspaceId, read);
    }

    private Set<UUID> permitted(
            long workspaceId, Collection<Result> results, SourceUsePurpose purpose, boolean newDelivery) {
        var owned = results.stream()
                .filter(result -> result.getId() != null && result.getWorkspaceId() == workspaceId)
                .filter(result -> !newDelivery || !result.isMigratedHistory())
                .toList();
        boolean readsHistory = ResultProofAccess.readsHistory(purpose);
        Set<UUID> permitted = new HashSet<>(access.permitsAllHistory(
                workspaceId,
                owned.stream()
                        .filter(result -> readsHistory && result.isMigratedHistory())
                        .toList(),
                purpose));
        var current = owned.stream()
                .filter(result -> !readsHistory || !result.isMigratedHistory())
                .toList();
        var live = access.permitsAll(workspaceId, current, purpose);
        var proven =
                current.stream().filter(result -> live.contains(result.getId())).toList();
        var citations = evidence.citations(workspaceId, proven);
        for (var result : proven) {
            if (citationsPermit(citations.getOrDefault(result.getId(), List.of()), purpose, newDelivery))
                permitted.add(result.getId());
        }
        return Set.copyOf(permitted);
    }

    private boolean citationsPermit(List<JsonNode> citations, SourceUsePurpose purpose, boolean newDelivery) {
        if (citations.isEmpty()) return false;
        if (newDelivery) {
            var proof = JsonNodeFactory.instance.arrayNode();
            citations.forEach(proof::add);
            if (!CitationVerification.isVerified(proof)) return false;
        }
        try {
            for (var citation : citations) {
                if (!sourceCatalogs.isSourceUsePermitted(
                        new SourceKind(citation.path("sourceKind").asString()), purpose)) return false;
            }
            return true;
        } catch (IllegalArgumentException unknownSource) {
            return false;
        }
    }
}
