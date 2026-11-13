package de.tum.cit.aet.hephaestus.core;

import org.jspecify.annotations.Nullable;

/**
 * Utility class for safe logging operations.
 * Provides methods to sanitize user-controlled input before logging to prevent log injection attacks.
 */
public final class LoggingUtils {

    private LoggingUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * Sanitizes a string for safe logging by removing control characters that could be used
     * for log injection attacks (newlines, carriage returns, tabs, and other control characters).
     *
     * <p>This method should be used whenever logging user-controlled input such as:
     * <ul>
     *   <li>Repository names, slugs, or identifiers</li>
     *   <li>User-provided strings (labels, descriptions, etc.)</li>
     *   <li>GitHub account logins</li>
     *   <li>Exception messages from external sources</li>
     * </ul>
     *
     * @param input the string to sanitize (may be null)
     * @return the sanitized string with control characters replaced by underscores,
     *         or null if input was null
     */
    public static @Nullable String sanitizeForLog(@Nullable String input) {
        if (input == null) {
            return null;
        }
        return input
                // Strip ANSI escape sequences (ESC + '[' + params + final byte)
                .replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "_")
                // Then strip remaining control characters
                .replaceAll("[\\p{Cc}\\u061C\\u200E\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]", "_");
    }

    /** Truncate {@code value} to {@code limit} chars with an ellipsis when longer. */
    public static String truncate(String value, int limit) {
        if (value == null) return "<null>";
        return value.length() > limit ? value.substring(0, limit) + "..." : value;
    }
}
