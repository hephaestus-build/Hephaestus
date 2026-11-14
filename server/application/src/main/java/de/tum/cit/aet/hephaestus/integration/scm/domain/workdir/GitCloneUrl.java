package de.tum.cit.aet.hephaestus.integration.scm.domain.workdir;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.web.util.UriComponentsBuilder;

/** The one builder of a provider clone URL. Every fetch from a provider takes its URL from here. */
public final class GitCloneUrl {

    private GitCloneUrl() {}

    /**
     * The clone URL of {@code repositoryPath} on {@code serverUrl}. Empty for a path that is not a provider path: fewer
     * than two segments, or an empty, {@code .} or {@code ..} segment, which could leave the repository's namespace.
     */
    public static Optional<String> of(String serverUrl, String repositoryPath) {
        String[] segments = repositoryPath.split("/", -1);
        if (segments.length < 2
                || Arrays.stream(segments)
                        .anyMatch(segment -> segment.isBlank() || segment.equals(".") || segment.equals("..")))
            return Optional.empty();
        segments[segments.length - 1] += ".git";
        return Optional.of(UriComponentsBuilder.fromUriString(serverUrl)
                .pathSegment(segments)
                .build()
                .encode()
                .toUriString());
    }

    /** {@link #of}, for a repository whose path the provider reported; refuses a path that is not a provider path. */
    public static String require(String serverUrl, String repositoryPath) {
        return of(serverUrl, repositoryPath)
                .orElseThrow(() -> new IllegalArgumentException("The repository path is not a provider path"));
    }
}
