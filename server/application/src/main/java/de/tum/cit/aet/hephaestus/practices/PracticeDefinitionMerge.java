package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class PracticeDefinitionMerge {
    private PracticeDefinitionMerge() {}

    public record FieldChange(
            PracticeDefinitionField field, boolean changedLocally, boolean offeredChanged, boolean conflict) {}

    public static List<FieldChange> changes(
            @Nullable PracticeDefinition base, PracticeDefinition current, PracticeDefinition offered) {
        return Arrays.stream(PracticeDefinitionField.values())
                .map(field -> {
                    Object original = base == null ? null : value(field, base);
                    boolean local = !Objects.equals(original, value(field, current));
                    boolean changed = !Objects.equals(original, value(field, offered));
                    boolean conflict =
                            local && changed && !Objects.equals(value(field, current), value(field, offered));
                    return new FieldChange(field, local, changed, conflict);
                })
                .filter(change -> change.changedLocally() || change.offeredChanged())
                .toList();
    }

    public static PracticeDefinition apply(
            @Nullable PracticeDefinition base,
            PracticeDefinition current,
            PracticeDefinition offered,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {
        var required = changes(base, current, offered).stream()
                .filter(FieldChange::offeredChanged)
                .map(FieldChange::field)
                .collect(Collectors.toSet());
        if (!required.equals(choices.keySet()) || choices.values().stream().anyMatch(Objects::isNull)) {
            throw new InvalidInputException("Choose the current or offered value for every changed catalog field.");
        }
        return new PracticeDefinition(
                source(PracticeDefinitionField.NAME, current, offered, choices).name(),
                source(PracticeDefinitionField.REVIEW_ON, current, offered, choices)
                        .reviewOn(),
                source(PracticeDefinitionField.APPLIES_WHEN, current, offered, choices)
                        .appliesWhen(),
                source(PracticeDefinitionField.ABOUT, current, offered, choices).about(),
                source(PracticeDefinitionField.READS, current, offered, choices).reads(),
                source(PracticeDefinitionField.REVIEWED_BY, current, offered, choices)
                        .reviewedBy(),
                source(PracticeDefinitionField.CRITERIA, current, offered, choices)
                        .criteria(),
                source(PracticeDefinitionField.SCRIPT, current, offered, choices)
                        .script(),
                source(PracticeDefinitionField.SCAN, current, offered, choices).scan(),
                source(PracticeDefinitionField.LINE_NOTES, current, offered, choices)
                        .lineNotes(),
                source(PracticeDefinitionField.GUIDANCE, current, offered, choices)
                        .guidance());
    }

    private static PracticeDefinition source(
            PracticeDefinitionField field,
            PracticeDefinition current,
            PracticeDefinition offered,
            Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {
        return choices.get(field) == PracticeReleaseChoice.OFFERED ? offered : current;
    }

    public static @Nullable Object value(PracticeDefinitionField field, PracticeDefinition definition) {
        return switch (field) {
            case NAME -> definition.name();
            case REVIEW_ON -> definition.reviewOn();
            case APPLIES_WHEN -> definition.appliesWhen();
            case ABOUT -> definition.about();
            case READS -> definition.reads();
            case REVIEWED_BY -> definition.reviewedBy();
            case CRITERIA -> definition.criteria();
            case SCRIPT -> definition.script();
            case SCAN -> definition.scan();
            case LINE_NOTES -> definition.lineNotes();
            case GUIDANCE -> definition.guidance();
        };
    }
}
