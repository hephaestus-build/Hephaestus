package de.tum.cit.aet.hephaestus.workspace;

import org.jspecify.annotations.Nullable;

/** An administrator's changes to public activity. A null field leaves the saved choice unchanged. */
public record PublicActivityChoice(
        @Nullable Boolean enabled,
        @Nullable Boolean allowSearchEngines,
        @Nullable Boolean contributorsWithoutAccount) {}
