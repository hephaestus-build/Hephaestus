package de.tum.cit.aet.hephaestus.activity.overview;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import de.tum.cit.aet.hephaestus.activity.overview.dto.ActivityPersonRow;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.Comparator;
import java.util.List;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.util.StdConverter;

/** Write contributor rows or identifiers in identity order at the HTTP boundary. */
@JacksonAnnotationsInside
@JsonSerialize(converter = ActivityNameOrder.ByName.class)
@Retention(RUNTIME)
@Target({FIELD, METHOD, RECORD_COMPONENT})
public @interface ActivityNameOrder {
    final class ByName extends StdConverter<List<?>, List<?>> {
        @Override
        public List<?> convert(List<?> rows) {
            if (rows.isEmpty() || rows.getFirst() instanceof ActivityPersonRow) {
                return rows.stream()
                        .map(ActivityPersonRow.class::cast)
                        .sorted(Comparator.comparing(ActivityPersonRow::activityName, ActivityScopeResolver.NAMES)
                                .thenComparing(ActivityPersonRow::activityLogin)
                                .thenComparing(ActivityPersonRow::activityIdentity))
                        .toList();
            }
            // The sequence of contributor identifiers can disclose a rank as well.
            return rows.stream().sorted(Comparator.comparing(Object::toString)).toList();
        }
    }
}
