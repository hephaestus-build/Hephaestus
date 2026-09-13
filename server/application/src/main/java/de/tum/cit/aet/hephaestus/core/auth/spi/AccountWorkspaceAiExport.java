package de.tum.cit.aet.hephaestus.core.auth.spi;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Account-owned choices only; no workspace configuration or another member's data. */
public interface AccountWorkspaceAiExport {
    List<Preference> preferences(long accountId);

    record Preference(
            String workspaceSlug,
            @Nullable String aiChoice,
            Instant updatedAt,
            @Nullable Instant welcomedAt,
            @Nullable Instant completedAt) {}
}
