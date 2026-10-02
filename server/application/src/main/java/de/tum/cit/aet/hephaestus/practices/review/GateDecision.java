package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Result of the practice review detection gate evaluation.
 * <p>
 * Uses a sealed interface so callers must handle both outcomes, and each
 * variant carries only the data relevant to that outcome:
 * <ul>
 *   <li>{@link Detect}: the gate passed — carries the resolved workspace
 *       and matched practices so downstream consumers don't need to re-query</li>
 *   <li>{@link Skip}: the gate rejected — carries a human-readable reason
 *       for diagnostics and logging</li>
 * </ul>
 */
public sealed interface GateDecision permits GateDecision.Detect, GateDecision.Skip {
    /**
     * The gate passed: the practice review agent should run.
     *
     * @param workspace         the resolved workspace for this PR's repository
     * @param matchedPractices practices bound to the observed signal and above {@code OFF}, and those rechecked
     * @param recheckedPractices slugs of the matched practices the signal does not occasion, admitted because
     *     their current word on this work is a problem the work may since have answered
     */
    record Detect(
            Workspace workspace,
            List<Practice> matchedPractices,
            long rolloutRevision,
            TriggerMode triggerMode,
            Set<String> recheckedPractices,
            Map<String, String> reviewState)
            implements GateDecision {
        public Detect {
            Objects.requireNonNull(workspace, "workspace must not be null");
            matchedPractices = List.copyOf(matchedPractices);
            recheckedPractices = Set.copyOf(recheckedPractices);
            reviewState = Map.copyOf(reviewState);
        }

        public Detect(
                Workspace workspace,
                List<Practice> matchedPractices,
                long rolloutRevision,
                TriggerMode triggerMode,
                Set<String> recheckedPractices) {
            this(workspace, matchedPractices, rolloutRevision, triggerMode, recheckedPractices, Map.of());
        }

        public Detect(
                Workspace workspace, List<Practice> matchedPractices, long rolloutRevision, TriggerMode triggerMode) {
            this(workspace, matchedPractices, rolloutRevision, triggerMode, Set.of(), Map.of());
        }
    }

    /**
     * The gate rejected: the practice review agent should NOT run.
     *
     * @param reason a short, human-readable reason for the skip (for logging/diagnostics)
     * @param signalReason the controlled-vocabulary reason to record the refused signal under, or
     *     {@code null} for the default {@link SignalStateReason#GATE_SKIPPED}. Name a reason only when
     *     the <em>class</em> of answer differs — a practice silenced to {@code OFF} is a different fact
     *     from "the gate declined", and only one of them is lifted by an admin.
     */
    record Skip(String reason, @Nullable SignalStateReason signalReason) implements GateDecision {
        /** A skip that carries only diagnostic prose; the signal is recorded as {@code GATE_SKIPPED}. */
        public Skip(String reason) {
            this(reason, null);
        }

        /** The reason to record this refusal under, resolving the unnamed case to {@code GATE_SKIPPED}. */
        public SignalStateReason resolvedSignalReason() {
            return signalReason != null ? signalReason : SignalStateReason.GATE_SKIPPED;
        }
    }
}
