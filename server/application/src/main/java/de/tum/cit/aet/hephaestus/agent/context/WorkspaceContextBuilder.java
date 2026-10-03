package de.tum.cit.aet.hephaestus.agent.context;

import de.tum.cit.aet.hephaestus.agent.context.providers.WorkspaceFolderRenderer;
import de.tum.cit.aet.hephaestus.agent.gateway.WorkspaceBudgetExceededException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.metrics.AgentMetrics;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCapture;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    private final @Nullable JobFolderIndexBuilder manifestBuilder;

    public WorkspaceContextBuilder(
            List<ContentSource> providers,
            MeterRegistry meterRegistry,
            @Nullable JobFolderIndexBuilder manifestBuilder) {
        List<ContentSource> sorted = new ArrayList<>(providers);
        AnnotationAwareOrderComparator.sort(sorted);
        // The folder snapshots the primary capture, so it must follow every other source.
        sorted.sort(Comparator.comparing(provider -> provider instanceof WorkspaceFolderRenderer));
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

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public PreparedEvidence prepare(ContextRequest request, EvidencePlan evidencePlan) {
        long startNs = System.nanoTime();
        try {
            BuildResult result = buildInputs(request, evidencePlan);
            var prepared = new PreparedEvidence(
                    result.files(), result.filesOnDisk(), result.cleanups(), result.manifest(), result.directories());
            if (prepared.manifest() == null) {
                prepared.close();
                throw new IllegalStateException("Review evidence was prepared without a source manifest");
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
     * @param change the reviewed change, for the subjects declared over it; null leaves them undecided
     */
    public JobFolderIndexBuilder.PreparedAutomatedReviewReadiness prepareAutomatedReviewReadiness(
            JobFolderIndex manifest,
            List<Practice> practices,
            Instant temporalAnchor,
            Map<String, byte[]> staged,
            @Nullable ReviewChange change) {
        if (manifestBuilder == null) {
            throw new IllegalStateException("Evidence readiness requires a manifest builder");
        }
        return manifestBuilder.prepareAutomatedReviewReadiness(manifest, practices, temporalAnchor, staged, change);
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
            Map<String, Path> filesOnDisk,
            List<EvidenceDirectory> directories,
            List<AutoCloseable> cleanups,
            @Nullable JobFolderIndex manifest) {}

    private BuildResult buildInputs(ContextRequest request, @Nullable EvidencePlan evidencePlan) {
        // Capture scope follows the source contract, not the practices later selected by readiness.
        Set<SourceKind> stagedSources = Set.of();
        if (evidencePlan != null) {
            if (manifestBuilder == null) {
                throw new IllegalStateException("Review evidence capture requires a source manifest builder");
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
        Map<String, Path> filesOnDisk = new LinkedHashMap<>();
        List<EvidenceDirectory> directories = new ArrayList<>();
        List<AutoCloseable> cleanups = new ArrayList<>();
        List<WorkspaceRefusal> refusals = new ArrayList<>();
        try {
            int contributed = 0;
            JobFolderIndex primary = null;
            for (ContentSource provider : providers) {
                if (!provider.supports(request)) {
                    continue;
                }
                if (evidencePlan != null && !(provider instanceof EvidenceSource)) {
                    throw new IllegalStateException("Review context provider must declare source kinds: "
                            + provider.getClass().getSimpleName());
                }
                if (provider instanceof WorkspaceFolderRenderer && evidencePlan != null && manifestBuilder != null) {
                    AgentJob job = reviewJob(request);
                    if (job == null) throw new IllegalStateException("Review folder has no job");
                    primary = manifestBuilder.augment(
                            new LinkedHashMap<>(files),
                            filesOnDisk,
                            keySourceKind,
                            job.getId().toString(),
                            evidencePlan,
                            new JobFolderIndexBuilder.CaptureMetadata(
                                    completeness,
                                    contentStates,
                                    immutableIdentities,
                                    observedAt,
                                    sourceEffectiveAt,
                                    stateOverrides,
                                    captureLimitations,
                                    attemptedKinds));
                }
                String providerName = provider.getClass().getSimpleName();
                Map<String, byte[]> contributionFiles;
                if (evidencePlan != null && provider instanceof EvidenceSource evidenceSource) {
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
                            cleanups,
                            refusals);
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
                        throw new IllegalStateException(providerName + " emitted undocumented review input " + key);
                    }
                    if (value == null) {
                        // Disk-staged content has no in-memory value.
                        continue;
                    }
                    files.put(key, value.clone());
                }
                contributed++;
            }
            JobFolderIndex manifest = null;
            if (manifestBuilder != null && evidencePlan != null) {
                AgentJob job = reviewJob(request);
                if (job != null) {
                    manifest = manifestBuilder.augment(
                            files,
                            filesOnDisk,
                            keySourceKind,
                            String.valueOf(job.getId()),
                            evidencePlan,
                            new JobFolderIndexBuilder.CaptureMetadata(
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
            if (manifest != null && manifestBuilder != null) {
                if (request instanceof ContextRequest.PracticeReviewRequest
                        && directories.stream().noneMatch(d -> d.target().equals(SandboxLayout.REPO_MOUNT_RELATIVE))) {
                    refusals.add(new WorkspaceRefusal(
                            WorkspaceRefusal.Target.REPOSITORY, "reviewed", SourceAbsenceReason.NO_WORKING_COPY));
                }
                manifest = new JobFolderIndex(
                        manifest.contractVersion(),
                        manifest.catalogDigest(),
                        manifest.artifactKind(),
                        manifest.capturedAt(),
                        primary == null ? manifest.sources() : mergeReadinessSources(primary, manifest),
                        refusals,
                        manifestBuilder.folderArtifacts(files, filesOnDisk, keySourceKind));
                manifestBuilder.writeIndex(files, manifest, directories);
            }
            log.debug(
                    "Workspace context built: {} files ({} staged from disk) from {} provider(s)",
                    files.size() + filesOnDisk.size(),
                    filesOnDisk.size(),
                    contributed);
            return new BuildResult(files, filesOnDisk, directories, cleanups, manifest);
        } catch (RuntimeException exception) {
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
            Map<String, Path> filesOnDisk,
            List<EvidenceDirectory> directories,
            List<AutoCloseable> cleanups,
            List<WorkspaceRefusal> refusals) {
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
        boolean folder = source instanceof WorkspaceFolderRenderer;
        boolean capturedFolder = false;
        for (SourceKind kind : source.sourceKinds()) {
            if (!selectedKinds.contains(kind)) continue;
            if (folder && capturedFolder) break;
            capturedFolder = folder;
            Set<SourceKind> collecting = folder ? selectedKinds : Set.of(kind);
            attemptedKinds.addAll(collecting);
            EvidenceContribution contribution;
            try {
                contribution = source.capture(request, collecting);
            } catch (RuntimeException e) {
                if (e instanceof WorkspaceBudgetExceededException) throw e;
                if (folder) throw new JobPreparationException("Workspace folder rendering failed", e);
                // Collector failures affect only their source; contribution validation failures propagate.
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
            refusals.addAll(contribution.refusals());
            validateContribution(source, collecting, contribution);
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

    /** Workspace-wide sources come from the folder; other work cannot repair missing reviewed-work evidence. */
    private static List<SourceCapture> mergeReadinessSources(JobFolderIndex primary, JobFolderIndex folder) {
        var workspaceSources = Set.of("outline.documents", "workspace.project-inventory");
        var rendered = folder.sources().stream().collect(Collectors.toMap(SourceCapture::kind, Function.identity()));
        return primary.sources().stream()
                .map(source -> workspaceSources.contains(source.kind().value())
                        ? Objects.requireNonNull(rendered.get(source.kind()))
                        : source)
                .toList();
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
                .collect(Collectors.toSet());
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
            // Synchronous mentor chat has no job.
            case ContextRequest.MentorChatRequest ignored -> null;
        };
    }
}
