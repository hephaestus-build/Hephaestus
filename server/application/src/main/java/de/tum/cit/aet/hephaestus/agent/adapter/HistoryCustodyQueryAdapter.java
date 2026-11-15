package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class HistoryCustodyQueryAdapter implements HistoryCustodyQuery {
    private final EvidenceFolderPersonDataCatalog copies;
    private final ReviewJobRepository jobs;
    private final ObjectMapper mapper;

    @Override
    public HistoryCustody find(long workspaceId, List<UUID> producingJobIds) {
        return copies.custody(workspaceId, producingJobIds);
    }

    /** The retained manifest keeps its capture time when admission retires the staged files. */
    @Override
    public Map<Receipt, Instant> capturedAt(long workspaceId, Collection<Receipt> receipts) {
        if (receipts.isEmpty()) return Map.of();
        Map<UUID, ReviewJobRepository.CaptureManifest> manifests = new HashMap<>();
        jobs.findCaptureManifests(
                        workspaceId,
                        receipts.stream()
                                .map(Receipt::producingJobId)
                                .distinct()
                                .toList())
                .forEach(job -> manifests.put(job.getId(), job));
        Map<Receipt, Instant> captured = new HashMap<>();
        for (var receipt : receipts) {
            var job = manifests.get(receipt.producingJobId());
            if (job != null && receipt.reviewId().equals(job.getReviewId()))
                capturedAt(job.getManifest()).ifPresent(at -> captured.put(receipt, at));
        }
        return captured;
    }

    private Optional<Instant> capturedAt(@Nullable String manifest) {
        if (manifest == null) return Optional.empty();
        try {
            var node = mapper.readTree(manifest);
            if (!node.isObject()) return Optional.empty();
            return Optional.of(
                    mapper.treeToValue(node, JobFolderIndex.Retained.class).capturedAt());
        } catch (JacksonException invalid) {
            return Optional.empty();
        }
    }
}
