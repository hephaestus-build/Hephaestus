package de.tum.cit.aet.hephaestus.workspace.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ScmServerUrlValidator.class)
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface ScmServerUrl {
    String message() default "Server URL must use HTTPS and must not point to private/reserved addresses";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
