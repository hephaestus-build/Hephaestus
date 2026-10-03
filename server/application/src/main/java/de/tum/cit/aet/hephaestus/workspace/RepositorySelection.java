package de.tum.cit.aet.hephaestus.workspace;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Indicates how repositories are selected for a GitHub App installation.
 */
public enum RepositorySelection {
    /**
     * All repositories in the installation are included.
     */
    ALL,

    /**
     * Only explicitly selected repositories are included.
     */
    SELECTED;

    /**
     * Parses a string value to RepositorySelection, case-insensitively.
     *
     * @param value the string value (e.g., "all", "selected", "ALL", "SELECTED")
     * @return the corresponding enum value, or null if value is null/empty
     * @throws IllegalArgumentException if the value doesn't match any enum constant
     */
    public static @Nullable RepositorySelection fromString(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return RepositorySelection.valueOf(value.toUpperCase(Locale.ROOT));
    }
}
