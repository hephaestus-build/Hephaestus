package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.job.FrozenCharge;
import de.tum.cit.aet.hephaestus.agent.job.ReviewAttemptUsageService;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobLlmUsage;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The accumulator bean is absent from the test context ({@code hephaestus.runtime.worker.enabled=false}), so it is
 * constructed directly with its own transaction boundary. Ledger outcomes of late calls are in {@code
 * ReviewAttemptLateUsageIntegrationTest}; this covers the in-flight counters of the attempt that runs.
 */
@Tag("integration")
class ProxyUsageAccumulatorIntegrationTest extends AbstractWorkspaceIntegrationTest {
    private static final UUID LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReviewJobRepository jobRepository;

    @Autowired
    private ReviewAttemptUsageService attemptUsage;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static JsonNode json(String body) {
        return MAPPER.readTree(body);
    }

    private ReviewJob persistedJob(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Proxy " + slug, slug + "-org", AccountType.ORG, owner);
        ReviewJob job = new ReviewJob();
        RuntimeJobTestData.initialize(job, workspace.getId());

        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofHours(1)));
        job.setConfigSnapshot(MAPPER.createObjectNode());
        job.prePersist();
        job = jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
        register(job);
        return job;
    }

    private void register(ReviewJob job) {
        assertThat(attemptUsage.registerCall(
                        job.getId(),
                        job.getWorkspaceId(),
                        job.getAttempt(),
                        Objects.requireNonNull(job.getLeaseId()),
                        ModelKind.CHAT,
                        FrozenCharge.UNKNOWN))
                .isTrue();
    }

    private ProxyRouting.BilledAttempt attempt(ReviewJob job, int number) {
        return new ProxyRouting.BilledAttempt(
                UsageSourceType.REVIEW_JOB, job.getId(), number, BigDecimal.ZERO, LEASE, 0L, 0L, job.getWorkspaceId());
    }

    private void accumulate(ProxyUsageAccumulator accumulator, ProxyRouting.BilledAttempt attempt, String body) {
        accumulator.accumulate(attempt, null, ProxyTokenUsage.from(json(body), false));
    }

    private ProxyUsageAccumulator accumulator(SimpleMeterRegistry registry) {
        return new ProxyUsageAccumulator(attemptUsage, transactionManager, registry);
    }

    private ReviewJobLlmUsage usageOf(ReviewJob job) {
        return jobRepository.findLlmUsageById(job.getId()).orElseThrow();
    }

    @Test
    void shouldAddEachCallOntoTheRunningAttemptWhenCallsRepeat() {
        ReviewJob job = persistedJob("proxy-usage-adds");
        String body = "{\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":10,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":5,\"cache_write_tokens\":7},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":2}}}";
        ProxyUsageAccumulator accumulator = accumulator(new SimpleMeterRegistry());

        accumulate(accumulator, attempt(job, 0), body);
        accumulate(accumulator, attempt(job, 0), body);
        accumulate(accumulator, attempt(job, 0), body);

        ReviewJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isEqualTo(3);
        assertThat(usage.inputTokens()).isEqualTo(54); // 3 × (30 − 5 − 7)
        assertThat(usage.cacheReadTokens()).isEqualTo(15);
        assertThat(usage.cacheWriteTokens()).isEqualTo(21);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.reasoningTokens()).isEqualTo(6);
        assertThat(jdbc.queryForObject(
                        "SELECT proxy_calls FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 0",
                        Integer.class,
                        job.getId()))
                .isEqualTo(3);
    }

    @Test
    void shouldRecordNothingWhenTheResponseHasNoUsageBlock() {
        ReviewJob job = persistedJob("proxy-usage-no-block");

        accumulate(accumulator(new SimpleMeterRegistry()), attempt(job, 0), "{\"choices\":[]}");

        ReviewJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isZero();
        assertThat(usage.inputTokens()).isZero();
    }

    @Test
    void shouldTouchNoRowWhenTheCallHasNoBillingTarget() {
        ReviewJob job = persistedJob("proxy-usage-null-target");

        accumulator(new SimpleMeterRegistry())
                .accumulate(
                        null,
                        null,
                        ProxyTokenUsage.from(
                                json("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0}}"), false));

        assertThat(usageOf(job).totalCalls()).isZero();
    }

    @Test
    void shouldLeaveTheNewAttemptsCountersAloneWhenASupersededAttemptsCallReturns() {
        ReviewJob job = persistedJob("proxy-usage-superseded");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        requeueTo(job, 1);

        accumulate(
                accumulator(registry), attempt(job, 0), "{\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":9}}");

        ReviewJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isZero();
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        // It landed on its own attempt instead.
        assertThat(jdbc.queryForObject(
                        "SELECT proxy_input_tokens FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 0",
                        Long.class,
                        job.getId()))
                .isEqualTo(25L);
        assertThat(registry.counter("llm.proxy.usage.late_settled").count()).isEqualTo(1.0);
    }

    @Test
    void shouldAddOntoTheAttemptThatOwnsTheRowWhenItWasRequeuedOnto() {
        ReviewJob job = persistedJob("proxy-usage-owning-attempt");
        requeueTo(job, 1);
        register(job);

        accumulate(
                accumulator(new SimpleMeterRegistry()),
                attempt(job, 1),
                "{\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":9}}");

        ReviewJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isEqualTo(1);
        assertThat(usage.inputTokens()).isEqualTo(25);
        assertThat(usage.outputTokens()).isEqualTo(9);
    }

    @Test
    void shouldDropTheCallWhenItsAttemptWasNeverRegistered() {
        ReviewJob job = persistedJob("proxy-usage-unregistered");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        accumulate(
                accumulator(registry), attempt(job, 3), "{\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":0}}");

        assertThat(usageOf(job).totalCalls()).isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM review_job_attempt_usage WHERE job_id = ? AND attempt = 3",
                        Integer.class,
                        job.getId()))
                .isZero();
        assertThat(registry.counter("llm.proxy.usage.accumulate.superseded").count())
                .isEqualTo(1.0);
    }

    private void requeueTo(ReviewJob job, int attempt) {
        job.setAttempt(attempt);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLlmCalls(0);
        job.setLlmInputTokens(0);
        job.setLlmOutputTokens(0);
        job.setLlmReasoningTokens(0);
        job.setLlmCacheReadTokens(0);
        job.setLlmCacheWriteTokens(0);
        jobRepository.saveAndFlush(RuntimeJobTestData.persistParent(jdbc, job));
    }

    /** Only reachable with a stubbed service: a real one has no way to fail on demand. */
    @Test
    void shouldCountAFailedSettlementWithoutThrowingWhenTheDatabaseFails() {
        ReviewAttemptUsageService failing = mock(ReviewAttemptUsageService.class);
        when(failing.settleCall(any(), anyLong(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("connection reset"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyUsageAccumulator accumulator = new ProxyUsageAccumulator(failing, transactionManager, registry);
        ProxyRouting.BilledAttempt attempt = new ProxyRouting.BilledAttempt(
                UsageSourceType.REVIEW_JOB, UUID.randomUUID(), 0, BigDecimal.ZERO, LEASE, 0L, 0L, 1L);

        assertThatCode(() ->
                        accumulate(accumulator, attempt, "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0}}"))
                .doesNotThrowAnyException();

        assertThat(registry.counter("llm.proxy.usage.accumulate.failure").count())
                .isEqualTo(1.0);
    }
}
