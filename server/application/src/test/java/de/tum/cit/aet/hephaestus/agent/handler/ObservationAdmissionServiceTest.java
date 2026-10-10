package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppSupportContext;
import de.tum.cit.aet.hephaestus.agent.handler.inapp.InAppSupportReader;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobTypeHandler;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.PreparedObservations;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.PreviousInAppFeedback;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Outcome;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
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

    @Mock
    private JobEvidenceFiles evidenceFiles;

    @Mock
    private ReviewHistoryContentSource reviewHistory;

    @Mock
    private InAppSupportReader supportReader;

    @Mock
    private ObjectProvider<InAppSupportReader> supportReaders;

    @Mock
    private PullRequestRepository pullRequests;

    private ObservationAdmissionService service;
    private AgentJob job;
    private ObservationAdmissionService.AdmissionIdentity identity;

    @Mock
    private PublicReviewEligibility publicEligibility;

    @BeforeEach
    void setUp() {
        lenient().when(publicEligibility.publicObservationIds(any(), any())).thenAnswer(invocation -> {
            Collection<Observation> rows = invocation.getArgument(1);
            return rows.stream().map(Observation::getId).collect(Collectors.toSet());
        });
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new SimpleTransactionStatus());
        // The index of a late read is the catalog's (EvidenceFolderPersonErasureIntegrationTest); here it answers the
        // read.
        lenient()
                .when(evidenceFiles.indexLateRead(any(), any(), any()))
                .thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
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
                transactionManager,
                evidenceFiles,
                publicEligibility,
                reviewHistory,
                supportReaders,
                pullRequests);
        lenient().when(supportReaders.getIfAvailable()).thenReturn(supportReader);
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

    @Test
    void shouldKeepTheAdmissionAndReportSupportUnavailableWhenTheSupportReadFails() {
        when(supportReader.snapshot(eq(job), any())).thenThrow(new IllegalStateException("read failed"));

        ObjectNode response = service.admit(identity, mapper.createArrayNode());

        assertThat(response.path(ObservationAdmissionService.IN_APP_SUPPORT_KEY)
                        .path("state")
                        .asString())
                .isEqualTo("UNAVAILABLE");
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.DIGEST_METADATA_KEY)
                        .asString())
                .isNotBlank()
                .isEqualTo(response.path("admissionDigest").asString());
        verify(prepared, times(1)).record(job);
    }

    @Test
    void shouldReadSupportInAWorkerOnlyContextAndKeepTheAdmissionWhenNoReaderExists() {
        var worker = new ApplicationContextRunner()
                .withPropertyValues("hephaestus.runtime.server.enabled=false", "hephaestus.runtime.worker.enabled=true")
                .withBean(ObservationRepository.class, () -> observations)
                .withBean(ObservationVisibilityPolicy.class, () -> mock(ObservationVisibilityPolicy.class))
                .withBean(PreviousInAppFeedback.class, () -> mock(PreviousInAppFeedback.class))
                .withBean(PracticeFeedbackDeliveryPolicy.class, () -> mock(PracticeFeedbackDeliveryPolicy.class))
                .withBean(Clock.class, Clock::systemUTC);
        worker.withUserConfiguration(InAppSupportReader.class)
                .run(context -> assertThat(admitWith(context.getBeanProvider(InAppSupportReader.class))
                                .path(ObservationAdmissionService.IN_APP_SUPPORT_KEY)
                                .path("state")
                                .asString())
                        .isEqualTo("COMPLETE"));
        worker.run(context -> assertThat(admitWith(context.getBeanProvider(InAppSupportReader.class))
                        .path(ObservationAdmissionService.IN_APP_SUPPORT_KEY)
                        .path("state")
                        .asString())
                .isEqualTo("UNAVAILABLE"));
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.DIGEST_METADATA_KEY)
                        .asString())
                .isNotBlank();
    }

    private ObjectNode admitWith(ObjectProvider<InAppSupportReader> readers) {
        return new ObservationAdmissionService(
                        jobs,
                        observations,
                        new JobTypeHandlerRegistry(List.copyOf(handlers.values())),
                        mapper,
                        transactionManager,
                        evidenceFiles,
                        publicEligibility,
                        reviewHistory,
                        readers,
                        pullRequests)
                .admit(identity, mapper.createArrayNode());
    }

    @Test
    void shouldCarryTheSupportReadOfTheOwningAttemptForTheAdmittedRows() {
        var support = new InAppSupportContext(
                InAppSupportContext.State.COMPLETE,
                "2026-10-08T10:00:00Z",
                null,
                List.of(new InAppSupportContext.PracticeSupport("handles-errors", List.of())));
        when(supportReader.snapshot(job, List.of())).thenReturn(support);

        ObjectNode response = service.admit(identity, mapper.createArrayNode());

        assertThat(response.path(ObservationAdmissionService.IN_APP_SUPPORT_KEY))
                .isEqualTo(mapper.valueToTree(support));
        assertThat(response.path("observations").isArray()).isTrue();
    }

    @Test
    void shouldReportTheLostAttemptInsteadOfSupportWhenOwnershipChangesAfterAdmission() {
        doAnswer(invocation -> {
                    job.setWorkerId("worker-2");
                    return null;
                })
                .when(prepared)
                .record(job);

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.DIGEST_METADATA_KEY)
                        .asString())
                .isNotBlank();
        verifyNoInteractions(supportReader);
    }

    @Test
    void refreshesCommunicationAfterAdmissionWithoutChangingCanonicalEvidence() {
        service.admit(identity, mapper.createArrayNode());
        JsonNode metadata = Objects.requireNonNull(job.getMetadata()).deepCopy();
        Observation author = Observation.builder()
                .id(UUID.randomUUID())
                .aboutUserId(7L)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .build();
        when(observations.findByAgentJobId(job.getId(), 1L)).thenReturn(List.of(author));
        ObjectNode first = mapper.createObjectNode();
        first.putArray("feedback");
        ObjectNode later = first.deepCopy();
        later.withArray("feedback").addObject().put("body", "Earlier public advice");
        when(reviewHistory.publicSameWorkFeedback(1L, 7L, ArtifactKinds.PULL_REQUEST, 42L))
                .thenReturn(first, later);

        assertThat(service.publicFeedbackHistory(identity).path("history")).isEqualTo(first);
        ObjectNode refreshed = service.publicFeedbackHistory(identity);
        assertThat(refreshed.path("history")).isEqualTo(later);
        assertThat(Instant.parse(refreshed.path("readAt").asString())).isNotNull();
        assertThat(job.getMetadata()).isEqualTo(metadata);
        verify(prepared, times(1)).record(job);
        verify(pullRequests(), times(1)).prepareObservations(eq(job), any());
    }

    @Test
    void refusesHistoryBeforeAdmissionAndAfterOwnershipLoss() {
        assertThatThrownBy(() -> service.publicFeedbackHistory(identity))
                .isInstanceOf(ObservationAdmissionService.PublicHistoryRefusedException.class);
        verifyNoInteractions(reviewHistory);
        service.admit(identity, mapper.createArrayNode());
        job.setWorkerId("another-worker");
        assertThatThrownBy(() -> service.publicFeedbackHistory(identity))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        verifyNoInteractions(reviewHistory);
    }

    @Test
    void refusesIneligiblePublicHistoryAndPreservesAdmissionWhenAHistoryQueryFails() {
        service.admit(identity, mapper.createArrayNode());
        JsonNode metadata = Objects.requireNonNull(job.getMetadata()).deepCopy();
        assertThatThrownBy(() -> service.publicFeedbackHistory(identity))
                .isInstanceOf(ObservationAdmissionService.PublicHistoryRefusedException.class);
        Observation author = Observation.builder()
                .id(UUID.randomUUID())
                .aboutUserId(7L)
                .artifactKind(ArtifactKinds.PULL_REQUEST)
                .artifactId(42L)
                .build();
        when(observations.findByAgentJobId(job.getId(), 1L)).thenReturn(List.of(author));
        when(reviewHistory.publicSameWorkFeedback(1L, 7L, ArtifactKinds.PULL_REQUEST, 42L))
                .thenThrow(new IllegalStateException("history unavailable"));
        assertThatThrownBy(() -> service.publicFeedbackHistory(identity)).isInstanceOf(IllegalStateException.class);
        assertThat(job.getMetadata()).isEqualTo(metadata);
        verify(prepared, times(1)).record(job);
    }

    /** Submits an admission and returns once its thread is parked on the running one. */
    private static Future<ObjectNode> joiningRetry(ExecutorService pool, Callable<ObjectNode> admit)
            throws InterruptedException {
        var thread = new AtomicReference<Thread>();
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
    void shouldRemoveEvidenceOnlyAfterAdmissionCommits() {
        var committed = new AtomicBoolean();
        doAnswer(invocation -> {
                    committed.set(true);
                    return null;
                })
                .when(transactionManager)
                .commit(any());
        doAnswer(invocation -> {
                    assertThat(committed).isTrue();
                    assertThat(ObservationAdmissionService.isAdmitted(job)).isTrue();
                    return null;
                })
                .when(evidenceFiles)
                .discardAdmittedAttempt(identity);
        service.admit(identity, mapper.createObjectNode());
        verify(evidenceFiles).discardAdmittedAttempt(identity);
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

    @ParameterizedTest
    @EnumSource(AgentJobType.class)
    void shouldRecordAnInadmissibleSubmissionWhenPublicationRejectsIt(AgentJobType type) {
        job.setJobType(type);
        doThrow(new JobDeliveryException("Reviewed work changed after submission"))
                .when(prepared)
                .record(job);

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessage("Reviewed work changed after submission");

        assertThat(refusalReason(job)).isEqualTo(ObservationAdmissionService.INADMISSIBLE_REASON_CODE);
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reason")
                        .asString())
                .isEqualTo("Reviewed work changed after submission");
        assertThat(ObservationAdmissionService.isAdmitted(job)).isFalse();
        verify(evidenceFiles, never()).discardAdmittedAttempt(any());
        verify(transactionManager).rollback(any());
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
                .outcome(Outcome.MET)
                .evidence(evidence)
                .evidenceRationale("The issue states why.")
                .build();
        when(observations.findByAgentJobId(job.getId(), job.getWorkspace().getId()))
                .thenReturn(List.of(observation));

        when(publicEligibility.publicObservationIds(job, List.of(observation))).thenReturn(Set.of());
        ObjectNode response = service.admit(identity, mapper.createArrayNode().add("one"));

        assertThat(response.path("observations").get(0).path("id").asString())
                .isEqualTo(observation.getId().toString());
        assertThat(response.path("observations").get(0).path("publicEligible").asBoolean())
                .isFalse();
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

    private ObjectNode reviewedTarget(long pullRequestId) {
        return mapper.createObjectNode()
                .put("pull_request_id", pullRequestId)
                .put("repository_id", 7L)
                .put("repository_full_name", "org/repo")
                .put("pr_number", 12);
    }

    @Test
    void shouldLockThePreparedPullRequestBeforeTheJobAndAdmitAnUnchangedTarget() {
        job.setMetadata(reviewedTarget(42L));

        service.admit(identity, mapper.createArrayNode());

        var order = inOrder(pullRequests, prepared);
        order.verify(pullRequests).lockById(42L);
        order.verify(prepared).record(job);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pull_request_id", "repository_id", "repository_full_name", "pr_number"})
    void shouldRefuseATargetThatChangedWhileThePullRequestLockWasAwaited(String field) {
        job.setMetadata(reviewedTarget(42L));
        when(Objects.requireNonNull(handlers.get(AgentJobType.PULL_REQUEST_REVIEW))
                        .prepareObservations(any(), any()))
                .thenAnswer(invocation -> {
                    ObjectNode changed = reviewedTarget(42L);
                    if (field.equals("repository_full_name")) {
                        changed.put(field, "org/other");
                    } else {
                        changed.put(field, 43L);
                    }
                    job.setMetadata(changed);
                    return prepared;
                });

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);

        verify(pullRequests).lockById(42L);
        verify(prepared, never()).record(any());
    }

    @Test
    void shouldRefuseATargetMovedToAnotherWorkspaceWhileThePullRequestLockWasAwaited() {
        job.setMetadata(reviewedTarget(42L));
        when(Objects.requireNonNull(handlers.get(AgentJobType.PULL_REQUEST_REVIEW))
                        .prepareObservations(any(), any()))
                .thenAnswer(invocation -> {
                    Workspace other = new Workspace();
                    other.setId(2L);
                    job.setWorkspace(other);
                    return prepared;
                });

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);

        verify(prepared, never()).record(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"18446744073709551616", "\"42\"", "0", "-1", "4.2", "null"})
    void shouldLockNothingForAPullRequestIdThatIsNotAPositiveLong(String id) {
        ObjectNode metadata = reviewedTarget(42L);
        metadata.set("pull_request_id", mapper.readTree(id));
        job.setMetadata(metadata);

        service.admit(identity, mapper.createArrayNode());

        verify(pullRequests, never()).lockById(anyLong());
        verify(prepared).record(job);
    }

    @Test
    void shouldLockNothingForAnotherJobType() {
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        job.setMetadata(reviewedTarget(42L));

        service.admit(identity, mapper.createArrayNode());

        verify(pullRequests, never()).lockById(anyLong());
        verify(prepared).record(job);
    }
}
