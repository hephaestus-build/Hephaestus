package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The phase-local narrative half of a progress report — one contract shared by every integration's
 * runner, serialized into {@code sync_job.progress} (JSONB) by
 * {@link SyncExecutionHandle#progress(Integer, Integer, SyncProgress)}.
 *
 * <p>Two halves, deliberately separate:
 * <ul>
 *   <li>{@code itemsProcessed}/{@code itemsTotal} on the handle are <b>job-global</b> and determinate —
 *       they drive the percent bar.</li>
 *   <li>This record is <b>phase-local</b> and narrative — it says which part of a multi-phase sync is
 *       running and what it is doing right now.</li>
 * </ul>
 *
 * <p>{@link #currentStep()} is the render contract: it is the one string the UI shows, so it must stand
 * alone as a sentence. Everything else here is structured detail the UI may use to decorate it (phase
 * chip, per-phase sub-bar) but is not required to.
 *
 * @param phase            which phase of the sync is running
 * @param currentStep      the one human-readable text the UI renders for the step. For a
 *                         backfill it names the repository, the kind of work and the number range reached
 * @param currentRepository the resource being worked on (repository / channel / collection), when the
 *                          phase is per-resource; {@code null} for connection-wide phases
 * @param unitsCompleted   phase-local completed units (e.g. repositories done in this phase)
 * @param unitsTotal       phase-local total units, or {@code null} when genuinely not yet known
 */
public record SyncProgress(
        @NonNull SyncPhase phase,
        @NonNull String currentStep,
        @Nullable String currentRepository,
        @Nullable Integer unitsCompleted,
        @Nullable Integer unitsTotal) {
    /** Detail key carrying {@link SyncPhase#token()}. */
    public static final String KEY_PHASE = "phase";
    /** Detail key carrying the human sentence — the UI's render key. */
    public static final String KEY_CURRENT_STEP = "currentStep";
    /** Detail key carrying the current resource name. */
    public static final String KEY_CURRENT_REPOSITORY = "currentRepository";
    /** Detail key carrying phase-local completed units. */
    public static final String KEY_UNITS_COMPLETED = "unitsCompleted";
    /** Detail key carrying phase-local total units. */
    public static final String KEY_UNITS_TOTAL = "unitsTotal";

    public SyncProgress {
        if (phase == null) {
            throw new IllegalArgumentException("phase is required");
        }
        if (currentStep == null || currentStep.isBlank()) {
            throw new IllegalArgumentException("currentStep is required — it is the string the UI renders");
        }
    }

    /** Phase-wide step with no per-resource focus and no unit math. */
    public static SyncProgress of(SyncPhase phase, String currentStep) {
        return new SyncProgress(phase, currentStep, null, null, null);
    }

    /**
     * Per-resource step: {@code unitsCompleted}/{@code unitsTotal} count resources within the phase.
     */
    public static SyncProgress ofResource(
            SyncPhase phase,
            String currentStep,
            @Nullable String currentRepository,
            @Nullable Integer unitsCompleted,
            @Nullable Integer unitsTotal) {
        return new SyncProgress(phase, currentStep, currentRepository, unitsCompleted, unitsTotal);
    }

    /**
     * Flattens to the JSONB map persisted on the job row. Null members are omitted rather than written
     * as JSON nulls, so a reader can't tell "absent" from "explicitly unknown" — the distinction has no
     * consumer and omitting keeps the column small.
     */
    public Map<String, Object> toDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(KEY_PHASE, phase.token());
        detail.put(KEY_CURRENT_STEP, currentStep);
        if (currentRepository != null) {
            detail.put(KEY_CURRENT_REPOSITORY, currentRepository);
        }
        if (unitsCompleted != null) {
            detail.put(KEY_UNITS_COMPLETED, unitsCompleted);
        }
        if (unitsTotal != null) {
            detail.put(KEY_UNITS_TOTAL, unitsTotal);
        }
        return Map.copyOf(detail);
    }
}
