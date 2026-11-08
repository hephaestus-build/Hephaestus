package de.tum.cit.aet.hephaestus.practices.dto;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT,
    ElementType.ANNOTATION_TYPE,
    ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
@NotBlank
@Size(max = DefinitionSlugs.MAX_LENGTH)
@Pattern(regexp = DefinitionSlugs.PRACTICE_PATTERN, message = "This identifier is reserved or has an invalid format.")
public @interface ValidPracticeSlug {
    String message() default "Use a valid practice slug.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
