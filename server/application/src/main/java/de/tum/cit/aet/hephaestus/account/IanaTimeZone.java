package de.tum.cit.aet.hephaestus.account;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** An IANA time zone id that this runtime knows, such as {@code Europe/Berlin}; {@code null} is valid. */
@Documented
@Constraint(validatedBy = IanaTimeZone.Validator.class)
@Target({FIELD, PARAMETER, RECORD_COMPONENT})
@Retention(RUNTIME)
public @interface IanaTimeZone {
    String message() default "The time zone must be an IANA time zone, such as Europe/Berlin.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<IanaTimeZone, String> {

        private static final Set<String> ZONES = Set.copyOf(ZoneId.getAvailableZoneIds());

        /** The zone of a stored id; empty when this runtime does not know it, as after a time zone data update. */
        static Optional<ZoneId> parse(String id) {
            return ZONES.contains(id) ? Optional.of(ZoneId.of(id)) : Optional.empty();
        }

        @Override
        public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
            return value == null || ZONES.contains(value);
        }
    }
}
