package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.handler.CitationVerification;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class EvidenceDeliveryAuthorization implements EvidenceAuthorization {

    private final AgentJobRepository jobRepository;
    private final ArtifactSourceCatalogRegistry sourceCatalogs;

    public EvidenceDeliveryAuthorization(
            AgentJobRepository jobRepository, ArtifactSourceCatalogRegistry sourceCatalogs) {
        this.jobRepository = jobRepository;
        this.sourceCatalogs = sourceCatalogs;
    }

    @Override
    public boolean permits(long workspaceId, Observation observation, SourceUsePurpose requestedPurpose) {
        return permits(workspaceId, observation.getAgentJobId(), observation.getEvidence(), requestedPurpose);
    }

    public boolean permits(
            long workspaceId, @Nullable UUID jobId, @Nullable JsonNode evidence, SourceUsePurpose requestedPurpose) {
        JsonNode citations = citationsOrNull(jobId, evidence);
        if (jobId == null || citations == null) {
            return false;
        }
        return jobRepository
                .findEvidenceContractVersion(jobId, workspaceId)
                .map(contractVersion -> permits(contractVersion, citations, requestedPurpose))
                .orElse(false);
    }

    @Override
    public Set<UUID> permitsAll(
            long workspaceId, Collection<Observation> observations, SourceUsePurpose requestedPurpose) {
        return permitsBatch(workspaceId, observations, requestedPurpose, false);
    }

    @Override
    public Set<UUID> permitsForNewDelivery(
            long workspaceId, Collection<Observation> observations, SourceUsePurpose requestedPurpose) {
        return permitsBatch(workspaceId, observations, requestedPurpose, true);
    }

    private Set<UUID> permitsBatch(
            long workspaceId,
            Collection<Observation> observations,
            SourceUsePurpose requestedPurpose,
            boolean newDelivery) {
        List<Citable> citable = new ArrayList<>();
        Set<UUID> jobIds = new HashSet<>();
        for (Observation observation : observations) {
            UUID observationId = observation.getId();
            UUID jobId = observation.getAgentJobId();
            JsonNode citations = citationsOrNull(jobId, observation.getEvidence());
            if (observationId == null || jobId == null || citations == null) {
                continue;
            }
            citable.add(new Citable(observationId, jobId, citations));
            jobIds.add(jobId);
        }
        if (citable.isEmpty()) {
            return Set.of();
        }
        // A run outside this workspace has no row and a run that recorded no evidence has a null value.
        // Dropping both here is what makes an absent key mean "not permitted", which is the answer the
        // empty Optional carries on the single-row path.
        Map<UUID, AgentJobRepository.EvidenceContractVersionRow> contractVersions = new HashMap<>();
        for (var row : jobRepository.findEvidenceContractVersions(workspaceId, jobIds)) {
            if (row.getContractVersion() != null) {
                contractVersions.put(row.getId(), row);
            }
        }
        Set<UUID> permitted = new HashSet<>();
        for (Citable entry : citable) {
            var row = contractVersions.get(entry.jobId());
            if (row == null || row.getContractVersion() == null) continue;
            if (newDelivery && !CitationVerification.isVerified(entry.jobId(), row.getAttempt(), entry.citations())) {
                continue;
            }
            if (permits(row.getContractVersion(), entry.citations(), requestedPurpose)) {
                permitted.add(entry.observationId());
            }
        }
        return permitted;
    }

    private record Citable(UUID observationId, UUID jobId, JsonNode citations) {}

    @Nullable
    private static JsonNode citationsOrNull(@Nullable UUID jobId, @Nullable JsonNode evidence) {
        if (jobId == null || evidence == null) {
            return null;
        }
        JsonNode citations = evidence.path("citations");
        return citations.isArray() && !citations.isEmpty() ? citations : null;
    }

    private boolean permits(String contractVersion, JsonNode citations, SourceUsePurpose requestedPurpose) {
        try {
            SourceContractVersion version = new SourceContractVersion(contractVersion);
            for (JsonNode citation : citations) {
                JsonNode sourceKind = citation.path("sourceKind");
                if (!sourceKind.isString()
                        || !sourceCatalogs.isSourceUsePermitted(
                                version, new SourceKind(sourceKind.asString()), requestedPurpose)) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
