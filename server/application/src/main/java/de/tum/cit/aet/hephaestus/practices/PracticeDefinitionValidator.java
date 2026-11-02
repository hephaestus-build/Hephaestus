package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.ClasspathSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedByKind;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PracticeDefinitionValidator {
    private static final Pattern RESULT_WORD =
            Pattern.compile("\\b(?:MET|NOT_MET|DOES_NOT_APPLY|COULD_NOT_DETERMINE)\\b");
    private final ClasspathSourceCatalogRegistry sources;
    private final PracticeItemOptions items;

    public void validate(ItemKind kind, PracticeDefinition definition) {
        var descriptor = items.require(kind);
        var capabilities = descriptor.reviewCapabilities();
        text(definition.name(), 3, 128, "practice name");
        text(definition.criteria(), 1, 50_000, "criteria");
        if (definition.script() != null)
            text(definition.script(), 1, PracticeDefinition.MAX_PRACTICE_SCRIPT_LENGTH, "practice script");
        if (definition.reviewOn().events().size() > 100) {
            throw new IllegalArgumentException("Choose at most 100 review events.");
        }
        var unoffered = UnofferedItemCapabilities.of(descriptor, definition);
        if ((unoffered != null && !unoffered.events().isEmpty())
                || descriptor.events().stream()
                        .anyMatch(option -> !option.bindableForAuthoring()
                                && definition.reviewOn().events().contains(option.name()))) {
            throw new IllegalArgumentException("Choose review events offered for this item kind.");
        }
        if (unoffered != null && unoffered.about() != null) {
            throw new IllegalArgumentException("Choose an About role offered for this item kind.");
        }
        definition.reviewOn().state().forEach((dimension, values) -> {
            var declared = capabilities.reviewOnStateDimensions().stream()
                    .filter(option -> option.key().equals(dimension))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Choose a known review state" + " dimension."));
            if (values.isEmpty()
                    || values.size() > 100
                    || values.stream()
                            .anyMatch(value -> value.isBlank()
                                    || value.length() > 200
                                    || declared.values().stream()
                                            .noneMatch(option -> option.value().equals(value)))) {
                throw new IllegalArgumentException("Choose values offered for this review state dimension.");
            }
        });
        if (definition.reviewedBy().by() != ReviewedByKind.HEPHAESTUS && definition.script() != null) {
            throw new IllegalArgumentException("A practice script needs Hephaestus as the reviewer.");
        }
        boolean automated = definition.reviewedBy().by() == ReviewedByKind.HEPHAESTUS;
        validateReads(kind, definition.reads(), automated);
        var scan = definition.scan();
        if (scan != null) {
            if (!capabilities.scanItemKinds().contains(scan.itemKind())) {
                throw new IllegalArgumentException("This item kind does not support the selected scan item kind.");
            }
            validateReads(scan.itemKind(), scan.reads(), automated);
        }
        if (definition.appliesWhen() != null) {
            for (var clause : definition.appliesWhen().anyOf()) {
                String aspect = clause.changedPath() != null
                        ? "CHANGED_PATH"
                        : clause.diffContains() != null
                                ? "DIFF_CONTAINS"
                                : clause.hasItems() != null ? "HAS_ITEMS" : "MERGED";
                if (!capabilities.appliesWhenSupportedAspects().contains(aspect)) {
                    throw new IllegalArgumentException("This item kind cannot check the selected condition.");
                }
                if (clause.hasItems() != null
                        && !capabilities.appliesWhenEvidenceCollections().contains(clause.hasItems())) {
                    throw new IllegalArgumentException("Choose an evidence collection offered for this item kind.");
                }
            }
        }
        var guidance = definition.guidance();
        guidance(guidance.whyItMatters());
        guidance(guidance.whatGoodLooksLike());
        guidance(guidance.goingWell());
        if (guidance.visual() != null) {
            PracticeGuidanceRules.validate(guidance.visual());
            guidance(guidance.visual().alt());
        }
        if (guidance.guide() != null) {
            PracticeGuidanceRules.validate(guidance.guide());
            guidance(guidance.guide().markdown());
        }
    }

    private void validateReads(ItemKind kind, PracticeReads reads, boolean automated) {
        if (!reads.contract().equals(sources.current().version().value())) {
            throw new IllegalArgumentException("Choose the current source contract for a new definition.");
        }
        var applicable = sources.current().sourcesFor(kind.value());
        for (var source : reads.sources()) {
            if (!applicable.contains(source.source())) {
                throw new IllegalArgumentException("Choose read sources offered for this item kind.");
            }
            if (source.need() == ReadNeed.COMPLETE
                    && !sources.requireSource(source.source())
                            .completenessPolicy()
                            .supportsComplete()) {
                throw new IllegalArgumentException("This source cannot provide a complete capture.");
            }
        }
        if (automated && reads.sources().stream().noneMatch(source -> source.need() != ReadNeed.OPTIONAL)) {
            throw new IllegalArgumentException("Hephaestus needs at least one required read source.");
        }
    }

    private static void text(String value, int min, int max, String field) {
        if (value.isBlank() || value.length() < min || value.length() > max) {
            throw new IllegalArgumentException("Use " + min + " to " + max + " characters for the " + field + ".");
        }
    }

    private static void guidance(@Nullable String value) {
        if (value != null && RESULT_WORD.matcher(value).find()) {
            throw new IllegalArgumentException("Use plain guidance without review outcome labels.");
        }
    }
}
