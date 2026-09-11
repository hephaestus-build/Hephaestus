package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.evidence.PracticeSubjectCheck;
import de.tum.cit.aet.hephaestus.evidence.RequiredCaptureQuality;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceState;
import de.tum.cit.aet.hephaestus.evidence.SourceArtifact;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureFacts;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ContextManifestBuilder {

    public record PreparedAutomatedReviewReadiness(
            List<Practice> readyPractices, AutomatedReviewReadinessReport report) {
        public PreparedAutomatedReviewReadiness {
            readyPractices = List.copyOf(readyPractices);
            Objects.requireNonNull(report, "report");
        }
    }

    public record CaptureMetadata(
            Map<SourceKind, SourceCompleteness> reportedCompleteness,
            Map<SourceKind, SourceContentState> reportedContentStates,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceCaptureState> stateOverrides,
            /** Per source, what its capture could not include; empty for a source that captured it all. */
            Map<SourceKind, List<String>> captureLimitations,
            Set<SourceKind> attemptedKinds) {
        public CaptureMetadata(
                Map<SourceKind, SourceCompleteness> reportedCompleteness,
                Map<SourceKind, String> immutableIdentities,
                Map<SourceKind, Instant> observedAt,
                Map<SourceKind, Instant> sourceEffectiveAt,
                Map<SourceKind, SourceCaptureState> stateOverrides,
                Set<SourceKind> attemptedKinds) {
            this(
                    reportedCompleteness,
                    Map.of(),
                    immutableIdentities,
                    observedAt,
                    sourceEffectiveAt,
                    stateOverrides,
                    Map.of(),
                    attemptedKinds);
        }

        public CaptureMetadata(
                Map<SourceKind, SourceCompleteness> reportedCompleteness,
                Map<SourceKind, SourceContentState> reportedContentStates,
                Map<SourceKind, String> immutableIdentities,
                Map<SourceKind, Instant> observedAt,
                Map<SourceKind, Instant> sourceEffectiveAt,
                Map<SourceKind, SourceCaptureState> stateOverrides,
                Set<SourceKind> attemptedKinds) {
            this(
                    reportedCompleteness,
                    reportedContentStates,
                    immutableIdentities,
                    observedAt,
                    sourceEffectiveAt,
                    stateOverrides,
                    Map.of(),
                    attemptedKinds);
        }
    }

    private final JsonMapper objectMapper;
    private final ArtifactSourceCatalogRegistry catalogs;
    private final PracticeSubjectEvaluator subjectEvaluator;
    private final Clock clock;

    public ContextManifestBuilder(
            JsonMapper objectMapper,
            ArtifactSourceCatalogRegistry catalogs,
            PracticeSubjectEvaluator subjectEvaluator,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.catalogs = catalogs;
        this.subjectEvaluator = subjectEvaluator;
        this.clock = clock;
    }

    void validateEvidenceSources(List<ContentSource> providers) {
        Set<SourceKind> seen = new HashSet<>();
        for (ContentSource provider : providers) {
            if (!(provider instanceof EvidenceSource evidenceSource)) continue;
            for (SourceKind kind : evidenceSource.sourceKinds()) {
                catalogs.requireSource(catalogs.current().version(), kind);
                if (!seen.add(kind)) {
                    throw new IllegalStateException("Multiple evidence providers declare source kind " + kind);
                }
            }
        }
    }

    /** For captures held entirely in memory. */
    public ArtifactSourceManifest augment(
            Map<String, byte[]> files,
            Map<String, SourceKind> pathKinds,
            String jobId,
            EvidencePlan plan,
            CaptureMetadata metadata) {
        return augment(files, Map.of(), pathKinds, jobId, plan, metadata);
    }

    public ArtifactSourceManifest augment(
            Map<String, byte[]> files,
            Map<String, java.nio.file.Path> filesOnDisk,
            Map<String, SourceKind> pathKinds,
            String jobId,
            EvidencePlan plan,
            CaptureMetadata metadata) {
        Instant capturedAt = clock.instant();
        Set<SourceKind> applicableSources = stagedSources(plan);
        // The manifest enumerates the applicable sources and nothing else, so a fact reported for a
        // source outside them would be dropped in silence. A collector that got here with one has a
        // wiring bug worth failing on, not a capture worth publishing minus the part nobody can read.
        Set<SourceKind> reported = new HashSet<>(metadata.attemptedKinds());
        reported.addAll(metadata.stateOverrides().keySet());
        reported.addAll(metadata.reportedCompleteness().keySet());
        reported.addAll(metadata.reportedContentStates().keySet());
        reported.addAll(metadata.immutableIdentities().keySet());
        reported.addAll(metadata.observedAt().keySet());
        reported.addAll(metadata.sourceEffectiveAt().keySet());
        reported.addAll(metadata.captureLimitations().keySet());
        reported.removeAll(applicableSources);
        if (!reported.isEmpty()) {
            throw new IllegalArgumentException(
                    "Capture reports sources that do not apply to " + plan.artifactKind() + ": " + reported);
        }
        List<SourceCapture> captures = applicableSources.stream()
                .sorted()
                .map(kind -> capture(
                        kind,
                        files,
                        filesOnDisk,
                        pathKinds,
                        plan,
                        capturedAt,
                        metadata.reportedCompleteness(),
                        metadata.reportedContentStates(),
                        metadata.immutableIdentities(),
                        metadata.observedAt(),
                        metadata.sourceEffectiveAt(),
                        metadata.stateOverrides(),
                        metadata.captureLimitations(),
                        metadata.attemptedKinds()))
                .toList();
        ArtifactSourceManifest manifest = new ArtifactSourceManifest(
                plan.contractVersion(),
                catalogs.catalogDigest(),
                plan.artifactKind().value(),
                capturedAt,
                captures);
        try {
            byte[] internalBytes = objectMapper.writeValueAsBytes(manifest);
            files.put(SandboxLayout.MANIFEST_PATH, internalBytes);
            return manifest;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Artifact-source manifest generation failed", e);
        }
    }

    boolean isSourceUsePermitted(SourceContractVersion version, SourceKind kind) {
        return catalogs.isSourceUsePermitted(version, kind, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
    }

    /**
     * Every source this capture stages: all of them, for the artifact kind under review.
     *
     * <p>There is no per-run selection to apply on top. A source that applies to the kind is attempted;
     * what comes back — available, withheld for want of a use decision, unavailable for want of a
     * collector, or a collection error — is recorded per source in the manifest and is the model's to
     * read. No source is dropped because no practice asked for it.
     */
    Set<SourceKind> stagedSources(EvidencePlan plan) {
        return catalogs.requireSourcesFor(
                plan.contractVersion(), plan.artifactKind().value());
    }

    public PreparedAutomatedReviewReadiness prepareAutomatedReviewReadiness(
            ArtifactSourceManifest manifest,
            List<Practice> practices,
            String jobId,
            Instant temporalAnchor,
            @Nullable SignalName signal,
            Map<String, byte[]> staged) {
        AutomatedReviewReadinessResult result =
                checkAutomatedReviewReadiness(manifest, practices, temporalAnchor, signal, staged);
        if (result.decisions().isEmpty()) {
            throw new IllegalArgumentException("Cannot persist an empty automated-review readiness report");
        }
        Instant decidedAt = result.decisions().getFirst().decidedAt();
        AutomatedReviewReadinessReport report = new AutomatedReviewReadinessReport(
                manifest.contractVersion(),
                manifest.catalogDigest(),
                manifest.artifactKind(),
                manifest.capturedAt(),
                decidedAt,
                result.decisions());
        return new PreparedAutomatedReviewReadiness(result.readyPractices(), report);
    }

    /**
     * Convenience for callers judging evidence as of now. A replay reproducing a past decision must
     * pass that decision's anchor instead: defaulting to the current instant would re-date the
     * question and can flip a verdict that was correct when it was made.
     */
    public AutomatedReviewReadinessResult checkAutomatedReviewReadinessAsOfNow(
            ArtifactSourceManifest manifest, List<Practice> practices) {
        return checkAutomatedReviewReadiness(manifest, practices, clock.instant(), null, Map.of());
    }

    /**
     * Readiness judged without the staged bytes, which makes every subject declaration undecidable and
     * therefore asks every practice whose evidence is readable. The safe reading for a caller — a
     * replay, a test — that holds a manifest but not the capture it describes.
     */
    public AutomatedReviewReadinessResult checkAutomatedReviewReadiness(
            ArtifactSourceManifest manifest,
            List<Practice> practices,
            Instant temporalAnchor,
            @Nullable SignalName signal) {
        return checkAutomatedReviewReadiness(manifest, practices, temporalAnchor, signal, Map.of());
    }

    /**
     * @param signal what occasioned the review, which decides which of each practice's bindings speaks
     *               for it; {@code null} means nobody named an occasion and every binding does
     * @param staged the capture's own bytes, from which a practice's declared subject is decided. Empty
     *               means "not supplied", which leaves every subject undecided and every practice asked
     */
    public AutomatedReviewReadinessResult checkAutomatedReviewReadiness(
            ArtifactSourceManifest manifest,
            List<Practice> practices,
            Instant temporalAnchor,
            @Nullable SignalName signal,
            Map<String, byte[]> staged) {
        Objects.requireNonNull(temporalAnchor, "temporalAnchor");
        Objects.requireNonNull(staged, "staged");
        // A manifest recorded under a source contract this runtime no longer ships is unreplayable
        // rather than invalid: the recorded decision remains correct for the evidence it was made on.
        // Declining to re-derive a readiness result is correct; failing as though the evidence were
        // malformed is not.
        if (!catalogs.current().version().equals(manifest.contractVersion())
                || !catalogs.catalogDigest().equals(manifest.catalogDigest())) {
            throw new UnreplayableEvidenceException("Manifest references source contract " + manifest.contractVersion()
                    + " (digest "
                    + manifest.catalogDigest()
                    + "), which this runtime no longer ships");
        }
        Set<SourceKind> expectedKinds = catalogs.requireSourcesFor(manifest.contractVersion(), manifest.artifactKind());
        Set<SourceKind> capturedKinds =
                manifest.sources().stream().map(SourceCapture::kind).collect(java.util.stream.Collectors.toSet());
        if (!capturedKinds.equals(expectedKinds)) {
            throw new IllegalArgumentException(
                    "Manifest source captures do not match the sources its artifact kind applies to");
        }
        Map<SourceKind, SourceCapture> captures = new HashMap<>();
        manifest.sources().forEach(capture -> captures.put(capture.kind(), capture));
        Instant checkedAt = clock.instant();
        List<Practice> ready = new ArrayList<>();
        List<AutomatedReviewReadinessDecision> decisions = new ArrayList<>();
        for (Practice practice : practices) {
            var requirements = practice.getAutomatedReviewPolicy();
            if (requirements == null) {
                throw new IllegalArgumentException("Practice has no evidence requirements: " + practice.getSlug());
            }
            if (!requirements.sourceContractVersion().equals(manifest.contractVersion())
                    || !practice.getArtifactKind().value().equals(manifest.artifactKind())) {
                throw new IllegalArgumentException(
                        "Practice evidence contract does not match manifest: " + practice.getSlug());
            }
            List<AutomatedReviewReadinessReason> decisionReasons = new ArrayList<>();
            switch (requirements.automatedReview().mode()) {
                case NONE -> decisionReasons.add(AutomatedReviewReadinessReason.NO_AUTOMATED_REVIEW);
                case LANGUAGE_MODEL -> {}
            }
            switch (requirements.automatedReview().evidenceSufficiency()) {
                case DECLARED_EVIDENCE_INSUFFICIENT ->
                    decisionReasons.add(AutomatedReviewReadinessReason.DECLARED_EVIDENCE_INSUFFICIENT);
                case SUFFICIENT_WHEN_REQUIREMENTS_MET, NONE -> {}
            }
            List<SourceReadinessCheck> sourceChecks = new ArrayList<>();
            // Only the bindings this occasion matched speak here, and within them only the sources the
            // practice takes a refusing stance on.
            for (var need : PracticeBinding.needsFor(practice.getBindings(), signal)) {
                if (!need.refuses()) {
                    // A contextual source is read when it is there and noted when it is not, which is a
                    // fact for the manifest to carry rather than a reason to withhold the review.
                    continue;
                }
                // How strictly the capture must have gone is the source's answer, not the practice's:
                // stating it per practice is only a way for two practices to disagree about one source.
                RequiredCaptureQuality quality = catalogs.requireSource(manifest.contractVersion(), need.sourceKind())
                        .requiredQuality();
                SourceCapture capture = captures.get(need.sourceKind());
                SourceCaptureState.Available available =
                        capture != null && capture.state() instanceof SourceCaptureState.Available captured
                                ? captured
                                : null;
                List<SourceReadinessReason> reasons = new ArrayList<>();
                if (available == null) {
                    // Absence is the whole answer. Incompleteness and emptiness are facts ABOUT a
                    // capture, so a source nothing captured cannot also be partial or empty; stating
                    // all three at once contradicts itself and sends the reader to the wrong fix.
                    reasons.add(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
                } else {
                    // The one thing the practice still gets to say about the capture, because it is a
                    // statement about the claim rather than about the source: a review that asserts
                    // something is absent cannot be satisfied by a fragment that merely does not
                    // contain it.
                    boolean demandsComplete =
                            quality.demandsComplete() || need.stance().demandsCompleteCapture();
                    if (demandsComplete && available.completeness() != SourceCompleteness.COMPLETE)
                        reasons.add(SourceReadinessReason.SOURCE_INCOMPLETE);
                    if (quality.demandsContent() && available.content() != SourceContentState.NON_EMPTY)
                        reasons.add(SourceReadinessReason.SOURCE_EMPTY);
                }
                sourceChecks.add(new SourceReadinessCheck(
                        need.sourceKind(),
                        manifest.contractVersion(),
                        checkedAt,
                        temporalAnchor,
                        reasons.isEmpty(),
                        reasons));
            }
            // The subject is asked about last, and only of a practice that would otherwise be asked.
            // "We could not read what this needs" outranks "there was nothing of this kind here",
            // because a capture we could not read cannot establish the second: judging the subject over
            // it would dress an instrument failure up as a fact about somebody's work.
            boolean readableAndDeclared = decisionReasons.isEmpty()
                    && sourceChecks.stream().allMatch(SourceReadinessCheck::meetsRequirements);
            PracticeSubjectCheck subjectCheck = readableAndDeclared
                    ? subjectEvaluator.evaluate(
                            PracticeBinding.subjectFor(practice.getBindings(), signal), manifest, staged)
                    : null;
            if (subjectCheck != null && subjectCheck.absent()) {
                decisionReasons.add(AutomatedReviewReadinessReason.SUBJECT_NOT_IN_THE_WORK);
            }
            AutomatedReviewReadinessDecision decision = new AutomatedReviewReadinessDecision(
                    practice.getSlug(),
                    checkedAt,
                    decisionReasons.isEmpty()
                            && sourceChecks.stream().allMatch(SourceReadinessCheck::meetsRequirements),
                    decisionReasons,
                    sourceChecks,
                    subjectCheck);
            decisions.add(decision);
            if (decision.ready()) ready.add(practice);
        }
        return new AutomatedReviewReadinessResult(ready, decisions);
    }

    private SourceCapture capture(
            SourceKind kind,
            Map<String, byte[]> files,
            Map<String, java.nio.file.Path> filesOnDisk,
            Map<String, SourceKind> pathKinds,
            EvidencePlan plan,
            Instant capturedAt,
            Map<SourceKind, SourceCompleteness> reportedCompleteness,
            Map<SourceKind, SourceContentState> reportedContentStates,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceCaptureState> stateOverrides,
            Map<SourceKind, List<String>> captureLimitations,
            Set<SourceKind> attemptedKinds) {
        ArtifactSourceContract contract = catalogs.requireSource(plan.contractVersion(), kind);
        SourceCaptureState override = stateOverrides.get(kind);
        if (override != null) {
            return missingCapture(contract, override);
        }
        if (!attemptedKinds.contains(kind)) {
            return missingCapture(contract, new SourceCaptureState.Unavailable(SourceAbsenceReason.NO_PROVIDER));
        }
        List<SourceArtifact> artifacts = pathKinds.entrySet().stream()
                .filter(entry -> entry.getValue().equals(kind))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> artifact(entry.getKey(), files.get(entry.getKey()), filesOnDisk.get(entry.getKey())))
                .toList();
        if (artifacts.isEmpty() && !contract.completenessPolicy().supportsEmpty()) {
            return missingCapture(contract, new SourceCaptureState.Unavailable(SourceAbsenceReason.EMPTY_NOT_VALID));
        }
        SourceCompleteness completeness =
                reportedCompleteness.getOrDefault(kind, inferredCompleteness(contract, artifacts, files));
        if ((completeness == SourceCompleteness.COMPLETE
                        && !contract.completenessPolicy().supportsComplete())
                || (completeness == SourceCompleteness.PARTIAL
                        && !contract.completenessPolicy().supportsPartial())) {
            throw new IllegalStateException(
                    kind + " reported completeness forbidden by its source contract: " + completeness);
        }
        SourceContentState content = reportedContentStates.getOrDefault(
                kind, artifacts.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY);
        if (content == SourceContentState.EMPTY
                && !contract.completenessPolicy().supportsEmpty()) {
            throw new IllegalStateException(kind + " reported EMPTY although its source contract forbids it");
        }
        SourceCaptureFacts facts = new SourceCaptureFacts(
                capturedAt, sourceEffectiveAt.get(kind), observedAt.get(kind), immutableIdentities.get(kind));
        List<String> limitations = captureLimitations.getOrDefault(kind, List.of());
        // A collector that named an omission and still reported COMPLETE is contradicting itself; the
        // completeness is the claim a practice acts on, so the omission is what has to win.
        if (!limitations.isEmpty() && completeness == SourceCompleteness.COMPLETE) {
            throw new IllegalStateException(kind + " reported COMPLETE while naming what it omitted: " + limitations);
        }
        return new SourceCapture(
                kind, new SourceCaptureState.Available(content, completeness, facts, limitations), artifacts);
    }

    private static SourceCapture missingCapture(ArtifactSourceContract contract, SourceCaptureState state) {
        SourceAbsenceState absenceState = absenceState(state);
        if (!contract.supportedAbsenceStates().contains(absenceState)) {
            throw new IllegalArgumentException(contract.kind() + " does not support absence state " + absenceState);
        }
        return new SourceCapture(contract.kind(), state, List.of());
    }

    private SourceCompleteness inferredCompleteness(
            ArtifactSourceContract contract, List<SourceArtifact> artifacts, Map<String, byte[]> files) {
        if (artifacts.isEmpty()) {
            if (!contract.completenessPolicy().supportsEmpty()) return SourceCompleteness.UNKNOWN;
            if (contract.completenessPolicy().supportsComplete()) return SourceCompleteness.COMPLETE;
            if (contract.completenessPolicy().supportsPartial()) return SourceCompleteness.PARTIAL;
            return SourceCompleteness.UNKNOWN;
        }
        List<Boolean> truncationMarkers = artifacts.stream()
                .map(artifact -> truncationMarker(files.get(artifact.path())))
                .flatMap(Optional::stream)
                .toList();
        if (truncationMarkers.stream().anyMatch(Boolean.TRUE::equals)) {
            return SourceCompleteness.PARTIAL;
        }
        // Every artifact must have said it was untruncated. One marker among several unmarked files
        // is not evidence about the unmarked ones, and COMPLETE is the claim a practice requires.
        if (truncationMarkers.size() == artifacts.size()
                && contract.completenessPolicy().supportsComplete()) {
            return SourceCompleteness.COMPLETE;
        }
        return contract.completenessPolicy().supportsPartial()
                ? SourceCompleteness.PARTIAL
                : SourceCompleteness.UNKNOWN;
    }

    private Optional<Boolean> truncationMarker(byte @Nullable [] bytes) {
        if (bytes == null || bytes.length == 0 || bytes[0] != '{') {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(bytes);
            return node.has("truncated") && node.path("truncated").isBoolean()
                    ? Optional.of(node.path("truncated").asBoolean())
                    : Optional.empty();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private SourceArtifact artifact(String path, byte @Nullable [] bytes, java.nio.file.@Nullable Path onDisk) {
        if (onDisk != null) {
            try {
                return new SourceArtifact(
                        path, mediaType(path), artifactDigest(onDisk), java.nio.file.Files.size(onDisk));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Evidence artifact unreadable: " + path, e);
            }
        }
        if (bytes == null) {
            throw new IllegalStateException("Evidence artifact has null bytes: " + path);
        }
        return new SourceArtifact(path, mediaType(path), ProvenanceDigest.sha256Hex(bytes), bytes.length);
    }

    private static String artifactDigest(java.nio.file.Path path) throws java.io.IOException {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var stream = new java.security.DigestInputStream(java.nio.file.Files.newInputStream(path), digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static SourceAbsenceState absenceState(SourceCaptureState state) {
        if (state instanceof SourceCaptureState.NotCollected) return SourceAbsenceState.NOT_COLLECTED;
        if (state instanceof SourceCaptureState.Unavailable) return SourceAbsenceState.UNAVAILABLE;
        if (state instanceof SourceCaptureState.Redacted) return SourceAbsenceState.REDACTED;
        if (state instanceof SourceCaptureState.CollectionError) return SourceAbsenceState.COLLECTION_ERROR;
        throw new IllegalArgumentException("AVAILABLE is not an absence state");
    }

    private static String mediaType(String path) {
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".md")) return "text/markdown";
        if (path.endsWith(".patch")) return "text/x-diff";
        return "application/octet-stream";
    }
}
