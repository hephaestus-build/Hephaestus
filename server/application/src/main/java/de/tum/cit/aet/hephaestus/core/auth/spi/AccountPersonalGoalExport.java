package de.tum.cit.aet.hephaestus.core.auth.spi;

import org.jspecify.annotations.Nullable;

/** Reads the owner's goal while the export holds its source fence. */
public interface AccountPersonalGoalExport {
    @Nullable String words(long accountId);
}
