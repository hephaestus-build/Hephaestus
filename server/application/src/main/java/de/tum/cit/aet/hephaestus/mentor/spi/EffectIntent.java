package de.tum.cit.aet.hephaestus.mentor.spi;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** An admitted effect, immutable once written. Its key is stable across retries, leases and restores, and its marker is
 * derived from the key, so a restore never mints a new one. */
public record EffectIntent(
        EffectRef ref,
        String key,
        EffectKind kind,
        @Nullable RequestRef request,
        Audience audience,
        Route route,
        ScopeStamp scope,
        @Nullable BasisRef basis,
        Instant deadline,
        String marker) {}
