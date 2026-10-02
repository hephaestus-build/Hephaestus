package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.JobFolderIndex;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewChange;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.GeneratedPathReviewDTO;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class PracticeReviewPreparation {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Logger log = LoggerFactory.getLogger(PracticeReviewPreparation.class);

    private final WorkspaceContextBuilder workspaceContextBuilder;
    private final PracticeCatalogInjector practiceCatalogInjector;
    private final TaskEnvelopeWriter taskEnvelopeWriter;
    private final GitRepositoryManager gitRepositoryManager;
    private final JobEvidenceFiles evidenceFiles;

    PracticeReviewPreparation(
            WorkspaceContextBuilder workspaceContextBuilder,
            PracticeCatalogInjector practiceCatalogInjector,
            TaskEnvelopeWriter taskEnvelopeWriter,
            GitRepositoryManager gitRepositoryManager,
            JobEvidenceFiles evidenceFiles) {
        this.workspaceContextBuilder = workspaceContextBuilder;
        this.practiceCatalogInjector = practiceCatalogInjector;
        this.taskEnvelopeWriter = taskEnvelopeWriter;
        this.gitRepositoryManager = gitRepositoryManager;
        this.evidenceFiles = evidenceFiles;
    }

    /**
     * @param envelope built only once at least one practice is ready, since the run it describes exists
     *     only then
     * @param staging what the handler adds beside the catalog, such as the composition request
     * @throws InsufficientEvidenceException when no practice is ready; the evidence travels with it for
     *     the executor to record and release
     */
    PreparedJobInputs prepare(
            AgentJob job,
            ArtifactKind artifactKind,
            ContextRequest request,
            Supplier<TaskEnvelope> envelope,
            Consumer<Map<String, byte[]>> staging) {
        evidenceFiles.beginPersonCapture(job);
        try {
            return prepareCaptured(job, artifactKind, request, envelope, staging);
        } catch (InsufficientEvidenceException refused) {
            throw refused;
        } catch (RuntimeException failure) {
            evidenceFiles.abortPersonCapture(job);
            throw failure;
        }
    }

    private PreparedJobInputs prepareCaptured(
            AgentJob job,
            ArtifactKind artifactKind,
            ContextRequest request,
            Supplier<TaskEnvelope> envelope,
            Consumer<Map<String, byte[]>> staging) {
        List<Practice> eligible = practiceCatalogInjector.resolveEligiblePractices(job, artifactKind);
        PreparedEvidence prepared = workspaceContextBuilder.prepare(request, EvidencePlan.compile(eligible));
        try {
            JobFolderIndex manifest = Objects.requireNonNull(prepared.manifest(), "manifest");
            var change = ReviewChange.of(gitRepositoryManager, request);
            var readiness = workspaceContextBuilder.prepareAutomatedReviewReadiness(
                    manifest,
                    eligible,
                    job.getCreatedAt(),
                    practice -> PracticeCatalogInjector.occasionOf(job, practice.getSlug()),
                    prepared.files(),
                    change);
            List<Practice> ready = readiness.readyPractices();
            if (ready.size() < eligible.size()) {
                log.info(
                        "Not asking {} of {} practice(s): jobId={}, skipped={}",
                        eligible.size() - ready.size(),
                        eligible.size(),
                        job.getId(),
                        readiness.report().decisions().stream()
                                .filter(decision -> !decision.ready())
                                .map(decision -> decision.practiceSlug() + decision.reasonCodes())
                                .toList());
            }
            if (ready.isEmpty()) {
                throw new InsufficientEvidenceException(
                        "No practice has sufficient evidence: jobId=" + job.getId(),
                        evidenceFiles.prepare(job, prepared, readiness.report()));
            }
            Map<String, byte[]> files = new LinkedHashMap<>(prepared.files());
            if (artifactKind.equals(ArtifactKinds.PULL_REQUEST) && change != null) {
                var metadata = java.util.Objects.requireNonNull(job.getMetadata());
                var patterns = java.util.stream.StreamSupport.stream(
                                metadata.path("generated_path_patterns").spliterator(), false)
                        .map(JsonNode::asString)
                        .toList();
                var policy = GeneratedPathReviewDTO.of(patterns, change.changedPaths());
                files.put(GeneratedPathReviewDTO.INPUT_PATH, JSON.writeValueAsBytes(policy));
            }
            files.put(SandboxLayout.TASK_ENVELOPE_FILENAME, taskEnvelopeWriter.write(envelope.get()));
            practiceCatalogInjector.inject(files, job, artifactKind, ready);
            staging.accept(files);
            return evidenceFiles.prepare(job, prepared.withFiles(files), readiness.report());
        } catch (InsufficientEvidenceException refused) {
            throw refused;
        } catch (RuntimeException exception) {
            prepared.close();
            throw exception;
        }
    }
}
