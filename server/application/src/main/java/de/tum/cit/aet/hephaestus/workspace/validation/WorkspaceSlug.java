package de.tum.cit.aet.hephaestus.workspace.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Ensures workspace slugs follow the same constraints as creation (lowercase alphanumerics + hyphen).
 */
@Documented
@Constraint(validatedBy = WorkspaceSlugValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface WorkspaceSlug {
    String message() default "Choose a non-reserved lowercase DNS label with at most 63 characters.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
