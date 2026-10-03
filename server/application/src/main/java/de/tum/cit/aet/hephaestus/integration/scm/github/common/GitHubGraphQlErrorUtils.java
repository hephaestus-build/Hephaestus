package de.tum.cit.aet.hephaestus.integration.scm.github.common;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;

/**
 * Utility class for analyzing GitHub GraphQL API errors.
 * <p>
 * GitHub's GraphQL API returns structured errors with an extensions field that
 * contains error classification. This utility helps detect specific error types
 * like NOT_FOUND to enable appropriate handling (e.g., soft-deleting stale data
 * rather than logging errors for expected scenarios).
 */
public final class GitHubGraphQlErrorUtils {

    private GitHubGraphQlErrorUtils() {
        // Utility class - prevent instantiation
    }

    /**
     * GitHub GraphQL error type indicating a resource was not found.
     * <p>
     * This occurs when querying for issues, pull requests, or other resources
     * that have been deleted from GitHub but may still exist in our database.
     */
    public static final String ERROR_TYPE_NOT_FOUND = "NOT_FOUND";

    /**
     * Checks if the GraphQL response contains a NOT_FOUND error for the specified path.
     * <p>
     * GitHub returns NOT_FOUND errors when querying for resources that don't exist,
     * such as deleted issues or pull requests. This is distinct from null data,
     * which indicates no results but not an error condition.
     *
     * @param response the GraphQL response to check
     * @param pathPrefix the path prefix to check for (e.g., "repository.issue" or "repository.pullRequest")
     * @return true if the response contains a NOT_FOUND error at the specified path
     */
    public static boolean isNotFoundError(@Nullable ClientGraphQlResponse response, String pathPrefix) {
        return hasErrorType(response, ERROR_TYPE_NOT_FOUND, pathPrefix);
    }

    /**
     * Checks if the GraphQL response contains any NOT_FOUND error.
     *
     * @param response the GraphQL response to check
     * @return true if the response contains any NOT_FOUND error
     */
    public static boolean hasAnyNotFoundError(@Nullable ClientGraphQlResponse response) {
        return hasErrorType(response, ERROR_TYPE_NOT_FOUND, null);
    }

