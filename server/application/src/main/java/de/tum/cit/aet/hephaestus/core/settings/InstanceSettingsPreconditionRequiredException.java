package de.tum.cit.aet.hephaestus.core.settings;

import java.io.Serial;

final class InstanceSettingsPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    InstanceSettingsPreconditionRequiredException() {
        super("If-Match must contain the current instance settings ETag when releasing Silent Mode");
    }
}
