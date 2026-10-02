package de.tum.cit.aet.hephaestus.core.privacy.spi;

import de.tum.cit.aet.hephaestus.core.security.ScmOrigin;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Exact source registration, including providers that have never authenticated an account. */
public record PersonSourceNamespace(String providerType, String serverUrl) {
    public PersonSourceNamespace {
        if (!java.util.Set.of("GITHUB", "GITLAB", "SLACK", "OUTLINE").contains(providerType)
                || ScmOrigin.of(serverUrl).isEmpty())
            throw new IllegalArgumentException("A source namespace requires its exact provider type and origin");
    }

    public static Optional<PersonSourceNamespace> from(String kind, @Nullable String configuredUrl) {
        String url =
                switch (kind) {
                    case "GITHUB" -> configuredUrl == null ? "https://github.com" : configuredUrl;
                    case "GITLAB" -> configuredUrl == null ? "https://gitlab.com" : configuredUrl;
                    case "SLACK" -> "https://slack.com";
                    case "OUTLINE" -> configuredUrl;
                    default -> throw new IllegalArgumentException("Unsupported source provider type");
                };
        return Optional.ofNullable(url).map(value -> new PersonSourceNamespace(kind, value));
    }
}
