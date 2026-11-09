package de.tum.cit.aet.hephaestus.evidence.capture;

import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * @param stateOverrides capture states only the collector can establish, such as evidence withheld for consent
 *     or an item absent upstream. The manifest otherwise infers absence from missing files, which cannot
 *     distinguish an empty source from one that was not permitted to be read.
 * @param filesOnDisk content staged by path instead of retained in byte arrays. The attempt folder receives a
 *     read-only copy, which is streamed into the workspace archive.
 * @param cleanup releases whatever backs {@code filesOnDisk}, or null when nothing needs releasing. It joins the
 *     attempt's cleanups, which run after final admission or when the attempt ends without one.
 * @param captureLimitations per source, what the capture could not include — the same codes the collector would
 *     use to say why it reported {@link SourceCompleteness#PARTIAL}. Reported here rather than inferred, because
 *     only the collector knows the difference between a tree with nothing more in it and a tree whose walk it
 *     stopped.
 */
public record CaptureContribution(
        Map<String, byte[]> files,
        Map<SourceKind, SourceCompleteness> completeness,
        Map<SourceKind, String> immutableIdentities,
        Map<SourceKind, Instant> observedAt,
        Map<SourceKind, Instant> sourceEffectiveAt,
        Map<SourceKind, SourceContentState> contentStates,
        Map<SourceKind, SourceCaptureState> stateOverrides,
        Map<String, Path> filesOnDisk,
        @Nullable AutoCloseable cleanup,
        Map<SourceKind, List<String>> captureLimitations,
        List<EvidenceDirectory> directories,
        List<WorkspaceRefusal> refusals,
        List<CitableText> citableTexts) {
    /** The same source state for a manifest and a current-source comparison. */
    public SourceCaptureState stateFor(SourceKind kind, Instant capturedAt) {
        var override = stateOverrides.get(kind);
        if (override != null) return override;
        return new SourceCaptureState.Available(
                contentStates.getOrDefault(
                        kind,
                        files.isEmpty() && filesOnDisk.isEmpty() && citableTexts.isEmpty()
                                ? SourceContentState.EMPTY
                                : SourceContentState.NON_EMPTY),
                completeness.getOrDefault(kind, SourceCompleteness.UNKNOWN),
                new SourceCaptureFacts(
                        capturedAt, sourceEffectiveAt.get(kind), observedAt.get(kind), immutableIdentities.get(kind)),
                captureLimitations.getOrDefault(kind, List.of()));
    }

    public static CaptureContribution empty() {
        return new CaptureContribution(
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, Map.of(),
                List.of(), List.of(), List.of());
    }

    /** An unavailable source has no files and makes no claim about its content or completeness. */
    public static CaptureContribution unavailable(Set<SourceKind> kinds, SourceAbsenceReason reason) {
        Map<SourceKind, SourceCaptureState> states = new HashMap<>();
        kinds.forEach(kind -> states.put(kind, new SourceCaptureState.Unavailable(reason)));
        return empty().withStateOverrides(states);
    }

    public CaptureContribution withStateOverrides(Map<SourceKind, SourceCaptureState> states) {
        return new CaptureContribution(
                files,
                completeness,
                immutableIdentities,
                observedAt,
                sourceEffectiveAt,
                contentStates,
                states,
                filesOnDisk,
                cleanup,
                captureLimitations,
                directories,
                refusals,
                citableTexts);
    }

    @SuppressWarnings("ReferenceEquality") // Throwable rejects suppression of the same exception instance.
    public static void closeAll(List<CaptureContribution> contributions) throws Exception {
        Exception failure = null;
        for (var contribution : contributions) {
            var cleanup = contribution.cleanup();
            if (cleanup == null) continue;
            try {
                cleanup.close();
            } catch (Exception error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    public CaptureContribution {
        citableTexts = List.copyOf(citableTexts);
        filesOnDisk = Map.copyOf(filesOnDisk);
        refusals = List.copyOf(refusals);
        directories = List.copyOf(directories);
        files = Map.copyOf(files);
        completeness = Map.copyOf(completeness);
        immutableIdentities = Map.copyOf(immutableIdentities);
        observedAt = Map.copyOf(observedAt);
        sourceEffectiveAt = Map.copyOf(sourceEffectiveAt);
        stateOverrides = Map.copyOf(stateOverrides);
        contentStates = Map.copyOf(contentStates);
        captureLimitations = captureLimitations.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
}
