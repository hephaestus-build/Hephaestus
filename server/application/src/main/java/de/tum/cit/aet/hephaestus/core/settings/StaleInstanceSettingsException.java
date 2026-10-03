package de.tum.cit.aet.hephaestus.core.settings;

import java.io.Serial;
import org.jspecify.annotations.Nullable;

final class StaleInstanceSettingsException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    StaleInstanceSettingsException() {
        this(null);
    }

    StaleInstanceSettingsException(@Nullable Throwable cause) {
        super("The instance settings changed after you loaded them. Reload the settings and try again.", cause);
    }
}
