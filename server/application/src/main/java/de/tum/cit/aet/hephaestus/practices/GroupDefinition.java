package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.exception.InvalidInputException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record GroupDefinition(
        String name,
        @Nullable String description,
        @Nullable String icon,
        @Nullable String color) implements ClosedPracticeInput {
    public static final int NAME_MAX_LENGTH = 128;
    public static final int DESCRIPTION_MAX_LENGTH = 2000;
    public static final int ICON_MAX_LENGTH = 64;
    public static final int COLOR_MAX_LENGTH = 32;

    public GroupDefinition {
        Objects.requireNonNull(name);
        if (name.isBlank()
                || name.length() > NAME_MAX_LENGTH
                || (description != null && description.length() > DESCRIPTION_MAX_LENGTH)
                || (icon != null && icon.length() > ICON_MAX_LENGTH)
                || (color != null && color.length() > COLOR_MAX_LENGTH)) {
            throw new InvalidInputException("Use a group name and metadata within the supported lengths.");
        }
    }
}
