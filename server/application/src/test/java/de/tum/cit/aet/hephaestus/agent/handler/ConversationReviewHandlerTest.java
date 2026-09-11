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
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Unit tests for the conversation-detection handler: pure submission logic plus the repo-less spike
 * assertions (no SCM source mount, empty volume mounts).
 */
class ConversationReviewHandlerTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private WorkspaceContextBuilder workspaceContextBuilder;

    @Mock
    private PracticeCatalogInjector practiceCatalogInjector;

    @Mock
    private PracticeDetectionDeliveryService deliveryService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ConversationReviewHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ConversationReviewHandler(
                objectMapper,
                workspaceContextBuilder,
                new TaskEnvelopeWriter(objectMapper),
                practiceCatalogInjector,
                new PracticeDetectionResultParser(objectMapper),
                deliveryService,
                eventPublisher,
                transactionTemplate);
    }

    private ConversationReviewSubmissionRequest sampleRequest() {
        return new ConversationReviewSubmissionRequest(
                555L, "C0ABC", "engineering", "1700000000.100000", 42L, "1700000900.500000");
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
        void buildsConversationMetadata() {
            JobSubmission submission = handler.createSubmission(sampleRequest());
            JsonNode metadata = submission.metadata();

            assertThat(metadata.get("artifact_kind").asString()).isEqualTo("chat.conversation_thread");
            assertThat(metadata.get("slack_thread_id").asLong()).isEqualTo(555L);
            assertThat(metadata.get("slack_channel_id").asString()).isEqualTo("C0ABC");
            assertThat(metadata.get("slack_channel_name").asString()).isEqualTo("engineering");
            assertThat(metadata.get("slack_thread_ts").asString()).isEqualTo("1700000000.100000");
            assertThat(metadata.get("slack_last_ts").asString()).isEqualTo("1700000900.500000");
            assertThat(metadata.get("about_user_id").asLong()).isEqualTo(42L);
        }

        @Test
        void idempotencyKeyCooldownScopesOnThreadPlusSubjectNotFreshness() {
            // The trailing segment is the disposable freshness (lastTs) that extractCooldownKeyPrefix strips.
            JobSubmission submission = handler.createSubmission(sampleRequest());
            assertThat(submission.idempotencyKey())
                    .isEqualTo("conversation_review:C0ABC:1700000000.100000:42:1700000900.500000");
        }

        @Test
        void rejectsWrongRequestType() {
            assertThatThrownBy(() -> handler.createSubmission(new WrongRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected ConversationReviewSubmissionRequest");
        }
    }

    private record WrongRequest() implements de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest {}

    @Nested
    class RepoLessExecution {

        private AgentJob conversationJob() {
            var job = new AgentJob();
            job.setId(java.util.UUID.randomUUID());
            var workspace = new Workspace();
            workspace.setId(1L);
            job.setWorkspace(workspace);
            ObjectNode metadata = objectMapper.createObjectNode();
            metadata.put("artifact_kind", "chat.conversation_thread");
            metadata.put("slack_channel_id", "C0ABC");
            metadata.put("slack_thread_ts", "1700000000.100000");
            metadata.put("about_user_id", 42L);
            job.setMetadata(metadata);
            return job;
        }

        @Test
        void prepareInputsWritesNoScmSourceAndOnlyContextPlusTask() {
            AgentJob job = conversationJob();
            Practice practice = new Practice();
            practice.setSlug("conversation-practice");
            practice.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.CONVERSATION_THREAD));
            practice.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.CONVERSATION_THREAD));
            var revision = new PracticeRevision();
            ReflectionTestUtils.setField(revision, "id", 12L);
            practice.setCurrentRevision(revision);
            when(practiceCatalogInjector.resolveEligiblePractices(job, ArtifactKinds.CONVERSATION_THREAD))
                    .thenReturn(List.of(practice));
            when(workspaceContextBuilder.prepare(any(), any()))
                    .thenReturn(new PreparedEvidence(
                            Map.of(
                                    SandboxLayout.CONTEXT_PREFIX + "conversation_thread.json",
                                    "{\"messages\":[]}".getBytes()),
                            org.mockito.Mockito.mock(ArtifactSourceManifest.class)));
            when(workspaceContextBuilder.prepareAutomatedReviewReadiness(
                            any(), any(), anyString(), any(), any(), any()))
                    .thenReturn(new ContextManifestBuilder.PreparedAutomatedReviewReadiness(
                            List.of(practice), mock(AutomatedReviewReadinessReport.class)));

            Map<String, byte[]> files = handler.prepareInputs(job).files();

            assertThat(files).containsKey(SandboxLayout.CONTEXT_PREFIX + "conversation_thread.json");
            assertThat(files).containsKey(SandboxLayout.TASK_ENVELOPE_FILENAME);
            assertThat(files).doesNotContainKey(SandboxLayout.SCM_SOURCE_KEEP);
            assertThat(files.keySet()).noneMatch(k -> k.startsWith(SandboxLayout.SOURCES_PREFIX));
        }
    }
}
