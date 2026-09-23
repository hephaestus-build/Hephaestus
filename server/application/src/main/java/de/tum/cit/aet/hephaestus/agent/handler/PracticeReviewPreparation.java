package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.InsufficientEvidenceException;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.ReviewChange;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedJobInputs;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelope;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PracticeReviewPreparation {

    private static final Logger log = LoggerFactory.getLogger(PracticeReviewPreparation.class);

    private final WorkspaceContextBuilder workspaceContextBuilder;
    private final PracticeCatalogInjector practiceCatalogInjector;
    private final TaskEnvelopeWriter taskEnvelopeWriter;
    private final GitRepositoryManager gitRepositoryManager;

    PracticeReviewPreparation(
            WorkspaceContextBuilder workspaceContextBuilder,
            PracticeCatalogInjector practiceCatalogInjector,
            TaskEnvelopeWriter taskEnvelopeWriter,
            GitRepositoryManager gitRepositoryManager) {
        this.workspaceContextBuilder = workspaceContextBuilder;
        this.practiceCatalogInjector = practiceCatalogInjector;
        this.taskEnvelopeWriter = taskEnvelopeWriter;
        this.gitRepositoryManager = gitRepositoryManager;
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
        SignalName signal = PracticeCatalogInjector.signalOf(job);
        List<Practice> eligible = practiceCatalogInjector.resolveEligiblePractices(job, artifactKind);
        PreparedEvidence prepared = workspaceContextBuilder.prepare(request, EvidencePlan.compile(eligible));
        try {
            ArtifactSourceManifest manifest = Objects.requireNonNull(prepared.manifest(), "manifest");
            var readiness = workspaceContextBuilder.prepareAutomatedReviewReadiness(
                    manifest,
                    eligible,
                    job.getCreatedAt(),
                    signal,
                    prepared.files(),
                    ReviewChange.of(gitRepositoryManager, request));
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
                        new PreparedJobInputs(prepared, readiness.report()));
            }
            Map<String, byte[]> files = new LinkedHashMap<>(prepared.files());
            files.put(SandboxLayout.TASK_ENVELOPE_FILENAME, taskEnvelopeWriter.write(envelope.get()));
            practiceCatalogInjector.inject(files, job, artifactKind, ready);
            staging.accept(files);
            return new PreparedJobInputs(prepared.withFiles(files), readiness.report());
        } catch (InsufficientEvidenceException refused) {
            throw refused;
        } catch (RuntimeException exception) {
            prepared.close();
            throw exception;
        }
    }
}
