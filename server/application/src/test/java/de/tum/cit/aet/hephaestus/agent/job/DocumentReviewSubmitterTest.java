package de.tum.cit.aet.hephaestus.agent.job;

import static de.tum.cit.aet.hephaestus.practices.review.GateDecisionTestFixtures.automaticRun;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.agent.handler.DocumentReviewSubmissionRequest;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactSignal;
import de.tum.cit.aet.hephaestus.integration.core.signal.DiscoveredVia;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.review.GateDecision;
import de.tum.cit.aet.hephaestus.practices.review.ReviewGate;
import de.tum.cit.aet.hephaestus.practices.review.TriggerMode;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A recorded {@code docs.document} signal becoming a review — and every way it can fail to become one,
 * settled under a reason somebody can act on.
 */
class DocumentReviewSubmitterTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 3L;
    private static final long DOCUMENT_ID = 77L;
    private static final SignalName PUBLISHED = SignalName.of("docs.document.published");
    private static final SignalKey KEY =
            new SignalKey(WORKSPACE_ID, DOCUMENT_ID, PUBLISHED, SignalRevision.ofContentDigest("Runbook", "hash-a"));

    @Mock
    private AgentJobService agentJobService;

    @Mock
    private DocumentProjection documentProjection;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ReviewGate gate;

    @Mock
    private SignalRecorder signalRecorder;

    /**
     * Real settling, not a pass-through stub. Every refusal below goes through this template because the
     * recorder is {@code Propagation.MANDATORY} and neither production entry point holds a transaction —
     * so a test that let the submitter call the recorder directly would pass against code that throws in
     * production. Running the callback is what makes these assertions mean anything.
     */
    @Mock
    private TransactionTemplate transactionTemplate;

    private DocumentReviewSubmitter submitter;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        lenient()
                .doAnswer(invocation -> {
                    Consumer<Object> settle = invocation.getArgument(0);
                    settle.accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
        submitter = new DocumentReviewSubmitter(
                agentJobService, documentProjection, workspaceRepository, gate, signalRecorder, transactionTemplate);
        workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
    }

    @Test
    void speaksForTheDocumentKind() {
        assertThat(submitter.artifactKind()).isEqualTo(ArtifactKinds.DOCUMENT);
    }

    @Test
    @DisplayName("a published document with a linked author and a live practice becomes a review")
    void submitsADocumentReview() {
        givenWorkspace();
        givenDocument(document(42L));
        when(gate.evaluateSignal(
                        workspace,
                        PUBLISHED,
                        TriggerMode.AUTO,
                        new ReviewSubject(42L, true),
                        Map.of("state", "ACTIVE")))
                .thenReturn(automaticRun(workspace, List.of()));

        submitter.onDocumentSignal(KEY, DiscoveredVia.EVENT);

        ArgumentCaptor<DocumentReviewSubmissionRequest> request =
                ArgumentCaptor.forClass(DocumentReviewSubmissionRequest.class);
        verify(agentJobService)
                .submit(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.DOCUMENT_REVIEW),
                        request.capture(),
                        eq(KEY),
                        any(GateDecision.Run.class));
        assertThat(request.getValue().documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(request.getValue().aboutUserId()).isEqualTo(42L);
        assertThat(request.getValue().signal()).isEqualTo(PUBLISHED);
        assertThat(request.getValue().revision()).isEqualTo(KEY.revision());
        assertThat(request.getValue().collectionName()).isEqualTo("Engineering");
        assertThat(request.getValue().observationOrigin()).isEqualTo(ObservationOrigin.LIVE);
        verifyNoInteractions(signalRecorder);
    }

    @Test
    @DisplayName("a backfilled signal keeps its population when the reaper re-offers it")
    void resubmitCarriesTheLedgersDiscoveryMode() {
        givenWorkspace();
        givenDocument(document(42L));
        when(gate.evaluateSignal(
                        workspace,
                        PUBLISHED,
                        TriggerMode.AUTO,
                        new ReviewSubject(42L, true),
                        Map.of("state", "ACTIVE")))
                .thenReturn(automaticRun(workspace, List.of()));
        ArtifactSignal signal = new ArtifactSignal();
        signal.setWorkspace(workspace);
        signal.setArtifactKind(ArtifactKinds.DOCUMENT.value());
        signal.setArtifactId(DOCUMENT_ID);
        signal.setSignalName(PUBLISHED.value());
        signal.setRevision(KEY.revision().value());
        signal.setDiscoveredVia(DiscoveredVia.BACKFILL);

        submitter.resubmit(signal);

        ArgumentCaptor<DocumentReviewSubmissionRequest> request =
                ArgumentCaptor.forClass(DocumentReviewSubmissionRequest.class);
        verify(agentJobService)
                .submit(
                        eq(WORKSPACE_ID),
                        eq(AgentJobType.DOCUMENT_REVIEW),
                        request.capture(),
                        any(),
                        any(GateDecision.Run.class));
        assertThat(request.getValue().observationOrigin()).isEqualTo(ObservationOrigin.BACKFILL);
    }

    @Test
    @DisplayName("a document erased while its signal waited is settled, not reviewed")
    void refusesAVanishedDocument() {
        givenWorkspace();
        when(documentProjection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.empty());

        submitter.onDocumentSignal(KEY, DiscoveredVia.EVENT);

        verify(signalRecorder).markRefused(KEY, SignalStateReason.ARTIFACT_GONE);
        verify(agentJobService, never()).submit(any(), any(), any(), any(), any(GateDecision.Run.class));
    }

    @Test
    void refusesATombstonedDocument() {
        givenWorkspace();
        givenDocument(tombstone());

        submitter.onDocumentSignal(KEY, DiscoveredVia.EVENT);

        verify(signalRecorder).markRefused(KEY, SignalStateReason.ARTIFACT_GONE);
    }

    @Test
    @DisplayName("an unlinked author is retryable: linking the account later revives every passed-over doc")
    void refusesAnUnlinkedAuthorRetryably() {
        givenWorkspace();
        givenDocument(document(null));

        submitter.onDocumentSignal(KEY, DiscoveredVia.EVENT);

        verify(signalRecorder).markRefused(KEY, SignalStateReason.SUBJECT_UNLINKED);
        verify(agentJobService, never()).submit(any(), any(), any(), any());
        assertThat(SignalStateReason.SUBJECT_UNLINKED.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("the gate's own reason is the one recorded, so 'turned off' stays distinct from 'nothing bound'")
    void recordsTheGatesReason() {
        givenWorkspace();
        givenDocument(document(42L));
        when(gate.evaluateSignal(
                        workspace,
                        PUBLISHED,
                        TriggerMode.AUTO,
                        new ReviewSubject(42L, true),
                        Map.of("state", "ACTIVE")))
                .thenReturn(new GateDecision.Skip(
                        "every practice bound to this signal is off", SignalStateReason.PRACTICE_AUTONOMY_OFF));

        submitter.onDocumentSignal(KEY, DiscoveredVia.EVENT);

        verify(signalRecorder).markRefused(KEY, SignalStateReason.PRACTICE_AUTONOMY_OFF);
        verify(agentJobService, never()).submit(any(), any(), any(), any());
    }

    @Test
    void refusesASignalWhoseWorkspaceIsGone() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.empty());

        submitter.onDocumentSignal(KEY, DiscoveredVia.EVENT);

        verify(signalRecorder).markRefused(KEY, SignalStateReason.ARTIFACT_GONE);
        verifyNoInteractions(documentProjection, gate, agentJobService);
    }

    private void givenWorkspace() {
        when(workspaceRepository.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
    }

    private void givenDocument(ProjectedDocument document) {
        when(documentProjection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.of(document));
    }

    private static ProjectedDocument document(@Nullable Long createdByMemberId) {
        return new ProjectedDocument(
                "engineering",
                "runbook",
                "Runbook",
                "# Runbook",
                false,
                null,
                null,
                "Alice",
                "outline-alice",
                createdByMemberId,
                "Bob",
                "outline-bob",
                99L,
                List.of(),
                false,
                "Engineering",
                null,
                null);
    }

    private static ProjectedDocument tombstone() {
        return ProjectedDocument.withoutAuthors("engineering", "runbook", "Runbook", null, true);
    }
}
