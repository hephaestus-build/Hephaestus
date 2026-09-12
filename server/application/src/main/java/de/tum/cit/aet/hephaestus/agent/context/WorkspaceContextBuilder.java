package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

/**
 * Materialises attempt-local workspace inputs. Planned
 * evidence builds record collection failures for readiness refusal; programming failures, undeclared paths,
 * and duplicate outputs remain fatal.
 */
@Service
public class WorkspaceContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceContextBuilder.class);

    private final List<ContentSource> providers;
    private final MeterRegistry meterRegistry;

    private final @Nullable ContextManifestBuilder manifestBuilder;

    public WorkspaceContextBuilder(
            List<ContentSource> providers,
            MeterRegistry meterRegistry,
            @Nullable ContextManifestBuilder manifestBuilder) {
        List<ContentSource> sorted = new ArrayList<>(providers);
        AnnotationAwareOrderComparator.sort(sorted);
        this.providers = List.copyOf(sorted);
        this.meterRegistry = meterRegistry;
        this.manifestBuilder = manifestBuilder;
        if (manifestBuilder != null) {
            manifestBuilder.validateEvidenceSources(this.providers);
        }
        log.info(
                "WorkspaceContextBuilder registered {} provider(s): {}",
                this.providers.size(),
                this.providers.stream().map(p -> p.getClass().getSimpleName()).toList());
    }

    public Map<String, byte[]> build(ContextRequest request) {
        return buildWithoutManifest(request);
    }

    public PreparedEvidence prepare(ContextRequest request, EvidencePlan evidencePlan) {
        long startNs = System.nanoTime();
        try {
            BuildResult result = buildInputs(request, evidencePlan);
            var prepared = new PreparedEvidence(
                    result.files(), result.filesOnDisk(), result.cleanups(), result.manifest(), result.directories());
            if (prepared.manifest() == null) {
                prepared.close();
                throw new IllegalStateException("Detector evidence was prepared without a source manifest");
            }
            return prepared;
        } finally {
            meterRegistry
                    .timer(
                            AgentMetrics.AGENT_CONTEXT_BUILD_DURATION,
                            Tags.of("kind", request.getClass().getSimpleName()))
                    .record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * @param staged the capture's own bytes, so a practice's declared subject can be decided from the
     *               evidence rather than put to the model; pass {@link PreparedEvidence#files()}
     */
    public ContextManifestBuilder.PreparedAutomatedReviewReadiness prepareAutomatedReviewReadiness(
            ArtifactSourceManifest manifest,
            List<Practice> practices,
            String jobId,
            Instant temporalAnchor,
            @Nullable SignalName signal,
            Map<String, byte[]> staged) {
        if (manifestBuilder == null) {
            throw new IllegalStateException("Evidence readiness requires a manifest builder");
        }
        return manifestBuilder.prepareAutomatedReviewReadiness(
                manifest, practices, jobId, temporalAnchor, signal, staged);
    }

    private Map<String, byte[]> buildWithoutManifest(ContextRequest request) {
        long startNs = System.nanoTime();
        try {
            return buildInputs(request, null).files();
        } finally {
            meterRegistry
                    .timer(
                            AgentMetrics.AGENT_CONTEXT_BUILD_DURATION,
                            Tags.of("kind", request.getClass().getSimpleName()))
                    .record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
        }
    }

    private record BuildResult(
            Map<String, byte[]> files,
            Map<String, java.nio.file.Path> filesOnDisk,
            List<EvidenceDirectory> directories,
            List<AutoCloseable> cleanups,
            @Nullable ArtifactSourceManifest manifest) {}

    private BuildResult buildInputs(ContextRequest request, @Nullable EvidencePlan evidencePlan) {
        // Every source the contract says applies to this artifact kind — not a subset chosen for the
        // practices in scope. What a practice needs before it may be reviewed is asked later, by the
        // readiness check; this one is only "what can the model see".
        Set<SourceKind> stagedSources = Set.of();
        if (evidencePlan != null) {
            if (manifestBuilder == null) {
                throw new IllegalStateException("Detector evidence capture requires a source manifest builder");
            }
            stagedSources = manifestBuilder.stagedSources(evidencePlan);
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        Map<String, String> keyOwner = new HashMap<>();
        Map<String, SourceKind> keySourceKind = new HashMap<>();
        Map<SourceKind, SourceCompleteness> completeness = new HashMap<>();
        Map<SourceKind, SourceContentState> contentStates = new HashMap<>();
        Map<SourceKind, String> immutableIdentities = new HashMap<>();
        Map<SourceKind, Instant> observedAt = new HashMap<>();
        Map<SourceKind, Instant> sourceEffectiveAt = new HashMap<>();
        Map<SourceKind, SourceCaptureState> stateOverrides = new HashMap<>();
        Map<SourceKind, List<String>> captureLimitations = new HashMap<>();
        Set<SourceKind> attemptedKinds = new HashSet<>();
        Map<String, java.nio.file.Path> filesOnDisk = new LinkedHashMap<>();
        List<EvidenceDirectory> directories = new ArrayList<>();
        List<AutoCloseable> cleanups = new ArrayList<>();
        try {
            int contributed = 0;
            for (ContentSource provider : providers) {
                if (!provider.supports(request)) {
                    continue;
                }
                if (evidencePlan != null && !(provider instanceof EvidenceSource)) {
                    throw new IllegalStateException("Detector context provider must declare source kinds: "
                            + provider.getClass().getSimpleName());
                }
                String providerName = provider.getClass().getSimpleName();
                Map<String, byte[]> contributionFiles;
                if (evidencePlan != null && provider instanceof EvidenceSource evidenceSource) {
                    // A collector whose kinds do not apply to this artifact kind at all — the Slack thread
                    // reader on a pull-request review — has nothing to say here. The manifest already reports
                    // only the kinds that apply, so there is no absence to record for it either.
                    if (evidenceSource.sourceKinds().stream().noneMatch(stagedSources::contains)) {
                        continue;
                    }
                    contributionFiles = captureIndependently(
                            request,
                            evidencePlan,
                            stagedSources,
                            evidenceSource,
                            providerName,
                            completeness,
                            contentStates,
                            immutableIdentities,
                            observedAt,
                            sourceEffectiveAt,
                            stateOverrides,
                            captureLimitations,
                            attemptedKinds,
                            filesOnDisk,
                            directories,
                            cleanups);
                } else {
                    try {
                        Map<String, byte[]> localFiles = new LinkedHashMap<>();
                        provider.contribute(request, localFiles);
                        contributionFiles = localFiles;
                    } catch (JobPreparationException e) {
                        throw e;
                    } catch (RuntimeException e) {
                        if (!(e instanceof EvidenceCollectionException)) throw e;
                        if (provider.required()) {
                            meterRegistry
                                    .counter(
                                            AgentMetrics.AGENT_CONTEXT_PROVIDER_REQUIRED_FAILURE,
                                            Tags.of("provider", providerName))
                                    .increment();
                            throw new JobPreparationException("Required content provider failed: " + providerName, e);
                        }
                        log.warn("Optional content provider failed, continuing: {} — {}", providerName, e.getMessage());
                        continue;
                    }
                }
                Set<String> contributedKeys = new LinkedHashSet<>(contributionFiles.keySet());
                for (var onDisk : filesOnDisk.entrySet()) {
                    if (!keyOwner.containsKey(onDisk.getKey())) {
                        contributedKeys.add(onDisk.getKey());
                    }
                }
                for (String key : contributedKeys) {
                    byte[] value = contributionFiles.get(key);
                    if (files.containsKey(key)) {
                        throw new IllegalStateException("Duplicate workspace key " + key
                                + ": written by both "
                                + keyOwner.get(key)
                                + " and "
                                + providerName);
                    }
                    if (!provider.ownsPath(key)) {
                        throw new IllegalStateException(
                                providerName + " wrote file outside its declared input namespace: " + key);
                    }
                    keyOwner.put(key, providerName);
                    if (provider instanceof EvidenceSource evidenceSource) {
                        SourceKind kind = evidenceSource.sourceKindFor(key);
                        if (!evidenceSource.sourceKinds().contains(kind)) {
                            throw new IllegalStateException(
                                    providerName + " mapped output to undeclared source kind " + kind);
                        }
                        if (evidencePlan != null && !stagedSources.contains(kind)) {
                            throw new IllegalStateException(providerName + " emitted source kind " + kind
                                    + ", which does not apply to this artifact");
                        }
                        keySourceKind.put(key, kind);
                    } else if (evidencePlan != null) {
                        throw new IllegalStateException(providerName + " emitted undocumented detector input " + key);
                    }
                    if (value == null) {
                        // Staged from disk: the bytes are never read by this process.
                        continue;
                    }
                    files.put(key, value.clone());
                }
                contributed++;
            }
            ArtifactSourceManifest manifest = null;
            if (manifestBuilder != null && evidencePlan != null) {
                AgentJob job = reviewJob(request);
                if (job != null) {
                    manifest = manifestBuilder.augment(
                            files,
                            filesOnDisk,
                            keySourceKind,
                            String.valueOf(job.getId()),
                            evidencePlan,
                            new ContextManifestBuilder.CaptureMetadata(
                                    completeness,
                                    contentStates,
                                    immutableIdentities,
                                    observedAt,
                                    sourceEffectiveAt,
                                    stateOverrides,
                                    captureLimitations,
                                    attemptedKinds));
                }
            }
            log.debug(
                    "Workspace context built: {} files ({} staged from disk) from {} provider(s)",
                    files.size() + filesOnDisk.size(),
                    filesOnDisk.size(),
                    contributed);
            return new BuildResult(files, filesOnDisk, directories, cleanups, manifest);
        } catch (RuntimeException exception) {
            // A later provider or the manifest failing must not strand what earlier collectors staged on
            // disk: nobody else will ever hold these cleanups.
            for (AutoCloseable cleanup : cleanups) {
                try {
                    cleanup.close();
                } catch (Exception failure) {
                    exception.addSuppressed(failure);
                }
            }
            throw exception;
        }
    }

    private Map<String, byte[]> captureIndependently(
            ContextRequest request,
            EvidencePlan plan,
            Set<SourceKind> stagedSources,
            EvidenceSource source,
            String providerName,
            Map<SourceKind, SourceCompleteness> completeness,
            Map<SourceKind, SourceContentState> contentStates,
            Map<SourceKind, String> immutableIdentities,
            Map<SourceKind, Instant> observedAt,
            Map<SourceKind, Instant> sourceEffectiveAt,
            Map<SourceKind, SourceCaptureState> stateOverrides,
            Map<SourceKind, List<String>> captureLimitations,
            Set<SourceKind> attemptedKinds,
            Map<String, java.nio.file.Path> filesOnDisk,
            List<EvidenceDirectory> directories,
            List<AutoCloseable> cleanups) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Set<SourceKind> selectedKinds = new HashSet<>(source.sourceKinds());
        selectedKinds.retainAll(stagedSources);
        if (manifestBuilder != null) {
            for (SourceKind kind : Set.copyOf(selectedKinds)) {
                if (!manifestBuilder.isSourceUsePermitted(plan.contractVersion(), kind)) {
                    stateOverrides.put(
                            kind, new SourceCaptureState.NotCollected(SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE));
                    selectedKinds.remove(kind);
                }
            }
        }
        for (SourceKind kind : source.sourceKinds()) {
            if (!selectedKinds.contains(kind)) continue;
            attemptedKinds.add(kind);
            EvidenceContribution contribution;
            try {
                contribution = source.capture(request, Set.of(kind));
            } catch (RuntimeException e) {
                // Sources are captured independently so one failing collector costs only its own source;
                // letting the exception propagate would instead discard every source already captured
                // for this job. Only failures raised by the collector are absorbed here — the checks
                // below still propagate.
                stateOverrides.put(kind, new SourceCaptureState.CollectionError(SourceAbsenceReason.PROVIDER_FAILURE));
                meterRegistry
                        .counter(
                                AgentMetrics.AGENT_CONTEXT_PROVIDER_REQUIRED_FAILURE, Tags.of("provider", providerName))
                        .increment();
                log.warn(
                        "Evidence source failed; recording collection error: {} {} — {}",
                        providerName,
                        kind,
                        e.getMessage());
                continue;
            }
            // Held before the checks below so a rejected contribution is released with the rest.
            if (contribution.cleanup() != null) {
                cleanups.add(contribution.cleanup());
            }
            validateContribution(source, Set.of(kind), contribution);
            contribution.files().forEach((path, bytes) -> {
                if (files.put(path, bytes) != null) {
                    throw new IllegalStateException(providerName + " emitted duplicate file " + path);
                }
            });
            contribution.filesOnDisk().forEach((path, file) -> {
                if (filesOnDisk.put(path, file) != null || files.containsKey(path)) {
                    throw new IllegalStateException(providerName + " emitted duplicate file " + path);
                }
            });
            for (EvidenceDirectory directory : contribution.directories()) {
                if (!source.ownsPath(directory.target())
                        || directories.stream()
                                .anyMatch(existing -> existing.target().startsWith(directory.target())
                                        || directory.target().startsWith(existing.target())))
                    throw new IllegalStateException(providerName + " emitted an overlapping or unauthorized directory");
                directories.add(directory);
            }
            completeness.putAll(contribution.completeness());
            contentStates.putAll(contribution.contentStates());
            stateOverrides.putAll(contribution.stateOverrides());
            immutableIdentities.putAll(contribution.immutableIdentities());
            observedAt.putAll(contribution.observedAt());
            sourceEffectiveAt.putAll(contribution.sourceEffectiveAt());
            captureLimitations.putAll(contribution.captureLimitations());
        }
        return files;
    }

    private static void validateContribution(
            EvidenceSource source, Set<SourceKind> allowedKinds, EvidenceContribution contribution) {
        Set<SourceKind> reportedKinds =
                new HashSet<>(contribution.completeness().keySet());
        reportedKinds.addAll(contribution.contentStates().keySet());
        reportedKinds.addAll(contribution.immutableIdentities().keySet());
        reportedKinds.addAll(contribution.observedAt().keySet());
        reportedKinds.addAll(contribution.sourceEffectiveAt().keySet());
        reportedKinds.addAll(contribution.captureLimitations().keySet());
        reportedKinds.removeAll(allowedKinds);
        if (!reportedKinds.isEmpty()) {
            throw new IllegalStateException(source.getClass().getSimpleName()
                    + " reported facts for undeclared or unselected sources: " + reportedKinds);
        }
        Set<SourceKind> emittedKinds = contribution.files().keySet().stream()
                .map(source::sourceKindFor)
                .filter(kind -> !allowedKinds.contains(kind))
                .collect(java.util.stream.Collectors.toSet());
        if (!emittedKinds.isEmpty()) {
            throw new IllegalStateException(source.getClass().getSimpleName()
                    + " emitted files for sources outside this capture: " + emittedKinds);
        }
    }

    /** The job behind any review request, or {@code null} for the mentor-chat flow. */
    private static @Nullable AgentJob reviewJob(ContextRequest request) {
        return switch (request) {
            case ContextRequest.PracticeReviewRequest pr -> pr.job();
            case ContextRequest.IssueReviewRequest ir -> ir.job();
            case ContextRequest.ConversationReviewRequest cr -> cr.job();
            case ContextRequest.DocumentReviewRequest dr -> dr.job();
            // Mentor chat is synchronous and has no job. No default branch: a variant added to the sealed
            // type must be a compile error here rather than a silent null.
            case ContextRequest.MentorChatRequest ignored -> null;
        };
    }
}
