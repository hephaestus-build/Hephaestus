package de.tum.cit.aet.hephaestus.core.runtime;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Artifact profiles disable runtime responsibilities and must never overlay production. */
@Component
@Profile("prod & (specs | cds-training)")
public final class BuildProfileGuard {

    public BuildProfileGuard() {
        throw new IllegalStateException(
                "The specs and cds-training profiles are build-only and cannot be combined with production.");
    }
}
