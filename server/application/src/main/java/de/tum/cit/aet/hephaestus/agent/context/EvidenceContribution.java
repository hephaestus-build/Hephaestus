package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public record EvidenceContribution(
        Map<String, byte[]> files,
        Map<SourceKind, SourceCompleteness> completeness,
        Map<SourceKind, String> immutableIdentities,
        Map<SourceKind, Instant> observedAt,
        Map<SourceKind, Instant> sourceEffectiveAt,
        Map<SourceKind, SourceContentState> contentStates,
        /**
         * Capture states only the collector can establish, such as evidence withheld for consent or an
         * artifact absent upstream. The manifest otherwise infers absence from missing files, which
         * cannot distinguish an empty source from one that was not permitted to be read.
         */
        Map<SourceKind, SourceCaptureState> stateOverrides,
        /**
         * Content already materialised on disk, staged by path so its bytes never enter this process; the
         * attempt folder takes a read-only copy and the workspace tar is streamed from there.
         */
        Map<String, Path> filesOnDisk,
        /**
         * Releases whatever backs {@link #filesOnDisk}, or null when nothing needs releasing. It joins the
         * attempt's cleanups, which run after final admission or when the attempt ends without one.
         */
        @Nullable AutoCloseable cleanup,
        /**
         * Per source, what the capture could not include — the same codes the collector would use to say
         * why it reported {@link SourceCompleteness#PARTIAL}. Reported here rather than inferred, because
         * only the collector knows the difference between a tree with nothing more in it and a tree whose
         * walk it stopped.
         */
        Map<SourceKind, List<String>> captureLimitations,
        List<EvidenceDirectory> directories) {
    public EvidenceContribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceContentState> contentStates,
            Map<SourceKind, SourceCaptureState> stateOverrides,
            Map<String, Path> filesOnDisk,
            @Nullable AutoCloseable cleanup,
            Map<SourceKind, List<String>> captureLimitations) {
        this(
                files,
                completeness,
                immutableIdentities,
                observedAt,
                sourceEffectiveAt,
                contentStates,
                stateOverrides,
                filesOnDisk,
                cleanup,
                captureLimitations,
                List.of());
    }

    public EvidenceContribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceContentState> contentStates,
            Map<SourceKind, SourceCaptureState> stateOverrides) {
        this(
                files,
                completeness,
                immutableIdentities,
                observedAt,
                sourceEffectiveAt,
                contentStates,
                stateOverrides,
                Map.of(),
                null,
                Map.of());
    }

    public EvidenceContribution(Map<String, byte[]> files, Map<SourceKind, SourceCompleteness> completeness) {
        this(files, completeness, Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of());
    }

    public EvidenceContribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> immutableIdentities) {
        this(
                files,
                completeness,
                immutableIdentities,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                Map.of());
    }

    public EvidenceContribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt) {
        this(
                files,
                completeness,
                immutableIdentities,
                observedAt,
                sourceEffectiveAt,
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                Map.of());
    }

    public EvidenceContribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceContentState> contentStates) {
        this(
                files,
                completeness,
                immutableIdentities,
                observedAt,
                sourceEffectiveAt,
                contentStates,
                Map.of(),
                Map.of(),
                null,
                Map.of());
    }

    /** An unavailable source has no files and makes no claim about its content or completeness. */
    public static EvidenceContribution unavailable(Set<SourceKind> kinds, SourceAbsenceReason reason) {
        Map<SourceKind, SourceCaptureState> states = new HashMap<>();
        kinds.forEach(kind -> states.put(kind, new SourceCaptureState.Unavailable(reason)));
        return new EvidenceContribution(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), states);
    }

    public EvidenceContribution {
        directories = List.copyOf(directories);
        files = Map.copyOf(Objects.requireNonNull(files, "files"));
        completeness = Map.copyOf(Objects.requireNonNull(completeness, "completeness"));
        immutableIdentities = Map.copyOf(Objects.requireNonNull(immutableIdentities, "immutableIdentities"));
        observedAt = Map.copyOf(Objects.requireNonNull(observedAt, "observedAt"));
        sourceEffectiveAt = Map.copyOf(Objects.requireNonNull(sourceEffectiveAt, "sourceEffectiveAt"));
        stateOverrides = Map.copyOf(Objects.requireNonNull(stateOverrides, "stateOverrides"));
        contentStates = Map.copyOf(Objects.requireNonNull(contentStates, "contentStates"));
        captureLimitations = Objects.requireNonNull(captureLimitations, "captureLimitations").entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }

    /** For a collector that stages files on disk but reports no limitation. */
    public EvidenceContribution(
            Map<String, byte[]> files,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceContentState> contentStates,
            Map<SourceKind, SourceCaptureState> stateOverrides,
            Map<String, Path> filesOnDisk,
            @Nullable AutoCloseable cleanup) {
        this(
                files,
                completeness,
                immutableIdentities,
                observedAt,
                sourceEffectiveAt,
                contentStates,
                stateOverrides,
                filesOnDisk,
                cleanup,
                Map.of());
    }
}
