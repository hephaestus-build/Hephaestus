package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.CitedSourceAccess;
import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.model.ResultFacts;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEvidenceQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultProofAccess;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
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
                && permitsAll(workspaceId, List.of(result.facts()), purpose).contains(result.getId());
    }

    @Override
    public Set<UUID> permitsAll(long workspaceId, Collection<ResultFacts> results, SourceUsePurpose purpose) {
        return permitted(workspaceId, results, purpose, false, Set::copyOf);
    }

    /**
     * A new delivery also verifies the full citations, so it needs each result's whole record. It checks each citation's
     * own source kind as well, so it never rests on recorded kinds alone.
     */
    @Override
    public Set<UUID> permitsForNewDelivery(long workspaceId, Collection<Result> results, SourceUsePurpose purpose) {
        Map<UUID, Result> byId = results.stream()
                .filter(result -> result.getId() != null)
                .collect(Collectors.toMap(Result::getId, Function.identity(), (first, second) -> first));
        return permitted(workspaceId, byId.values().stream().map(Result::facts).toList(), purpose, true, proven -> {
            var citations = evidence.citations(
                    workspaceId,
                    proven.stream()
                            .map(id -> Objects.requireNonNull(byId.get(id)))
                            .toList());
            return proven.stream()
                    .filter(id -> sendable(citations.getOrDefault(id, List.of()), purpose))
                    .collect(Collectors.toSet());
        });
    }

    @Override
    public <T> T asOneRead(long workspaceId, Supplier<T> read) {
        return access.asOneRead(workspaceId, read);
    }

    /** {@code verified} narrows the live, proven results to those whose citations a new delivery may send. */
    private Set<UUID> permitted(
            long workspaceId,
            Collection<ResultFacts> results,
            SourceUsePurpose purpose,
            boolean newDelivery,
            Function<List<UUID>, Set<UUID>> verified) {
        var owned = results.stream()
                .filter(result -> result.workspaceId() == workspaceId)
                .filter(result -> !newDelivery || !result.migratedHistory())
                .toList();
        Set<UUID> permitted = new HashSet<>(access.permitsAllHistory(
                workspaceId,
                owned.stream()
                        .filter(result -> ResultProofAccess.readsAsHistory(result, purpose))
                        .toList(),
                purpose));
        var current = owned.stream()
                .filter(result -> !ResultProofAccess.readsAsHistory(result, purpose))
                .toList();
        var live = access.permitsAll(workspaceId, current, purpose);
        var proven =
                current.stream().map(ResultFacts::id).filter(live::contains).toList();
        if (proven.isEmpty()) return Set.copyOf(permitted);
        var problemCitations = evidence.problemCitations(workspaceId, proven);
        var deliverable = verified.apply(proven);
        for (var result : current) {
            if (deliverable.contains(result.id())
                    && kindsPermit(result, problemCitations.getOrDefault(result.id(), List.of()), purpose))
                permitted.add(result.id());
        }
        return Set.copyOf(permitted);
    }

    /**
     * Every source that the result rests on, cited or consulted, must be usable for the purpose. A result that rests on
     * no source at all is not permitted, and neither is one whose sources are not all named.
     */
    private boolean kindsPermit(ResultFacts result, List<JsonNode> problemCitations, SourceUsePurpose purpose) {
        var own = result.sourceKinds();
        if (own == null) return false;
        List<String> kinds = new ArrayList<>(own);
        problemCitations.forEach(
                citation -> kinds.add(citation.path("sourceKind").asString()));
        return usable(kinds, purpose);
    }

    /** Verified citations, each from a source usable for the purpose. */
    private boolean sendable(List<JsonNode> citations, SourceUsePurpose purpose) {
        var proof = JsonNodeFactory.instance.arrayNode();
        citations.forEach(proof::add);
        return CitationVerification.isVerified(proof)
                && usable(
                        citations.stream()
                                .map(citation -> citation.path("sourceKind").asString())
                                .toList(),
                        purpose);
    }

    private boolean usable(List<String> kinds, SourceUsePurpose purpose) {
        if (kinds.isEmpty()) return false;
        try {
            for (var kind : kinds) {
                if (!sourceCatalogs.isSourceUsePermitted(new SourceKind(kind), purpose)) return false;
            }
            return true;
        } catch (IllegalArgumentException unknownSource) {
            return false;
        }
    }
}
