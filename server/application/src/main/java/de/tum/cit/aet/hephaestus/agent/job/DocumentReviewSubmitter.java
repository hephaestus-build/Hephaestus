package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentReviewTrigger;
import de.tum.cit.aet.hephaestus.agent.handler.DocumentReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.PendingSignalResubmitter;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ConditionalOnProperty(prefix = "hephaestus.agent", name = "enabled", havingValue = "true")
public class DocumentReviewSubmitter implements DocumentReviewTrigger, PendingSignalResubmitter {

    private static final Logger log = LoggerFactory.getLogger(DocumentReviewSubmitter.class);

    private final AgentJobService agentJobService;
    private final DocumentProjection documentProjection;
    private final WorkspaceRepository workspaceRepository;
    private final ReviewGate reviewGate;
    private final SignalRecorder signalRecorder;
    private final TransactionTemplate transactionTemplate;

    public DocumentReviewSubmitter(
            AgentJobService agentJobService,
            DocumentProjection documentProjection,
            WorkspaceRepository workspaceRepository,
            ReviewGate reviewGate,
            SignalRecorder signalRecorder,
            TransactionTemplate transactionTemplate) {
        this.agentJobService = agentJobService;
        this.documentProjection = documentProjection;
        this.workspaceRepository = workspaceRepository;
        this.reviewGate = reviewGate;
        this.signalRecorder = signalRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKinds.DOCUMENT;
    }

    @Override
    public void onDocumentSignal(SignalKey key, DiscoveredVia discoveredVia) {
        submit(key, discoveredVia);
    }

    @Override
    public void resubmit(ArtifactSignal signal) {
        submit(signal.key(), signal.getDiscoveredVia());
    }

    private void submit(SignalKey key, DiscoveredVia discoveredVia) {
        Workspace workspace = workspaceRepository.findById(key.workspaceId()).orElse(null);
        if (workspace == null) {
            refuse(key, SignalStateReason.ARTIFACT_GONE);
            return;
        }

        DocumentProjection.ProjectedDocument document = documentProjection
                .documentById(key.workspaceId(), key.artifactId())
                .filter(found -> !found.deleted())
                .orElse(null);
        if (document == null) {
            log.debug("Document signal has no reviewable document left: documentId={}", key.artifactId());
            refuse(key, SignalStateReason.ARTIFACT_GONE);
            return;
        }

        Long aboutUserId = document.createdByMemberId();
        if (aboutUserId == null || aboutUserId <= 0) {
            log.debug(
                    "Document signal has no linked author to attribute to: documentId={}, subject={}",
                    key.artifactId(),
                    document.createdBySubject());
            refuse(key, SignalStateReason.SUBJECT_UNLINKED);
            return;
        }

        switch (reviewGate.evaluateSignal(
                workspace,
                key.signalName(),
                TriggerMode.AUTO,
                new ReviewSubject(aboutUserId, true),
                java.util.Map.of("state", document.archived() ? "ARCHIVED" : "ACTIVE"))) {
            case GateDecision.Skip skip -> {
                log.debug(
                        "Document signal skipped by practice gate: documentId={}, reason={}",
                        key.artifactId(),
                        skip.reason());
                refuse(key, skip.resolvedSignalReason());
            }
            case GateDecision.Detect detect ->
                agentJobService.submit(
                        detect.workspace().getId(),
                        AgentJobType.DOCUMENT_REVIEW,
                        new DocumentReviewSubmissionRequest(
                                key.artifactId(),
                                document.title(),
                                document.collectionName() != null
                                        ? document.collectionName()
                                        : document.collectionSlug(),
                                aboutUserId,
                                key.signalName(),
                                key.revision(),
                                SignalOrigins.observationOriginOf(discoveredVia)),
                        key,
                        detect);
        }
    }

    private void refuse(SignalKey key, SignalStateReason reason) {
        transactionTemplate.executeWithoutResult(status -> signalRecorder.markRefused(key, reason));
    }
}
