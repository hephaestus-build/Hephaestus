package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.catalog.ResolvedLlmModel;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding;
import de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBindingRepository;
import de.tum.cit.aet.hephaestus.agent.handler.JobTypeHandlerRegistry;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.core.security.EncryptedStringConverter;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRecorder;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals;
import de.tum.cit.aet.hephaestus.observability.StructuredLogKeys;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import jakarta.persistence.Convert;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class AgentJobServiceTest extends BaseUnitTest {
    @org.junit.jupiter.api.BeforeEach
    void allowMemberAiForUnrelatedScenarios() {
        org.mockito.Mockito.lenient()
                .when(memberAiPolicy.permitsReview(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient()
                .when(memberAiPolicy.allowsResult(org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        org.mockito.Mockito.lenient()
                .when(memberAiPolicy.binding(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> agentBindingRepository
                        .findByWorkspaceIdAndPurposeWithModels(
                                invocation.getArgument(0),
                                de.tum.cit.aet.hephaestus.agent.config.AgentPurpose.PRACTICE_REVIEW)
                        .filter(de.tum.cit.aet.hephaestus.agent.config.WorkspaceAgentBinding::isEnabled));
        var handler = org.mockito.Mockito.mock(JobTypeHandler.class);
        org.mockito.Mockito.lenient()
                .when(handlerRegistry.getHandler(org.mockito.ArgumentMatchers.any()))
                .thenReturn(handler);
        org.mockito.Mockito.lenient()
                .when(handler.createSubmission(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(ignored -> createSubmission());
    }

    @org.mockito.Mock
    private de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy memberAiPolicy;

    @Mock
    private AgentJobRepository agentJobRepository;

    @Mock
    private WorkspaceAgentBindingRepository agentBindingRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private ConnectionService connectionService;

    @Mock
    private JobTypeHandlerRegistry handlerRegistry;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private de.tum.cit.aet.hephaestus.agent.usage.LlmBudgetService llmBudgetService;

    @Mock
    private LlmModelResolver llmModelResolver;

    @Mock
    private SignalRecorder signalRecorder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentJobService service;

    private Workspace workspace;
    private WorkspaceAgentBinding enabledBinding;

    @BeforeEach
    void setUp() {
        service = new AgentJobService(
                agentJobRepository,
                memberAiPolicy,
                workspaceRepository,
                connectionService,
                handlerRegistry,
                objectMapper,
                transactionTemplate,
                new PracticeReviewProperties(false, 15, 5, false, false),
                practiceRepository,
                llmBudgetService,
                llmModelResolver,
                signalRecorder);

        workspace = new Workspace();
        workspace.setId(1L);
        workspace.setWorkspaceSlug("test-ws");
        workspace.setStatus(Workspace.WorkspaceStatus.ACTIVE);
        workspace.getFeatures().setPracticesEnabled(true);
        lenient().when(workspaceRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(workspace));
        // One pull-request practice that says nothing about its own autonomy, so it inherits the workspace's —
        // and the workspace has expressed no opinion either, so the chain bottoms out at AUTOMATIC, which
        // admits a review.
        lenient()
                .when(practiceRepository.findAutonomyRows(anyLong()))
                .thenReturn(List.of(tierRow(null, ArtifactKinds.PULL_REQUEST)));

        enabledBinding = new WorkspaceAgentBinding();
        enabledBinding.setId(10L);
        enabledBinding.setWorkspace(workspace);
        enabledBinding.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        enabledBinding.setEnabled(true);
        enabledBinding.setTimeoutSeconds(600);
        // Two lookups, deliberately: submit() discovers the binding WITH its models (it needs the
        // funding source to pick the right cap), then re-reads it inside the write transaction.
        lenient()
                .when(agentBindingRepository.findByWorkspaceIdAndPurposeWithModels(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(Optional.of(enabledBinding));
        lenient()
                .when(agentBindingRepository.findByWorkspaceIdAndPurpose(1L, AgentPurpose.PRACTICE_REVIEW))
                .thenReturn(Optional.of(enabledBinding));

        lenient()
                .when(llmModelResolver.resolve(any()))
                .thenReturn(new ResolvedLlmModel(
                        "https://api.anthropic.com", "anthropic-messages", "claude-sonnet-4", null, null, false));
        lenient()
                .when(llmModelResolver.connectionRef(any()))
                .thenReturn(new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 99L, null, null));
    }

    /**
     * One row of {@link PracticeRepository#findAutonomyRows} — a practice's raw autonomy column and its
     * group's, ungrouped here so the chain runs practice → workspace.
     */
    private static PracticeRepository.PracticeAutonomyRow tierRow(
            @Nullable PracticeAutonomy practiceAutonomy, ArtifactKind artifactKind) {
        return new PracticeRepository.PracticeAutonomyRow() {
            @Override
            public @Nullable PracticeAutonomy getPracticeAutonomy() {
                return practiceAutonomy;
            }

            @Override
            public @Nullable PracticeAutonomy getGroupAutonomy() {
                return null;
            }

            @Override
            public @Nullable Long getGroupId() {
                return null;
            }

            @Override
            public ArtifactKind getArtifactKind() {
                return artifactKind;
            }
        };
    }

    private JobSubmission createSubmission() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("pr_number", 42);
        // 5-segment key grammar: <type>:<nameWithOwner>:<number>:<phase>:<freshness>
        // (PullRequestReviewHandler emits the trigger-event phase before the head SHA).
        return new JobSubmission(metadata, "pr_review:owner/repo:42:authoring:abc123");
    }

    @AfterEach
    void clearTraceContext() {
        MDC.remove(StructuredLogKeys.TRACE_ID);
    }

    @Nested
    class Submit {

        @BeforeEach
        @SuppressWarnings("unchecked")
        void setUpSubmit() {
            // Make transactionTemplate.execute() actually invoke the callback
            // (submitForConfig uses transactionTemplate.execute() for per-config isolation)
            lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
        }

        @Test
        void shouldRefuseNewReviewsWhenDeveloperHasChosenNoAi() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            when(memberAiPolicy.permitsReview(eq(1L), eq(AgentJobType.PULL_REQUEST_REVIEW), any()))
                    .thenReturn(false);
            assertThat(service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null))
                    .isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldReturnEmptyWhenBindingIsDisabled() {
            enabledBinding.setEnabled(false);
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldReturnEmptyWhenPracticeIsUnbound() {
            when(agentBindingRepository.findByWorkspaceIdAndPurposeWithModels(1L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.empty());
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldNotSubmitAfterWorkspaceLeavesActiveStatus() {
            workspace.setStatus(Workspace.WorkspaceStatus.PURGED);
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldNotSubmitAfterPracticeReviewsAreTurnedOff() {
            workspace.getFeatures().setPracticesEnabled(false);
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldNotSubmitWithoutAnActivePracticeForTheReviewedWork() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            // The workspace has a conversation practice, and it is switched OFF — the case that has to read
            // differently from "nothing is bound to this work at all", and the reason the autonomy survives to
            // the JVM instead of being filtered away in SQL.
            when(practiceRepository.findAutonomyRows(1L))
                    .thenReturn(List.of(tierRow(PracticeAutonomy.OFF, ArtifactKinds.CONVERSATION_THREAD)));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.CONVERSATION_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.CONVERSATION_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        /**
         * Asserted through the outcome rather than a {@code verify}: only the payer named here is given
         * an exhausted cap, so the job survives if and only if the service consulted the OTHER purse.
         */
        @ParameterizedTest(name = "a binding on {1}''s model is paused by {1}''s exhausted cap, and only by it")
        @CsvSource({"true, INSTANCE, WORKSPACE", "false, WORKSPACE, INSTANCE"})
        void shouldCheckTheBudgetOfWhoeverFundsTheBoundDetectionModel(
                boolean boundToAnInstanceModel, FundingSource payer, FundingSource otherPurse) {
            // An instance model on the binding = the host's shared models pay; none = the workspace's
            // own connected provider does.
            if (boundToAnInstanceModel) {
                enabledBinding.setInstanceModel(new de.tum.cit.aet.hephaestus.agent.catalog.LlmModel());
            }
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            // Exactly one purse is out of money. Mockito's default for the other is `false` (not blocked).
            when(llmBudgetService.blockSubmission(any(), any(), eq(payer))).thenReturn(true);

            JobTypeHandler handler = mock(JobTypeHandler.class);
            lenient()
                    .when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW))
                    .thenReturn(handler);
            lenient().when(handler.createSubmission(any())).thenReturn(createSubmission());
            lenient()
                    .when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());
            lenient().when(agentJobRepository.saveAndFlush(any(AgentJob.class))).thenAnswer(inv -> {
                AgentJob j = inv.getArgument(0);
                j.prePersist();
                return j;
            });

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result)
                    .as("the payer's cap is exhausted, so no job may be created")
                    .isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any(AgentJob.class));
            verify(llmBudgetService, never()).blockSubmission(any(), any(), eq(otherPurse));
        }

        @Test
        void shouldSubmitNothingWhenThePayersBudgetIsBlocked() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            when(llmBudgetService.blockSubmission(
                            any(), any(), eq(de.tum.cit.aet.hephaestus.agent.usage.FundingSource.WORKSPACE)))
                    .thenReturn(true);

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldSubmitNothingWhenBoundModelIsRevoked() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            when(llmModelResolver.resolve(enabledBinding)).thenThrow(new IllegalStateException("model unavailable"));

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldReturnExistingJobOnIdempotencyMatch() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());

            AgentJob existingJob = new AgentJob();
            existingJob.prePersist(); // generates ID
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(
                            eq(1L), eq("pr_review:owner/repo:42:authoring:abc123:detection"), any()))
                    .thenReturn(Optional.of(existingJob));

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(existingJob.getId());
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldCreateAQueuedJobWithItsPurposeIdempotencyKeyAndFrozenSnapshot() {
            MDC.put(StructuredLogKeys.TRACE_ID, "0123456789abcdef0123456789abcdef");
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());

            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());

            when(agentJobRepository.saveAndFlush(any(AgentJob.class))).thenAnswer(inv -> {
                AgentJob j = inv.getArgument(0);
                j.prePersist(); // simulate @PrePersist generating UUID + token
                return j;
            });

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isPresent();
            AgentJob job = result.get();
            assertThat(job.getWorkspace()).isEqualTo(workspace);
            assertThat(job.getPurpose()).isEqualTo(AgentPurpose.PRACTICE_REVIEW);
            assertThat(job.getJobType()).isEqualTo(AgentJobType.PULL_REQUEST_REVIEW);
            assertThat(job.getIdempotencyKey()).isEqualTo("pr_review:owner/repo:42:authoring:abc123:detection");
            assertThat(job.getConfigSnapshot()).isNotNull();
            assertThat(job.getStatus()).isEqualTo(AgentJobStatus.QUEUED);
            assertThat(job.getTraceId()).isEqualTo("0123456789abcdef0123456789abcdef");
        }

        @Test
        @DisplayName("the credential is NEVER frozen onto the job — one path, resolved live by the proxy")
        void neverCopiesTheCredentialOntoTheJob() {
            // Asserted on the entity's shape rather than on one submitted job's field being null: the
            // job has nowhere to put a provider credential at all, which a future write path cannot
            // undo by forgetting to leave a field unset.
            assertThat(AgentJob.class.getDeclaredFields())
                    .filteredOn(field -> field.isAnnotationPresent(Convert.class)
                            && field.getAnnotation(Convert.class).converter() == EncryptedStringConverter.class)
                    .describedAs("the job's own bearer token is the ONLY secret an agent_job row may carry")
                    .extracting(java.lang.reflect.Field::getName)
                    .containsExactly("jobToken");
        }

        @Test
        void shouldReturnEmptyOnDataIntegrityViolation() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());

            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(
                            eq(1L), eq("pr_review:owner/repo:42:authoring:abc123:detection"), any()))
                    .thenReturn(Optional.empty());

            when(agentJobRepository.saveAndFlush(any()))
                    .thenThrow(new DataIntegrityViolationException("uk_agent_job_idempotency"));

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
        }

        @Test
        void shouldSkipSubmissionWhenCooldownActive() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());

            AgentJob recent = new AgentJob();
            recent.prePersist();
            when(agentJobRepository.findRecentJobByKeyPrefix(eq(1L), any(), any()))
                    .thenReturn(Optional.of(recent));

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEmpty();
            verify(agentJobRepository, never()).saveAndFlush(any());
        }

        @Test
        void shouldPersistTheAdmittedRevisionWithoutMutatingHandlerMetadata() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            var submission = createSubmission();
            when(handler.createSubmission(any())).thenReturn(submission);
            when(agentJobRepository.findRecentJobByKeyPrefix(eq(1L), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.saveAndFlush(any())).thenAnswer(inv -> {
                AgentJob job = inv.getArgument(0);
                job.prePersist();
                return job;
            });
            var key = new SignalKey(1L, 42L, ScmSignals.PULL_REQUEST_OPENED, SignalRevision.ofHeadCommit("abc123"));

            var job = service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), key)
                    .orElseThrow();

            assertThat(java.util.Objects.requireNonNull(job.getMetadata())
                            .path(AgentJob.SIGNAL_REVISION_METADATA_KEY)
                            .asString())
                    .isEqualTo(key.revision().value());
            assertThat(submission.metadata().has(AgentJob.SIGNAL_REVISION_METADATA_KEY))
                    .isFalse();
        }

        @Test
        void shouldCreateJobWhenCooldownElapsed() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.findRecentJobByKeyPrefix(eq(1L), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.saveAndFlush(any())).thenAnswer(inv -> {
                AgentJob j = inv.getArgument(0);
                j.prePersist();
                return j;
            });

            Optional<AgentJob> result =
                    service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isPresent();
            verify(agentJobRepository).saveAndFlush(any());
        }

        @Test
        void shouldEscapeLikeWildcardsInCooldownPrefix() {
            // A repo name with a LIKE single-char wildcard ('_') must be escaped, or the cooldown prefix
            // would spuriously match unrelated keys (e.g. "my_org" matching "myXorg"). Capture the prefix
            // passed to the LIKE query (ESCAPE '\') and assert the '_' is backslash-escaped.
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            ObjectNode metadata = objectMapper.createObjectNode();
            JobSubmission underscoreKey = new JobSubmission(metadata, "pr_review:my_org/my%repo:42:authoring:abc123");
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(underscoreKey);
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.findRecentJobByKeyPrefix(eq(1L), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.saveAndFlush(any())).thenAnswer(inv -> {
                AgentJob j = inv.getArgument(0);
                j.prePersist();
                return j;
            });

            service.submit(1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
            verify(agentJobRepository).findRecentJobByKeyPrefix(eq(1L), prefix.capture(), any());
            // Phase preserved, freshness stripped, both LIKE metacharacters escaped, config scope appended.
            assertThat(prefix.getValue()).isEqualTo("pr\\_review:my\\_org/my\\%repo:42:authoring:%:detection");
        }
    }

    @Nested
    class CooldownKeyPrefix {

        @Test
        void prKeyStripsFreshnessKeepsPhase() {
            // 5-segment PR key: <type>:<nameWithOwner>:<number>:<phase>:<sha>. Only the trailing
            // SHA is stripped → cooldown scopes per (PR, phase), so an authoring re-trigger is cooled
            // down but a later merge retrospective (different phase) is NOT.
            assertThat(AgentJobService.extractCooldownKeyPrefix("pr_review:owner/repo:42:authoring:abc123"))
                    .isEqualTo("pr_review:owner/repo:42:authoring:");
        }

        @Test
        void issueKeyStripsFreshnessKeepsPhase() {
            // 5-segment issue key: the trailing segment is the disposable updatedAt version, the
            // 4th is the phase. A regression that dropped the freshness segment AND the phase would
            // collapse cooldown back to per-repo; pin that the (issue number, phase) scope survives.
            assertThat(AgentJobService.extractCooldownKeyPrefix("issue_review:owner/repo:12:IssueOpened:1700000000000"))
                    .isEqualTo("issue_review:owner/repo:12:IssueOpened:");
        }
    }

    @Nested
    class DevTriggerSupport {

        @Test
        void buildReviewRequestReturnsNullWhenBranchInfoMissing() {
            PullRequest pr = new PullRequest();
            pr.setId(5L);
            // headRefOid/headRefName/baseRefName all null → nothing to clone or diff.
            assertThat(service.buildReviewRequest(pr, ScmSignals.PULL_REQUEST_MERGED))
                    .isNull();
        }

        @Test
        void buildReviewRequestBuildsDetachedRequestWhenBranchInfoPresent() {
            PullRequest pr = new PullRequest();
            pr.setId(5L);
            pr.setHeadRefOid("abc123");
            pr.setHeadRefName("feature/test");
            pr.setBaseRefName("main");
            Repository repo = new Repository();
            repo.setId(100L);
            repo.setNameWithOwner("owner/repo");
            pr.setRepository(repo);

            var request = service.buildReviewRequest(pr, ScmSignals.PULL_REQUEST_MERGED);

            assertThat(request).isNotNull();
            assertThat(request.headRefOid()).isEqualTo("abc123");
            assertThat(request.triggerSignal()).isEqualTo(ScmSignals.PULL_REQUEST_MERGED);
        }

        @Test
        void buildIssueRequestReturnsNullWhenRepositoryMissing() {
            Issue issue = new Issue();
            issue.setId(7L);
            assertThat(service.buildIssueRequest(issue, ScmSignals.ISSUE_CLOSED))
                    .isNull();
        }

        @BeforeEach
        @SuppressWarnings("unchecked")
        void runTheSubmissionTransaction() {
            lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
                TransactionCallback<?> callback = inv.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
        }

        @Test
        void submitPreparedNamesTheReasonTheSubmissionActuallyStoppedOn() {
            when(agentBindingRepository.findByWorkspaceIdAndPurposeWithModels(1L, AgentPurpose.PRACTICE_REVIEW))
                    .thenReturn(Optional.empty());
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));

            String result = service.submitPrepared(
                    1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEqualTo("No job created. " + SignalStateReason.REVIEW_MODEL_UNBOUND.describe());
        }

        @Test
        void submitPreparedNamesTheCooldownRatherThanGuessingAtTheBudget() {
            // The live case this pins: an enabled binding, no budget configured at all, and a run
            // stopped by the workspace's cooldown.
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());
            AgentJob recent = new AgentJob();
            recent.prePersist();
            when(agentJobRepository.findRecentJobByKeyPrefix(eq(1L), any(), any()))
                    .thenReturn(Optional.of(recent));

            String result = service.submitPrepared(
                    1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).isEqualTo("No job created. " + SignalStateReason.COOLDOWN_ACTIVE.describe());
            assertThat(result).doesNotContain("budget");
        }

        @Test
        void submitPreparedNamesTheJobItCreated() {
            when(workspaceRepository.findById(1L)).thenReturn(Optional.of(workspace));
            JobTypeHandler handler = mock(JobTypeHandler.class);
            when(handlerRegistry.getHandler(AgentJobType.PULL_REQUEST_REVIEW)).thenReturn(handler);
            when(handler.createSubmission(any())).thenReturn(createSubmission());
            when(agentJobRepository.findByWorkspaceIdAndIdempotencyKeyAndStatusIn(anyLong(), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.findRecentJobByKeyPrefix(eq(1L), any(), any()))
                    .thenReturn(Optional.empty());
            when(agentJobRepository.saveAndFlush(any())).thenAnswer(inv -> {
                AgentJob saved = inv.getArgument(0);
                saved.prePersist();
                return saved;
            });

            String result = service.submitPrepared(
                    1L, AgentJobType.PULL_REQUEST_REVIEW, mock(JobSubmissionRequest.class), null);

            assertThat(result).startsWith("Job submitted: ");
        }
    }
}
