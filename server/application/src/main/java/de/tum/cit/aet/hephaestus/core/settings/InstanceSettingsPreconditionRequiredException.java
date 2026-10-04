package de.tum.cit.aet.hephaestus.core.settings;

import java.io.Serial;

final class InstanceSettingsPreconditionRequiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    InstanceSettingsPreconditionRequiredException() {
        super("To release Silent Mode, the If-Match header must contain the current ETag of the instance settings.");
    }
}
