package de.tum.cit.aet.hephaestus.practices.trace;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignalRepository.SignalledArtifactRow;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactCatalog;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackDeliveryState;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.ArtifactFeedbackRow;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackSuppressionReason;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.ArtifactObservationRow;
import de.tum.cit.aet.hephaestus.practices.review.DormantBinding;
import de.tum.cit.aet.hephaestus.practices.review.PracticeSignalCoverage;
import de.tum.cit.aet.hephaestus.practices.review.WorkspaceReviewDefaultsProvider;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOutcomeLookup.ReviewOutcome;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.PracticeOutput;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.SignalOccurrence;
import de.tum.cit.aet.hephaestus.practices.trace.TraceInputs.TracedPractice;
import de.tum.cit.aet.hephaestus.practices.trace.dto.ArtifactTraceDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.PracticeTraceEntryDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.TracedArtifactDTO;
import de.tum.cit.aet.hephaestus.practices.trace.dto.TracedSignalDTO;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the trace: the signal ledger crossed with the workspace's practices, the runs those
 * signals started, and what those runs produced.
 *
 * <p>A pure read model: it collects nothing, records nothing and adds no column.
 *
 * <p><b>The ledger is the tenancy boundary.</b> A mirrored merge request belongs to a workspace through
 * a monitor mapping rather than a column, so there is no cheap way to ask the mirror "is this yours" —
 * {@code artifact_signal} is workspace-scoped by construction, and an artifact with no row in it gets a
 * 404. The 404 therefore means "we have nothing recorded about this work" rather than standing in for a
 * permission check the caller cannot distinguish from absence.
 */
@Service
@RequiredArgsConstructor
class ArtifactTraceQueryService {

    private final ArtifactSignalRepository signals;
    private final PracticeRepository practices;
    private final PracticeSignalCoverage coverage;
    private final WorkspaceReviewDefaultsProvider workspaceDefaults;
    private final ObservationRepository observations;
    private final FeedbackRepository feedback;
    private final ReviewOutcomeLookup reviews;
    private final ArtifactCatalog artifacts;
    private final ArtifactIdentities identities;

