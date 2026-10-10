package de.tum.cit.aet.hephaestus.practices.spi;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

@Schema(description = "The review whose precompute run reported a practice's needs")
public record PrecomputeAsOfDTO(
        @NonNull UUID jobId, @NonNull Instant finishedAt) {}
