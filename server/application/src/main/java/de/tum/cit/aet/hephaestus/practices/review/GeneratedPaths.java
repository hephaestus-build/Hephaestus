package de.tum.cit.aet.hephaestus.practices.review;

import java.util.List;
import org.springframework.util.AntPathMatcher;

/** Repository-root, case-sensitive Ant globs; never filesystem-dependent or provider-dependent. */
public final class GeneratedPaths {
    private static final AntPathMatcher MATCHER = new AntPathMatcher("/");

    private GeneratedPaths() {}

    public static List<String> normalize(List<String> patterns) {
        if (patterns == null || patterns.size() > 100) {
            throw new InvalidReviewCoverageException("Use at most 100 generated-path patterns per repository");
        }
        return patterns.stream()
                .map(pattern -> {
                    if (pattern == null)
                        throw new InvalidReviewCoverageException("A generated-path pattern must not be null");
                    String value = pattern.trim();
                    if (value.isEmpty()
                            || value.length() > 512
                            || value.startsWith("/")
                            || value.startsWith("!")
                            || value.contains("\\")
                            || value.contains("{")
                            || value.contains("}")
                            || java.util.Arrays.asList(value.split("/", -1)).contains("..")) {
                        throw new InvalidReviewCoverageException(
                                "Use a repository-relative generated-path glob of 1 to 512 characters; no negation, parent paths, backslashes or URI variables");
                    }
                    return value;
                })
                .distinct()
                .sorted()
                .toList();
    }

    public static boolean matches(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> MATCHER.match(pattern, path));
    }
}
