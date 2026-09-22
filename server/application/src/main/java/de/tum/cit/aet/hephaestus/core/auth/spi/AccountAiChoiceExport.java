package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** The account's own AI choice only; no workspace configuration or another member's data. */
public interface AccountAiChoiceExport {
    @Nullable
    Choice choice(long accountId);

    record Choice(String aiChoice, Instant updatedAt) {}
}
