package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PracticeScriptRunDTO(
        UUID jobId,
        int attempt,
        @Schema(type = "string", pattern = "^[1-9][0-9]*$") String practiceId,
        @Schema(type = "string", pattern = "^[1-9][0-9]*$") String practiceVersionId,
        ScriptRunStatus status,
        ScriptKind scriptKind,
        @Nullable ScriptStopReason stopReason,

        @Schema(
                description = "Why a run that stopped as OFF_FORMAT has no result, when the runner's counts or the"
                        + " built-in's contract prove it. Absent otherwise; then the stop reason stands alone.")
        @Nullable
        ScriptNoResultCause noResultCause,

        Instant finishedAt,
        @Nullable Long durationMs,
        @Nullable String error,

        @Schema(
                description = "Model calls of the practice's review sessions, as the runner counted them. Not the calls"
                        + " the proxy billed: those are counted for the whole review. Absent when the runner did not"
                        + " record them.")
        @Nullable
        Integer reviewModelCalls) {}
