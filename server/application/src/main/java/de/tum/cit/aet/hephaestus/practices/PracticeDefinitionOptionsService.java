package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalog;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeDefinitionOptionsDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeEvidenceSourceOptionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeManualReviewSignalDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeSignalOptionDTO;
import de.tum.cit.aet.hephaestus.practices.dto.PracticeWorkTypeDefinitionOptionsDTO;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class PracticeDefinitionOptionsService {

    private final ArtifactSourceCatalogRegistry catalogs;
    private final PracticeEvidenceDefaults defaults;
    private final PracticeSignalOptions signalOptions;

    public PracticeDefinitionOptionsService(
            ArtifactSourceCatalogRegistry catalogs,
            PracticeEvidenceDefaults defaults,
            PracticeSignalOptions signalOptions) {
        this.catalogs = catalogs;
        this.defaults = defaults;
        this.signalOptions = signalOptions;
    }

    public PracticeDefinitionOptionsDTO options() {
        ArtifactSourceCatalog catalog = catalogs.current();
        return new PracticeDefinitionOptionsDTO(
                catalog.version(),
                signalOptions.authorableKinds().stream()
                        .map(artifact -> options(catalog, artifact))
                        .toList());
    }

    private PracticeWorkTypeDefinitionOptionsDTO options(ArtifactSourceCatalog catalog, ArtifactKind artifact) {
        Set<SourceKind> applicable = catalogs.requireSourcesFor(catalog.version(), artifact.value());
        return new PracticeWorkTypeDefinitionOptionsDTO(
                artifact,
                signalOptions.bindableOptionsFor(artifact).stream()
                        .map(option -> new PracticeSignalOptionDTO(
                                option.signal(), option.displayName(), option.recommended()))
                        .toList(),
                signalOptions
                        .manualRequestOptionFor(artifact)
                        .map(option -> new PracticeManualReviewSignalDTO(option.signal(), option.displayName()))
                        .orElse(null),
                defaults.policyFor(artifact),
                defaults.needsFor(artifact),
                List.of(PracticeAutomatedReviewMode.LANGUAGE_MODEL),
                catalog.sources().stream()
                        .filter(source -> applicable.contains(source.kind()))
                        .map(source -> new PracticeEvidenceSourceOptionDTO(
                                source.kind().value(),
                                source.displayName(),
                                source.description(),
                                source.selectionScope(),
                                source.privacyClass(),
                                source.requiredQuality(),
                                source.completenessPolicy().supportsComplete()))
                        .toList(),
                signalOptions.rolesFor(artifact).stream().sorted().toList());
    }
}
