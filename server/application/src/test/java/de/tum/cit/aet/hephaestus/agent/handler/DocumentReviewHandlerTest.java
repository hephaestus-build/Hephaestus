package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextManifestBuilder;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.context.providers.DocumentContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** The document handler: what it stamps on a job, and the repo-less shape of what it prepares. */
class DocumentReviewHandlerTest extends BaseUnitTest {

    private static final SignalName PUBLISHED = SignalName.of("docs.document.published");

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private WorkspaceContextBuilder workspaceContextBuilder;

    @Mock
    private PracticeCatalogInjector practiceCatalogInjector;

    @Mock
    private PracticeDetectionDeliveryService deliveryService;

    private DocumentReviewHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DocumentReviewHandler(
                objectMapper,
                new PracticeReviewPreparation(
                        workspaceContextBuilder, practiceCatalogInjector, new TaskEnvelopeWriter(objectMapper)),
                practiceCatalogInjector,
                new PracticeDetectionResultParser(objectMapper),
                deliveryService);
    }

    private DocumentReviewSubmissionRequest sampleRequest() {
        return new DocumentReviewSubmissionRequest(
                77L,
                "Deployment runbook",
                "Engineering",
                42L,
                PUBLISHED,
                SignalRevision.ofContentDigest("Deployment runbook", "hash-a"),
                ObservationOrigin.LIVE);
    }

    @Test
    void shouldRejectRawOutputThatNeverPassedAdmission() {
        var job = new AgentJob();
        job.setOutput(objectMapper.createObjectNode().put("rawOutput", "unadmitted output"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.deliver(job))
                .isInstanceOf(de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException.class);
        org.mockito.Mockito.verifyNoInteractions(deliveryService);
    }

    @Test
    void shouldDeliverOnlyPersistedVerdictsWithoutParsingRawOutputAgain() {
        var job = new AgentJob();
        job.setMetadata(
                objectMapper.createObjectNode().put(ObservationAdmissionService.DIGEST_METADATA_KEY, "admitted"));
        var output = objectMapper.createObjectNode().put("rawOutput", "not an observation payload");
        output.putObject("feedback").put("admissionDigest", "admitted");
        job.setOutput(output);
        handler.deliver(job);
        org.mockito.Mockito.verify(deliveryService).requirePublished(job);
        org.mockito.Mockito.verifyNoMoreInteractions(deliveryService);
    }

    @Nested
    class CreateSubmission {

        @Test
        void buildsDocumentMetadata() {
            JobSubmission submission = handler.createSubmission(sampleRequest());
            JsonNode metadata = submission.metadata();

            assertThat(metadata.get("artifact_kind").asString()).isEqualTo("docs.document");
            assertThat(metadata.get(DocumentContentSource.DOCUMENT_ID_METADATA_KEY)
                            .asLong())
                    .isEqualTo(77L);
            assertThat(metadata.get("title").asString()).isEqualTo("Deployment runbook");
            assertThat(metadata.get("docs_collection_name").asString()).isEqualTo("Engineering");
            assertThat(metadata.get("about_user_id").asLong()).isEqualTo(42L);
            assertThat(metadata.get(PracticeCatalogInjector.SIGNAL_METADATA_KEY).asString())
                    .isEqualTo("docs.document.published");
            assertThat(metadata.get(PracticeDetectionDeliveryService.ORIGIN_METADATA_KEY)
                            .asString())
                    .isEqualTo("LIVE");
        }

        @Test
        @DisplayName("cooldown scopes on the document, its subject and the occasion — not on the content")
        void idempotencyKeyPutsTheRevisionLast() {
            JobSubmission submission = handler.createSubmission(sampleRequest());

            // AgentJobService.extractCooldownKeyPrefix strips only the trailing segment, so a burst of
            // edits is rate-limited as one subject rather than re-firing on every new digest. Permanent
            // dedup is the ledger's, not this key's.
            String key = submission.idempotencyKey();
            assertThat(key).startsWith("document_review:77:42:published:");
            // extractCooldownKeyPrefix cuts at the LAST colon; asserting the cut here rather than calling
            // it keeps this a unit of the handler while still pinning the contract between the two.
            assertThat(key.substring(0, key.lastIndexOf(':') + 1)).isEqualTo("document_review:77:42:published:");
            assertThat(key.substring(key.lastIndexOf(':') + 1))
                    .isEqualTo(SignalRevision.ofContentDigest("Deployment runbook", "hash-a")
                            .value());
        }

        @Test
        @DisplayName("the revision is colon-free, so the cooldown prefix cannot be cut in the wrong place")
        void revisionCarriesNoSegmentSeparator() {
            String key = handler.createSubmission(sampleRequest()).idempotencyKey();

            assertThat(key.chars().filter(c -> c == ':').count()).isEqualTo(4);
        }

        @Test
        void omitsACollectionNameItDoesNotHave() {
            JobSubmission submission = handler.createSubmission(new DocumentReviewSubmissionRequest(
                    77L,
                    "Untitled",
                    null,
                    42L,
                    PUBLISHED,
                    SignalRevision.ofTerminalState("archived"),
                    ObservationOrigin.LIVE));

            assertThat(submission.metadata().has("docs_collection_name")).isFalse();
        }

        @Test
        void rejectsWrongRequestType() {
            assertThatThrownBy(() -> handler.createSubmission(new WrongRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected DocumentReviewSubmissionRequest");
        }
    }

    private record WrongRequest() implements de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest {}

    @Nested
    class RepoLessExecution {

        private AgentJob documentJob() {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());
            var workspace = new Workspace();
            workspace.setId(1L);
            job.setWorkspace(workspace);
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("artifact_kind", "docs.document");
            metadata.put(DocumentContentSource.DOCUMENT_ID_METADATA_KEY, 77L);
            metadata.put("about_user_id", 42L);
            metadata.put(PracticeCatalogInjector.SIGNAL_METADATA_KEY, PUBLISHED.value());
            job.setMetadata(metadata);
            return job;
        }

        @Test
        @DisplayName("no clone, no diff, no SCM mount — one document and a task")
        void prepareInputsWritesOnlyTheDocumentAndTheTask() {
            AgentJob job = documentJob();
            Practice practice = new Practice();
            practice.setSlug("keeps-linked-docs-consistent");
            practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.DOCUMENT));
            practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.DOCUMENT));
            var revision = new PracticeRevision();
            ReflectionTestUtils.setField(revision, "id", 12L);
            practice.setCurrentRevision(revision);
            when(practiceCatalogInjector.resolveEligiblePractices(job, ArtifactKinds.DOCUMENT))
                    .thenReturn(List.of(practice));
            when(workspaceContextBuilder.prepare(any(), any()))
                    .thenReturn(new PreparedEvidence(
                            Map.of(SandboxLayout.CONTEXT_PREFIX + "document.md", "# Runbook".getBytes()),
                            mock(ArtifactSourceManifest.class)));
            when(workspaceContextBuilder.prepareAutomatedReviewReadiness(
                            any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(new ContextManifestBuilder.PreparedAutomatedReviewReadiness(
                            List.of(practice), mock(AutomatedReviewReadinessReport.class)));

            Map<String, byte[]> files = handler.prepareInputs(job).files();

            assertThat(files).containsKey(SandboxLayout.CONTEXT_PREFIX + "document.md");
            assertThat(files).containsKey(SandboxLayout.TASK_ENVELOPE_FILENAME);
            assertThat(files).doesNotContainKey(SandboxLayout.SCM_SOURCE_KEEP);
            assertThat(files.keySet()).noneMatch(k -> k.startsWith(SandboxLayout.SOURCES_PREFIX));
        }
    }

    @Nested
    class PrepareObservations {

        private static final String OBSERVATION = """
            [{
              "practiceSlug": "explains-why",
              "summary": "States the motivation",
              "assessmentStatus": "ASSESSED", "presence": "PRESENT",
              "assessment": "GOOD",
              "severity": null,
              "evidenceRationale": "The text says why.",
              "evidence": {}
            }]
            """;

        @Test
        void shouldRefuseRatherThanFailWhenNothingSubmittedIsAnObservation() {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());

            assertThatThrownBy(
                            () -> handler.prepareObservations(job, objectMapper.readTree("[{\"practiceSlug\": \"\"}]")))
                    .isInstanceOfSatisfying(
                            de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException.class,
                            e -> assertThat(e.reasonCode()).isEqualTo("no_valid_observations"));
            org.mockito.Mockito.verifyNoInteractions(deliveryService);
        }

        @Test
        void shouldRecordThroughTheDeliveryServiceOnlyWhenAsked() {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());
            var admissible = mock(PracticeDetectionDeliveryService.PreparedObservations.class);
            when(deliveryService.prepare(org.mockito.ArgumentMatchers.eq(job), any()))
                    .thenReturn(admissible);

            var prepared = handler.prepareObservations(job, objectMapper.readTree(OBSERVATION));
            org.mockito.Mockito.verify(deliveryService, org.mockito.Mockito.never())
                    .publish(any(), any());

            prepared.record(job);
            org.mockito.Mockito.verify(deliveryService).publish(job, admissible);
        }
    }
}
