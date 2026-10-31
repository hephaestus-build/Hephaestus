package de.tum.cit.aet.hephaestus.integration.core.webhook;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Explicit allow-list path → {@link IntegrationKind} lookup.
 *
 * <p>Never calls {@link IntegrationKind#valueOf(String)} on user-controlled input —
 * that would reflect on attacker input. The hardcoded map is the only routing source.
 *
 * <p>GitLab and Outline are absent: each delivers only to its connection's own route, which verifies a credential bound
 * to that connection.
 */
@Component
public class IntegrationKindRouting {

    private static final Map<String, IntegrationKind> ROUTES =
            Map.of("github", IntegrationKind.GITHUB, "slack", IntegrationKind.SLACK);

    /** Whether the shared {@code /webhooks/{kind}} endpoint serves {@code kind}. */
    public boolean routes(IntegrationKind kind) {
        return ROUTES.containsValue(kind);
    }

    public Optional<IntegrationKind> resolve(@Nullable String pathSegment) {
        if (pathSegment == null) return Optional.empty();
        return Optional.ofNullable(ROUTES.get(pathSegment.toLowerCase(Locale.ROOT)));
    }
}