    @Transactional(readOnly = true)
    public ArtifactTraceDTO trace(Long workspaceId, ArtifactKind artifactKind, Long artifactId) {
        List<ArtifactSignal> recorded = signals.findForArtifact(workspaceId, artifactKind.value(), artifactId);
        if (recorded.isEmpty()) {
            throw new EntityNotFoundException("Traced artifact", artifactKind.value() + "/" + artifactId);
        }
        Set<UUID> reviewIds = recorded.stream()
                .map(ArtifactSignal::getJobId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, ReviewOutcome> outcomes = reviews.findByIds(workspaceId, reviewIds);

        Map<SignalName, String> labels = signalLabels(artifactKind);
        List<TracedSignalDTO> tracedSignals = recorded.stream()
                .map(signal -> TracedSignalDTO.from(
                        signal,
                        labels.get(SignalName.of(signal.getSignalName())),
                        signal.getJobId() == null ? null : outcomes.get(signal.getJobId())))
                .toList();

        List<PracticeTraceEntryDTO> entries = PracticeTraceDeriver.derive(
                tracedPractices(workspaceId, artifactKind),
                recorded.stream()
                        .map(signal -> new SignalOccurrence(
                                signal.getId(),
                                SignalName.of(signal.getSignalName()),
                                signal.getOccurredAt(),
                                signal.getState(),
                                signal.getStateReason(),
                                signal.getJobId()))
                        .toList(),
                outcomes,
                outputs(workspaceId, artifactKind, artifactId));

        ArtifactIdentity identity = Objects.requireNonNull(identities
                .resolve(workspaceId, artifactKind, List.of(artifactId))
                .get(artifactId));
        return new ArtifactTraceDTO(
                artifactKind,
                artifactId,
                identity.title(),
                identity.number(),
                identity.container(),
                identity.url(),
                tracedSignals,
                entries);
    }

    @Transactional(readOnly = true)
    public Page<TracedArtifactDTO> list(Long workspaceId, @Nullable ArtifactKind artifactKind, Pageable pageable) {
        Page<SignalledArtifactRow> page = signals.findSignalledArtifacts(
                workspaceId, artifactKind == null ? null : artifactKind.value(), pageable);
        // One resolve call per kind on the page, not per row: a mixed page is at most as many round
        // trips as there are kinds, and a single-kind page is one.
        Map<ArtifactKind, List<Long>> idsByKind = new HashMap<>();
        for (SignalledArtifactRow row : page) {
            idsByKind
                    .computeIfAbsent(ArtifactKind.of(row.getArtifactKind()), kind -> new ArrayList<>())
                    .add(row.getArtifactId());
        }
        Map<ArtifactKind, Map<Long, ArtifactIdentity>> resolved = new HashMap<>();
        idsByKind.forEach((kind, ids) -> resolved.put(kind, identities.resolve(workspaceId, kind, ids)));
        return page.map(row -> {
            ArtifactKind kind = ArtifactKind.of(row.getArtifactKind());
            ArtifactIdentity identity = Objects.requireNonNull(
                    Objects.requireNonNull(resolved.get(kind)).get(row.getArtifactId()));
            return new TracedArtifactDTO(
                    kind,
                    row.getArtifactId(),
                    identity.title(),
                    identity.number(),
                    identity.container(),
                    identity.url(),
                    row.getLastSignalAt(),
                    Math.toIntExact(row.getSignalCount()),
                    Math.toIntExact(row.getReviewedSignalCount()));
        });
    }

    /**
     * Every practice this workspace runs against this kind of work, including the ones at {@code OFF}.
     *
     * <p>Filtering those out would answer "what ran" when the question is "why didn't anything", and a
     * practice somebody deliberately silenced is the single most useful row on the page.
     */
    private List<TracedPractice> tracedPractices(Long workspaceId, ArtifactKind artifactKind) {
        Map<Long, String> dormancy = dormancyContradictedByTheLedger(workspaceId, artifactKind);
        // The autonomy the trace shows is the EFFECTIVE one. A reader asking why a practice said nothing is
        // asking what is in force here, not which of the three levels happens to hold the row.
        PracticeAutonomy workspaceDefault =
                workspaceDefaults.forWorkspace(workspaceId).defaultAutonomy();
        return practices.findByWorkspaceId(workspaceId).stream()
                .filter(practice -> artifactKind.equals(practice.getArtifactKind()))
                .map(practice -> new TracedPractice(
                        practice.getId(),
                        practice.getSlug(),
                        practice.getName(),
                        AutonomyResolver.effectiveAutonomyOf(practice, workspaceDefault),
                        watches(practice),
                        dormancy.get(practice.getId())))
                .toList();
    }

    /**
     * Dormancy as declared by coverage, minus every claim the ledger refutes.
     *
     * <p>{@code PracticeSignalCoverage} answers from the connection registry, which is the right source
     * for "will this ever fire here" and the wrong one for "has this ever fired": a signal already in
     * this workspace's ledger demonstrably arrives, whatever the registry says.
     *
     * <p>Only this page's artifact kind is read back — a practice's signals carry their kind in their own
     * names, so a row filed under another kind could never have refuted one of these claims.
     */
    private Map<Long, String> dormancyContradictedByTheLedger(Long workspaceId, ArtifactKind artifactKind) {
        List<DormantBinding> dormant = coverage.dormantBindings(workspaceId);
        if (dormant.isEmpty()) {
            return Map.of();
        }
        Set<SignalName> everRecorded = signals.findRecordedSignalNames(workspaceId, artifactKind.value()).stream()
                .map(SignalName::of)
                .collect(Collectors.toUnmodifiableSet());
        return dormant.stream()
                .filter(binding -> binding.signals().stream().noneMatch(everRecorded::contains))
                .collect(Collectors.toMap(DormantBinding::practiceId, DormantBinding::reason, (a, b) -> a));
    }

    private static List<SignalName> watches(Practice practice) {
        return PracticeBinding.signalsOf(practice.getBindings());
    }

    private Map<Long, PracticeOutput> outputs(Long workspaceId, ArtifactKind artifactKind, Long artifactId) {
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, UUID> latestReview = new HashMap<>();
        Map<Long, Instant> latestObserved = new HashMap<>();
        for (ArtifactObservationRow row : observations.findForArtifact(workspaceId, artifactKind, artifactId)) {
            counts.merge(row.getPracticeId(), 1, Integer::sum);
            Instant seen = latestObserved.get(row.getPracticeId());
            if (seen == null || row.getObservedAt().isAfter(seen)) {
                latestObserved.put(row.getPracticeId(), row.getObservedAt());
                if (row.getReviewId() != null) {
                    latestReview.put(row.getPracticeId(), row.getReviewId());
                }
            }
        }
        Map<Long, Integer> delivered = new HashMap<>();
        Map<Long, Collection<FeedbackSuppressionReason>> withheld = new HashMap<>();
        for (ArtifactFeedbackRow row : feedback.summarizeForArtifact(workspaceId, artifactKind, artifactId)) {
            if (row.getDeliveryState() == FeedbackDeliveryState.DELIVERED) {
                delivered.merge(row.getPracticeId(), Math.toIntExact(row.getUnits()), Integer::sum);
            } else if (row.getSuppressionReason() != null) {
                withheld.computeIfAbsent(row.getPracticeId(), practiceId -> new LinkedHashSet<>())
                        .add(row.getSuppressionReason());
            }
        }
        Set<Long> practiceIds = new LinkedHashSet<>(counts.keySet());
        practiceIds.addAll(delivered.keySet());
        practiceIds.addAll(withheld.keySet());
        Map<Long, PracticeOutput> outputs = new HashMap<>();
        for (Long practiceId : practiceIds) {
            outputs.put(
                    practiceId,
                    new PracticeOutput(
                            counts.getOrDefault(practiceId, 0),
                            delivered.getOrDefault(practiceId, 0),
                            List.copyOf(withheld.getOrDefault(practiceId, List.of())),
                            latestReview.get(practiceId),
                            latestObserved.get(practiceId)));
        }
        return outputs;
    }

    private Map<SignalName, String> signalLabels(ArtifactKind artifactKind) {
        return artifacts.descriptorFor(artifactKind).map(ArtifactDescriptor::signals).orElseGet(List::of).stream()
                .collect(Collectors.toMap(Signal::name, Signal::displayName, (a, b) -> a));
    }
}
