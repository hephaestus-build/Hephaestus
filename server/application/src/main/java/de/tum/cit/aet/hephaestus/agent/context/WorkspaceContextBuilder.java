package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureContribution;
import de.tum.cit.aet.hephaestus.evidence.capture.CaptureRequest;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.ConversationContextRequest;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class WorkspaceContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextBuilder.class);
    private final List<ContentSource> conversationSources;
    private final List<ItemSource> itemSources;
    private final ClasspathSourceCatalogRegistry catalogs;
    private final JobFolderIndexBuilder manifests;

    public WorkspaceContextBuilder(
            List<ContentSource> conversationSources,
            List<ItemSource> itemSources,
            ClasspathSourceCatalogRegistry catalogs,
            JobFolderIndexBuilder manifests) {
        var sorted = new ArrayList<>(conversationSources);
        AnnotationAwareOrderComparator.sort(sorted);
        this.conversationSources = List.copyOf(sorted);
        this.itemSources = List.copyOf(itemSources);
        this.catalogs = catalogs;
        this.manifests = manifests;
    }

    public Optional<JsonNode> read(long workspaceId, long accountId, String path) {
        var values = conversationSources.stream()
                .map(source -> source.read(workspaceId, accountId, path))
                .flatMap(Optional::stream)
                .toList();
        if (values.size() > 1) throw new IllegalStateException("A conversation resource has multiple owners");
        return values.stream().findFirst();
    }

    public Map<String, byte[]> build(ConversationContextRequest request) {
        var files = new LinkedHashMap<String, byte[]>();
        for (var source : conversationSources) {
            if (!source.supports(request)) continue;
            var contributed = new LinkedHashMap<String, byte[]>();
            try {
                source.contribute(request, contributed);
            } catch (RuntimeException failure) {
                if (source.required()) throw failure;
                log.warn(
                        "Conversation context source unavailable: source={}, failure={}",
                        source.getClass().getSimpleName(),
                        failure.getClass().getSimpleName());
                continue;
            }
            contributed.forEach((path, bytes) -> {
                if (!source.ownsPath(path) || files.putIfAbsent(path, bytes.clone()) != null) {
                    throw new IllegalStateException("A conversation source wrote an undeclared or duplicate path");
                }
            });
        }
        return files;
    }

    @SuppressWarnings("ReferenceEquality") // Throwable rejects suppression of the same exception instance.
    public PreparedEvidence prepare(ItemSnapshot item, CaptureRequest request) {
        var matching = itemSources.stream()
                .filter(source -> source.supports(item.item()))
                .toList();
        if (matching.size() != 1)
            throw new IllegalStateException("The item must have exactly one installed capture source");
        var contributions = new LinkedHashMap<SourceKind, CaptureContribution>();
        try {
            var kinds = request.sources().stream()
                    .sorted(Comparator.comparing(SourceKind::value))
                    .toList();
            for (var kind : kinds) {
                if (!catalogs.current().version().value().equals(request.sourceContractVersion())
                        || !catalogs.requireSource(kind)
                                .appliesTo(item.item().kind().value()))
                    throw new IllegalArgumentException("The requested source contract does not match the item");
            }
            var permitted = kinds.stream()
                    .filter(kind -> catalogs.isSourceUsePermitted(kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    .collect(Collectors.toUnmodifiableSet());
            for (var kind : kinds) {
                var contribution = permitted.contains(kind)
                        ? matching.getFirst()
                                .capture(
                                        item,
                                        new CaptureRequest(
                                                request.reviewId(),
                                                request.jobId(),
                                                request.attempt(),
                                                Set.of(kind),
                                                request.sourceContractVersion(),
                                                request.scriptContractVersion(),
                                                request.scanPaths(),
                                                permitted,
                                                request.eventId()))
                        : CaptureContribution.unavailable(Set.of(kind), SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE);
                contributions.put(kind, contribution);
            }
            return manifests.build(item.item().kind(), contributions);
        } catch (RuntimeException failure) {
            try {
                CaptureContribution.closeAll(List.copyOf(contributions.values()));
            } catch (Exception cleanupFailure) {
                if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }
}
