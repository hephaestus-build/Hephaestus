package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedObservations;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationAdmissionServiceTest extends BaseUnitTest {

    @Mock
    private AgentJobRepository jobs;

    @Mock
    private ObservationRepository observations;

    @Mock
    private PreparedObservations prepared;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final Map<AgentJobType, JobTypeHandler> handlers = new EnumMap<>(AgentJobType.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final JsonNode NO_FAILURES = mapper.createArrayNode();
    private ObservationAdmissionService service;
    private AgentJob job;
    private ObservationAdmissionService.AdmissionIdentity identity;

    @BeforeEach
    void setUp() {
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        for (AgentJobType type : AgentJobType.values()) {
            JobTypeHandler handler = mock(JobTypeHandler.class);
            lenient().when(handler.jobType()).thenReturn(type);
            lenient().when(handler.prepareObservations(any(), any())).thenReturn(prepared);
            handlers.put(type, handler);
        }
        service = new ObservationAdmissionService(
                jobs,
                observations,
                new JobTypeHandlerRegistry(List.copyOf(handlers.values())),
                mapper,
                transactionManager);
        job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        identity = new ObservationAdmissionService.AdmissionIdentity(job.getId(), 1L, 0, "worker-1");
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        job.setWorkspace(workspace);
        job.setMetadata(mapper.createObjectNode());
        when(jobs.findByIdWithWorkspaceForUpdate(job.getId())).thenReturn(Optional.of(job));
        lenient()
                .when(observations.findByAgentJobId(
                        job.getId(), job.getWorkspace().getId()))
                .thenReturn(List.of());
    }

    /** Submits an admission and returns once its thread is parked on the running one. */
    private static Future<ObjectNode> joiningRetry(
            ExecutorService pool, java.util.concurrent.Callable<ObjectNode> admit) throws InterruptedException {
        var thread = new java.util.concurrent.atomic.AtomicReference<Thread>();
        Future<ObjectNode> retry = pool.submit(() -> {
            thread.set(Thread.currentThread());
            return admit.call();
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (thread.get() == null || thread.get().getState() != Thread.State.WAITING) {
            assertThat(System.nanoTime())
                    .as("the retry parks on the running admission")
                    .isLessThan(deadline);
            assertThat(retry.isDone())
                    .as("the retry does not finish on its own")
                    .isFalse();
            Thread.sleep(5);
        }
        return retry;
    }

    private JobTypeHandler pullRequests() {
        return Objects.requireNonNull(handlers.get(AgentJobType.PULL_REQUEST_REVIEW));
    }

    private static String refusalReason(AgentJob job) {
        return Objects.requireNonNull(job.getMetadata())
                .path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                .path("reasonCode")
                .asString();
    }

    @Test
    void shouldClearPreviousRefusalWhenObservationsAreAdmitted() {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.putObject(ObservationAdmissionService.REFUSAL_METADATA_KEY).put("reasonCode", "no_valid_observations");
        job.setMetadata(metadata);
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isTrue();
        service.admit(identity, mapper.createArrayNode());
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isFalse();
        verify(prepared).record(job);
    }

    @Test
    void samePayloadReplaysWithoutAdmittingTwice() {
        ArrayNode payload = mapper.createArrayNode().add("one");
        ObjectNode first = service.admit(identity, payload);
        ObjectNode replay = service.admit(identity, payload);

        assertThat(replay.path("admissionDigest").asString())
                .isEqualTo(first.path("admissionDigest").asString());
        verify(pullRequests(), times(1)).prepareObservations(job, payload);
        verify(prepared, times(1)).record(job);
    }

    @Test
    void changedPayloadConflictsAfterAdmission() {
        service.admit(identity, mapper.createArrayNode().add("one"));
        assertThatThrownBy(
                        () -> service.admit(identity, mapper.createArrayNode().add("two")))
                .isInstanceOf(ObservationAdmissionService.AdmissionConflictException.class);
        verify(pullRequests(), times(1)).prepareObservations(eq(job), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "worker", "workspace", "cancelled", "completed", "missing"})
    void shouldRejectAdmissionAndRefusalWhenAuthenticatedOwnershipIsStale(String mismatch) {
        switch (mismatch) {
            case "attempt" -> job.setRetryCount(1);
            case "worker" -> job.setWorkerId("worker-2");
            case "workspace" -> job.getWorkspace().setId(2L);
            case "cancelled" -> job.setStatus(AgentJobStatus.CANCELLED);
            case "completed" -> job.setStatus(AgentJobStatus.COMPLETED);
            case "missing" ->
                when(jobs.findByIdWithWorkspaceForUpdate(job.getId())).thenReturn(Optional.empty());
            default -> throw new AssertionError(mismatch);
        }

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        assertThatThrownBy(() -> service.recordRefusal(identity, "no_valid_observations", "Refused", NO_FAILURES))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);

        handlers.values().forEach(handler -> verify(handler, never()).prepareObservations(any(), any()));
        verify(jobs, never()).save(any());
        assertThat(job.getMetadata()).isEqualTo(mapper.createObjectNode());
    }

    @Test
    void shouldPersistRefusalWhenAttemptStillOwnsRunningJob() {
        service.recordRefusal(identity, "no_valid_observations", "Refused", NO_FAILURES);
        assertThat(refusalReason(job)).isEqualTo("no_valid_observations");
        verify(jobs).save(job);
    }

    @Test
    void shouldRecordTheRefusalOnTheJobWhenTheHandlerRefusesTheSubmission() {
        when(pullRequests().prepareObservations(eq(job), any()))
                .thenThrow(new ObservationsRefusedException("did_not_read_the_diff", "Refused"));

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationsRefusedException.class);

        assertThat(refusalReason(job)).isEqualTo("did_not_read_the_diff");
        verify(prepared, never()).record(any());
    }

    @Test
    void shouldRecordAnInadmissibleSubmissionAsARefusalWhenTheHandlerCannotAdmitIt() {
        when(pullRequests().prepareObservations(eq(job), any()))
                .thenThrow(new JobDeliveryException("Observation cited unavailable evidence"));

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessage("Observation cited unavailable evidence");

        assertThat(refusalReason(job)).isEqualTo(ObservationAdmissionService.INADMISSIBLE_REASON_CODE);
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reason")
                        .asString())
                .isEqualTo("Observation cited unavailable evidence");
    }

    @Test
    void shouldRejectDelayedRefusalWhenAnotherSubmissionWasAdmitted() {
        service.admit(identity, mapper.createArrayNode());
        assertThatThrownBy(() -> service.recordRefusal(identity, "no_valid_observations", "Refused", NO_FAILURES))
                .isInstanceOf(ObservationAdmissionService.AdmissionConflictException.class);
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isFalse();
        verify(jobs, times(1)).save(job);
    }

    @Test
    void shouldRejectPublicationWhenOwnershipChangesDuringVerification() {
        when(pullRequests().prepareObservations(eq(job), any())).thenAnswer(invocation -> {
            job.setRetryCount(1);
            return prepared;
        });
        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        verifyNoInteractions(prepared);
        verify(jobs, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ISSUE_REVIEW", "CONVERSATION_REVIEW", "DOCUMENT_REVIEW"})
    void shouldUseFencedAdmissionForEveryReviewedWorkKind(String type) {
        job.setJobType(AgentJobType.valueOf(type));
        ArrayNode payload = mapper.createArrayNode().add("observation");
        service.admit(identity, payload);
        verify(handlers.get(job.getJobType())).prepareObservations(job, payload);
        verify(pullRequests(), never()).prepareObservations(any(), any());
        verify(prepared).record(job);
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.DIGEST_METADATA_KEY)
                        .asString())
                .isNotBlank();
    }

    @Test
    void shouldJoinTheRunningAdmissionWhenTheSamePayloadArrivesAgain() throws Exception {
        ArrayNode payload = mapper.createArrayNode().add("one");
        var verifying = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var preparations = new AtomicInteger();
        when(pullRequests().prepareObservations(eq(job), any())).thenAnswer(invocation -> {
            preparations.incrementAndGet();
            verifying.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return prepared;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ObjectNode> first = pool.submit(() -> service.admit(identity, payload));
            assertThat(verifying.await(10, TimeUnit.SECONDS)).isTrue();
            // The retry has nothing to do but wait: a second preparation would be a second container.
            Future<ObjectNode> retry = joiningRetry(pool, () -> service.admit(identity, payload.deepCopy()));
            release.countDown();

            assertThat(retry.get(10, TimeUnit.SECONDS).path("admissionDigest").asString())
                    .isEqualTo(first.get(10, TimeUnit.SECONDS)
                            .path("admissionDigest")
                            .asString());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(preparations).hasValue(1);
        verify(prepared, times(1)).record(job);
    }

    @Test
    void shouldHandTheRunningAdmissionsRefusalToTheRetryThatJoinedIt() throws Exception {
        ArrayNode payload = mapper.createArrayNode().add("one");
        var verifying = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pullRequests().prepareObservations(eq(job), any())).thenAnswer(invocation -> {
            verifying.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            throw new ObservationsRefusedException("no_valid_observations", "Refused");
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ObjectNode> first = pool.submit(() -> service.admit(identity, payload));
            assertThat(verifying.await(10, TimeUnit.SECONDS)).isTrue();
            Future<ObjectNode> retry = joiningRetry(pool, () -> service.admit(identity, payload.deepCopy()));
            release.countDown();

            for (Future<ObjectNode> attempt : List.of(first, retry)) {
                assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                        .cause()
                        .isInstanceOf(ObservationsRefusedException.class);
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(pullRequests(), times(1)).prepareObservations(eq(job), any());
        assertThat(refusalReason(job)).isEqualTo("no_valid_observations");
    }

    @Test
    void shouldConflictWhenADifferentPayloadArrivesWhileOneIsBeingVerified() throws Exception {
        var verifying = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pullRequests().prepareObservations(eq(job), any())).thenAnswer(invocation -> {
            verifying.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return prepared;
        });
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<ObjectNode> first = pool.submit(
                    () -> service.admit(identity, mapper.createArrayNode().add("one")));
            assertThat(verifying.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() ->
                            service.admit(identity, mapper.createArrayNode().add("two")))
                    .isInstanceOf(ObservationAdmissionService.AdmissionConflictException.class);

            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void responseCarriesDurableIdentityAndFullEvidence() {
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        Practice practice = new Practice();
        practice.setSlug("explains-why");
        ObjectNode evidence = mapper.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", "scm.issue.core")
                .put("quote", "why");
        Observation observation = Observation.builder()
                .id(UUID.randomUUID())
                .practice(practice)
                .summary("Explains the motivation")
                .presence(Presence.PRESENT)
                .assessment(Assessment.GOOD)
                .assessmentStatus(AssessmentStatus.ASSESSED)
                .evidence(evidence)
                .evidenceRationale("The issue states why.")
                .build();
        when(observations.findByAgentJobId(job.getId(), job.getWorkspace().getId()))
                .thenReturn(List.of(observation));

        ObjectNode response = service.admit(identity, mapper.createArrayNode().add("one"));

        assertThat(response.path("observations").get(0).path("id").asString()).isNotBlank();
        assertThat(response.path("observations").get(0).path("evidence").path("citations"))
                .hasSize(1);
        assertThat(response.path("observations")
                        .get(0)
                        .path("citations")
                        .get(0)
                        .path("quote")
                        .asString())
                .isEqualTo("why");
        assertThat(response.path("observations")
                        .get(0)
                        .path("citations")
                        .get(0)
                        .path("anchorable")
                        .asBoolean())
                .isFalse();
    }
}
