package de.tum.cit.aet.hephaestus.workspace.validation;

import de.tum.cit.aet.hephaestus.core.security.ScmServerEndpointPolicy;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

@RequiredArgsConstructor
public class ScmServerUrlValidator implements ConstraintValidator<ScmServerUrl, String> {
    private final ScmServerEndpointPolicy endpoints;

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        try {
            endpoints.validate(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
