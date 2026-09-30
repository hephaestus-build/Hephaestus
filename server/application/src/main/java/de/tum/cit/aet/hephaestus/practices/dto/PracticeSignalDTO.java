package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * A signal and the words a reader sees for it. The name is for matching and linking; a surface prints the
 * display name, which the artifact kind's descriptor declares.
 */
@Schema(description = "A signal and the words a reader sees for it")
public record PracticeSignalDTO(
        @NonNull
        @Schema(
                description = "Signal name, for matching and linking; never printed",
                example = "scm.pull_request.ready")
        SignalName signal,

        @NonNull @Schema(description = "What to print for the signal")
        String displayName) {}
