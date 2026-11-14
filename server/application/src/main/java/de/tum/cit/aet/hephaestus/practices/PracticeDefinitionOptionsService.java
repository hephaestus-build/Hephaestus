package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventCoverage;
import de.tum.cit.aet.hephaestus.practices.dto.ItemEventOptionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeItemTypeOptionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.ReadSourceOptionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.StateDimensionDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ApplicabilityClauseKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PracticeDefinitionOptionsService {
    private final PracticeItemOptions items;
    private final ClasspathSourceCatalogRegistry sources;
    private final ItemEventCoverage coverage;

    public PracticeDefinitionOptionsDTO options(@Nullable Long workspaceId) {
        return new PracticeDefinitionOptionsDTO(
                items.all().stream().map(item -> options(item, workspaceId)).toList(),
                sources.current().version().value());
    }

    private PracticeItemTypeOptionDTO options(ItemDescriptor item, @Nullable Long workspaceId) {
        var capabilities = item.reviewCapabilities();
        var readSources = sources.current().sources().stream()
                .filter(source -> source.appliesTo(item.kind().value()))
                .map(source -> new ReadSourceOptionDTO(
                        source.kind().value(),
                        source.displayName(),
                        source.completenessPolicy().supportsComplete()
                                ? List.of(ReadNeed.REQUIRED, ReadNeed.COMPLETE, ReadNeed.OPTIONAL)
                                : List.of(ReadNeed.REQUIRED, ReadNeed.OPTIONAL),
                        source.citation()))
                .toList();
        return new PracticeItemTypeOptionDTO(
                item.kind().value(),
                item.displayName(),
                item.events().stream()
                        .filter(event -> event.bindableForAuthoring())
                        .map(event -> new ItemEventOptionDTO(
                                event.name().value(),
                                event.displayName(),
                                event.recommendedForAuthoring(),
                                workspaceId != null
                                        && coverage.connectedCoverage(workspaceId)
                                                .contains(event.name()),
                                coverage.raisedBy(event.name()).stream()
                                        .sorted()
                                        .toList()))
                        .toList(),
                item.roles().stream().sorted().toList(),
                readSources,
                capabilities.reviewOnStateDimensions().stream()
                        .map(dimension -> new StateDimensionDTO(
                                dimension.key(),
                                dimension.displayName(),
                                dimension.values().stream()
                                        .map(value -> new StateDimensionDTO.Value(value.value(), value.displayName()))
                                        .toList()))
                        .toList(),
                !capabilities.scanItemKinds().isEmpty(),
                capabilities.scanItemKinds().stream()
                        .map(kind -> kind.value())
                        .sorted()
                        .toList(),
                capabilities.appliesWhenSupportedAspects().stream()
                        .sorted()
                        .map(ApplicabilityClauseKind::valueOf)
                        .toList(),
                readSources.stream()
                        .filter(source ->
                                capabilities.appliesWhenEvidenceCollections().contains(source.source()))
                        .toList(),
                item.copyCapabilities().contains(CopyCapability.SUMMARY)
                        || item.copyCapabilities().contains(CopyCapability.LINE_NOTE));
    }
}
