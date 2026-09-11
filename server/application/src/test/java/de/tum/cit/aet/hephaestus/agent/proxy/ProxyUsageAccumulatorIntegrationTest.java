package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.config.AgentPurpose;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobLlmUsage;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.AbstractWorkspaceIntegrationTest;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The accumulator bean is absent from the test context ({@code hephaestus.runtime.worker.enabled=false}),
 * so it is constructed directly here and its {@code REQUIRES_NEW} boundary supplied by
 * {@link TransactionTemplate}.
 */
@Tag("integration")
class ProxyUsageAccumulatorIntegrationTest extends AbstractWorkspaceIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private AgentJobRepository jobRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static JsonNode json(String body) {
        return MAPPER.readTree(body);
    }

    private AgentJob persistedJob(String slug) {
        User owner = persistUser(slug + "-owner");
        Workspace workspace = createWorkspace(slug, "Proxy " + slug, slug + "-org", AccountType.ORG, owner);
        AgentJob job = new AgentJob();
        job.setWorkspace(workspace);
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        job.setPurpose(AgentPurpose.PRACTICE_REVIEW);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setConfigSnapshot(MAPPER.createObjectNode());
        job.prePersist();
        return jobRepository.saveAndFlush(job);
    }

    private void accumulate(@org.jspecify.annotations.Nullable UUID jobId, JsonNode body, boolean responsesProtocol) {
        accumulateAs(
                jobId == null
                        ? null
                        : new ProxyRouting.BilledAttempt(
                                LlmUsageSourceType.AGENT_JOB, jobId, 0, BigDecimal.ZERO, "worker-1"),
                body,
                responsesProtocol);
    }

    private void accumulateAs(
            ProxyRouting.@org.jspecify.annotations.Nullable BilledAttempt attempt,
            JsonNode body,
            boolean responsesProtocol) {
        accumulateAs(
                new ProxyUsageAccumulator(jobRepository, new SimpleMeterRegistry()), attempt, body, responsesProtocol);
    }

    private void accumulateAs(
            ProxyUsageAccumulator accumulator,
            ProxyRouting.@org.jspecify.annotations.Nullable BilledAttempt attempt,
            JsonNode body,
            boolean responsesProtocol) {
        transactionTemplate.executeWithoutResult(
                tx -> accumulator.accumulate(attempt, ProxyTokenUsage.from(body, responsesProtocol)));
    }

    private AgentJobLlmUsage usageOf(AgentJob job) {
        return jobRepository.findLlmUsageById(job.getId()).orElseThrow();
    }

    @Test
    @DisplayName("successive calls add onto the row rather than replacing it")
    void repeatedCallsAccumulateOntoTheSameRow() {
        AgentJob job = persistedJob("proxy-usage-adds");
        String body = "{\"usage\":{\"prompt_tokens\":30,\"completion_tokens\":10,"
                + "\"prompt_tokens_details\":{\"cached_tokens\":5,\"cache_write_tokens\":7},"
                + "\"completion_tokens_details\":{\"reasoning_tokens\":2}}}";

        accumulate(job.getId(), json(body), false);
        accumulate(job.getId(), json(body), false);
        accumulate(job.getId(), json(body), false);

        AgentJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isEqualTo(3);
        assertThat(usage.inputTokens()).isEqualTo(54); // 3 × (30 − 5 − 7)
        assertThat(usage.cacheReadTokens()).isEqualTo(15);
        assertThat(usage.cacheWriteTokens()).isEqualTo(21);
        assertThat(usage.outputTokens()).isEqualTo(30);
        assertThat(usage.reasoningTokens()).isEqualTo(6);
    }

    @Test
    @DisplayName("a response with no usage block leaves the row at zero")
    void missingUsageBlockRecordsNothing() {
        AgentJob job = persistedJob("proxy-usage-no-block");

        accumulate(job.getId(), json("{\"choices\":[]}"), false);

        AgentJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isZero();
        assertThat(usage.inputTokens()).isZero();
    }

    @Test
    @DisplayName("the mentor route (no billing target) touches no row")
    void nullJobIdIsANoOp() {
        AgentJob job = persistedJob("proxy-usage-null-target");

        accumulate(null, json("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0}}"), false);

        assertThat(usageOf(job).totalCalls()).isZero();
    }

    /**
     * Attempt 0 dispatches a provider call. While it waits, its worker's heartbeat goes stale and orphan
     * recovery requeues the job — bumping {@code retry_count} and zeroing these counters — after which a
     * sibling claims it as attempt 1. The original worker was never dead: its response arrives and it
     * accumulates, onto counters that now belong to attempt 1 and its price and funding source.
     */
    @Test
    @DisplayName("a late call from a superseded attempt is dropped, not billed to the attempt that now owns the row")
    void lateWriteFromASupersededAttemptDoesNotLandOnTheNewAttempt() {
        AgentJob job = persistedJob("proxy-usage-superseded");
        ProxyRouting.BilledAttempt attemptZero = new ProxyRouting.BilledAttempt(
                LlmUsageSourceType.AGENT_JOB, job.getId(), 0, BigDecimal.ZERO, "worker-1");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyUsageAccumulator accumulator = new ProxyUsageAccumulator(jobRepository, registry);

        requeueTo(job, 1);

        accumulateAs(
                accumulator, attemptZero, json("{\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":9}}"), false);

        AgentJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isZero();
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        assertThat(registry.counter("llm.proxy.usage.accumulate.superseded").count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("the attempt that still owns the row is accumulated onto as before")
    void theOwningAttemptStillAccumulates() {
        AgentJob job = persistedJob("proxy-usage-owning-attempt");
        requeueTo(job, 1);

        accumulateAs(
                new ProxyRouting.BilledAttempt(
                        LlmUsageSourceType.AGENT_JOB, job.getId(), 1, BigDecimal.ZERO, "worker-1"),
                json("{\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":9}}"),
                false);

        AgentJobLlmUsage usage = usageOf(job);
        assertThat(usage.totalCalls()).isEqualTo(1);
        assertThat(usage.inputTokens()).isEqualTo(25);
        assertThat(usage.outputTokens()).isEqualTo(9);
    }

    @Test
    @DisplayName("a call that lands after the job went terminal does not corrupt the final totals")
    void lateWriteAfterTerminalIsDropped() {
        AgentJob job = persistedJob("proxy-usage-after-terminal");
        job.setStatus(AgentJobStatus.COMPLETED);
        job.setLlmTotalInputTokens(500);
        job.setLlmTotalCalls(4);
        jobRepository.saveAndFlush(job);

        accumulate(job.getId(), json("{\"usage\":{\"prompt_tokens\":25,\"completion_tokens\":0}}"), false);

        AgentJobLlmUsage usage = usageOf(job);
        assertThat(usage.inputTokens()).isEqualTo(500);
        assertThat(usage.totalCalls()).isEqualTo(4);
    }

    /** Reproduces what {@code AgentJobRepository#requeueOrphan} does to the row, then re-claims it. */
    private void requeueTo(AgentJob job, int attempt) {
        job.setRetryCount(attempt);
        job.setStatus(AgentJobStatus.RUNNING);
        job.setLlmTotalCalls(0);
        job.setLlmTotalInputTokens(0);
        job.setLlmTotalOutputTokens(0);
        job.setLlmTotalReasoningTokens(0);
        job.setLlmCacheReadTokens(0);
        job.setLlmCacheWriteTokens(0);
        jobRepository.saveAndFlush(job);
    }

    /** Only reachable with a stubbed repository: a real one has no way to fail on demand. */
    @Test
    @DisplayName("a failed write is swallowed, but counted so the under-billing is visible")
    void failedAccumulationIsCountedNotSilent() {
        AgentJobRepository failing = mock(AgentJobRepository.class);
        when(failing.accumulateLlmUsage(any(), anyInt(), any()))
                .thenThrow(new IllegalStateException("connection reset"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProxyUsageAccumulator accumulator = new ProxyUsageAccumulator(failing, registry);

        assertThatCode(() -> accumulator.accumulate(
                        new ProxyRouting.BilledAttempt(
                                LlmUsageSourceType.AGENT_JOB, UUID.randomUUID(), 0, BigDecimal.ZERO, "worker-1"),
                        ProxyTokenUsage.from(
                                json("{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":0}}"), false)))
                .doesNotThrowAnyException();

        assertThat(registry.counter("llm.proxy.usage.accumulate.failure").count())
                .isEqualTo(1.0);
    }
}
