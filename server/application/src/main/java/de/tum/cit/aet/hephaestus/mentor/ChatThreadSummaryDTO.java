package de.tum.cit.aet.hephaestus.mentor;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Lightweight thread row for the list endpoint — no messages, no session_jsonl BYTEA. */
@Schema(description = "Mentor chat thread summary (no messages).")
public record ChatThreadSummaryDTO(
        @NonNull UUID id, @Nullable String title, @NonNull Instant createdAt) {}