    /**
     * Checks if the GraphQL response contains an error of the specified type.
     *
     * @param response the GraphQL response to check
     * @param errorType the error type to look for (e.g., "NOT_FOUND", "FORBIDDEN")
     * @param pathPrefix optional path prefix to filter errors (null to check all errors)
     * @return true if a matching error is found
     */
    public static boolean hasErrorType(
            @Nullable ClientGraphQlResponse response, String errorType, @Nullable String pathPrefix) {
        if (response == null) {
            return false;
        }

        List<ResponseError> errors = response.getErrors();
        if (errors == null || errors.isEmpty()) {
            return false;
        }

        for (ResponseError error : errors) {
            if (matchesErrorType(error, errorType) && matchesPath(error, pathPrefix)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the error has the specified type in its extensions.
     */
    private static boolean matchesErrorType(ResponseError error, String errorType) {
        Map<String, Object> extensions = error.getExtensions();
        if (extensions == null) {
            return false;
        }

        Object type = extensions.get("type");
        return errorType.equals(type);
    }

    /**
     * Checks if the error path matches or is a child of the specified prefix.
     * <p>
     * The path is a list of path segments (strings and integers for array indices).
     * For example, ["repository", "issue"] would match pathPrefix "repository.issue",
     * and ["repository", "issue", "comments"] would also match "repository.issue".
     * However, ["repository"] would NOT match "repository.issue".
     *
     * @param error      the error to check
     * @param pathPrefix the path prefix to match against (must not be null)
     * @return true if the error path starts with the given prefix
     */
    private static boolean matchesPath(ResponseError error, @Nullable String pathPrefix) {
        if (pathPrefix == null) {
            return true;
        }

        // Use getParsedPath() which returns List<Object> with field names (Strings) and array indices (Integers)
        List<Object> path = error.getParsedPath();
        if (path == null || path.isEmpty()) {
            // Fallback: check the string path representation
            String stringPath = error.getPath();
            if (stringPath == null || stringPath.isEmpty()) {
                return false;
            }
            // Only match if the error path is at or under the expected location
            return stringPath.startsWith(pathPrefix);
        }

        // Build the path string from the path segments (excluding array indices)
        StringBuilder pathBuilder = new StringBuilder();
        for (Object segment : path) {
            if (segment instanceof String stringSegment) {
                if (pathBuilder.length() > 0) {
                    pathBuilder.append(".");
                }
                pathBuilder.append(stringSegment);
            }
            // Skip integer indices (array positions)
        }

        String actualPath = pathBuilder.toString();
        // Only match if the error path is at or under the expected location
        return actualPath.startsWith(pathPrefix);
    }

    /**
     * Extracts a human-readable error message for NOT_FOUND errors.
     * <p>
     * Useful for logging at an appropriate level (WARN instead of ERROR).
     *
     * @param response the GraphQL response
     * @return a descriptive message about the NOT_FOUND error, or null if no such error
     */
    @Nullable
    public static String getNotFoundErrorMessage(@Nullable ClientGraphQlResponse response) {
        if (response == null) {
            return null;
        }

        List<ResponseError> errors = response.getErrors();
        if (errors == null) {
            return null;
        }

        for (ResponseError error : errors) {
            if (matchesErrorType(error, ERROR_TYPE_NOT_FOUND)) {
                return error.getMessage();
            }
        }

        return null;
    }

    // Transient Error Detection for Retry Logic

    /**
     * Checks if the GraphQL response contains a transient error that should be retried.
     * <p>
     * Transient errors include:
     * <ul>
     *   <li>GitHub timeout responses ("couldn't respond in time", "Something went wrong")</li>
     *   <li>Rate limit errors in the GraphQL response</li>
     *   <li>Server-side errors (INTERNAL_ERROR)</li>
     * </ul>
     *
     * @param response the GraphQL response to check
     * @return the transient error info if found, null otherwise
     */
    @Nullable
    public static TransientError detectTransientError(@Nullable ClientGraphQlResponse response) {
        if (response == null) {
            return null;
        }

        List<ResponseError> errors = response.getErrors();
        if (errors == null || errors.isEmpty()) {
            return null;
        }

        for (ResponseError error : errors) {
            String message = error.getMessage();
            if (message == null) {
                continue;
            }

            String lowerMessage = message.toLowerCase(Locale.ROOT);

            // GitHub timeout responses - these come back as HTTP 200 with error in body
            // Examples: "couldn't respond in time", "Something went wrong while executing your query"
            if (lowerMessage.contains("couldn't respond in time")
                    || lowerMessage.contains("could not respond in time")
                    || lowerMessage.contains("something went wrong while executing")
                    || lowerMessage.contains("timedout")
                    || lowerMessage.contains("timed out")) {
                return new TransientError(TransientErrorType.TIMEOUT, message);
            }

            // Rate limit errors in GraphQL
            if (lowerMessage.contains("rate limit")
                    || lowerMessage.contains("ratelimit")
                    || lowerMessage.contains("abuse detection")
                    || lowerMessage.contains("secondary rate")) {
                return new TransientError(TransientErrorType.RATE_LIMIT, message);
            }

            // Check extensions for error type
            Map<String, Object> extensions = error.getExtensions();
            if (extensions != null) {
                Object errorType = extensions.get("type");
                if (errorType != null) {
                    String type = errorType.toString();
                    // Resource limits exceeded - new September 2025 error type
                    // GitHub caps per-query execution resources to prevent excessive load
                    if ("RESOURCE_LIMITS_EXCEEDED".equals(type) || "MAX_NODE_LIMIT_EXCEEDED".equals(type)) {
                        return new TransientError(TransientErrorType.RESOURCE_LIMIT, message);
                    }
                    if ("INTERNAL_ERROR".equals(type) || "SERVICE_UNAVAILABLE".equals(type)) {
                        return new TransientError(TransientErrorType.SERVER_ERROR, message);
                    }
                    if ("FORBIDDEN".equals(type) && lowerMessage.contains("rate")) {
                        return new TransientError(TransientErrorType.RATE_LIMIT, message);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Types of transient errors that can occur in GraphQL responses.
     */
    public enum TransientErrorType {
        /** GitHub couldn't respond in time - retry after short delay */
        TIMEOUT,
        /** Rate limit hit - need longer backoff (1 minute minimum for secondary) */
        RATE_LIMIT,
        /** Server-side error - retry with exponential backoff */
        SERVER_ERROR,
        /** Resource limits exceeded (Sept 2025) - reduce query complexity */
        RESOURCE_LIMIT,
    }

    /**
     * Information about a transient error detected in a GraphQL response.
     *
     * @param type the type of transient error
     * @param message the error message from GitHub
     */
    public record TransientError(TransientErrorType type, String message) {
        /**
         * Returns the recommended wait duration before retrying.
         *
         * @return recommended wait duration
         */
        public Duration getRecommendedWait() {
            return switch (type) {
                case TIMEOUT -> Duration.ofSeconds(5);
                case RATE_LIMIT -> Duration.ofMinutes(1); // Secondary rate limits need 1 min minimum
                case SERVER_ERROR -> Duration.ofSeconds(10);
                case RESOURCE_LIMIT -> Duration.ofSeconds(0); // No wait - must reduce query complexity
            };
        }

        /**
         * Returns true if reducing query complexity might help.
         * For RESOURCE_LIMIT errors, the query should be retried with smaller page size.
         */
        public boolean shouldReduceComplexity() {
            return type == TransientErrorType.RESOURCE_LIMIT;
        }

        /**
         * Returns true if this error should be retried.
         */
        public boolean isRetryable() {
            return true; // All transient errors are retryable
        }
    }
}
