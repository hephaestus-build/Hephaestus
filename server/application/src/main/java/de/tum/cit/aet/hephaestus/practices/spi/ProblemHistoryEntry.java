package de.tum.cit.aet.hephaestus.practices.spi;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

@RawObservationAccess
public record ProblemHistoryEntry(
        UUID problemId,
        Severity severity,
        String summary,
        @Nullable String path,
        @Nullable Integer startLine) {}
