package de.tum.cit.aet.hephaestus.practices;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/** Three-way comparison and explicit field choices for both catalogue hops. */
public final class PracticeDefinitionMerge {

    private PracticeDefinitionMerge() {}

    public record FieldChange(PracticeDefinitionField field, boolean offeredChanged, boolean conflict) {}

    public static List<FieldChange> changes(
            PracticeDefinition base, PracticeDefinition current, PracticeDefinition offered) {
        return Arrays.stream(PracticeDefinitionField.values())
                .filter(field -> !Objects.equals(value(field, base), value(field, offered))
                        || !Objects.equals(value(field, base), value(field, current)))
                .map(field -> {
                    boolean offeredChanged = !Objects.equals(value(field, base), value(field, offered));
                    boolean currentChanged = !Objects.equals(value(field, base), value(field, current));
                    return new FieldChange(field, offeredChanged, offeredChanged && currentChanged);
                })
                .toList();
    }

    public static PracticeDefinition apply(
            PracticeDefinition base,
            PracticeDefinition current,
            PracticeDefinition offered,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {
        var changed = changes(base, current, offered).stream()
                .filter(FieldChange::offeredChanged)
                .map(FieldChange::field)
                .collect(Collectors.toSet());
        if (!changed.equals(choices.keySet()) || choices.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Choose current or offered for every changed catalogue field");
        }
        return new PracticeDefinition(
                source(PracticeDefinitionField.NAME, current, offered, choices).name(),
                source(PracticeDefinitionField.BINDINGS, current, offered, choices)
                        .bindings(),
                source(PracticeDefinitionField.CRITERIA, current, offered, choices)
                        .criteria(),
                source(PracticeDefinitionField.PRECOMPUTE_SCRIPT, current, offered, choices)
                        .precomputeScript(),
                source(PracticeDefinitionField.AUTOMATED_REVIEW_POLICY, current, offered, choices)
                        .automatedReviewPolicy(),
                source(PracticeDefinitionField.WHY_IT_MATTERS, current, offered, choices)
                        .whyItMatters(),
                source(PracticeDefinitionField.WHAT_GOOD_LOOKS_LIKE, current, offered, choices)
                        .whatGoodLooksLike(),
                source(PracticeDefinitionField.GROUP_SLUG, current, offered, choices)
                        .groupSlug(),
                source(PracticeDefinitionField.DELIVERY_BEHAVIOR, current, offered, choices)
                        .deliveryBehavior());
    }

    private static PracticeDefinition source(
            PracticeDefinitionField field,
            PracticeDefinition current,
            PracticeDefinition offered,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {
        return choices.get(field) == PracticeReleaseChoice.OFFERED ? offered : current;
    }

    private static @Nullable Object value(PracticeDefinitionField field, PracticeDefinition definition) {
        return switch (field) {
            case NAME -> definition.name();
            case BINDINGS -> definition.bindings();
            case CRITERIA -> definition.criteria();
            case PRECOMPUTE_SCRIPT -> definition.precomputeScript();
            case AUTOMATED_REVIEW_POLICY -> definition.automatedReviewPolicy();
            case WHY_IT_MATTERS -> definition.whyItMatters();
            case WHAT_GOOD_LOOKS_LIKE -> definition.whatGoodLooksLike();
            case GROUP_SLUG -> definition.groupSlug();
            case DELIVERY_BEHAVIOR -> definition.deliveryBehavior();
        };
    }
}
