package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmConnectionHealth;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageRecorder;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/** Real proxy routing and transport for a sandbox test; capacity, consent and payer caps have separate contracts. */
public final class SandboxModelProxy {
    private SandboxModelProxy() {}

    public static Object controller(ApplicationContext context) {
        var policy = mock(ProxyRequestPolicy.class);
        when(policy.allows(any())).thenReturn(true);
        var permits = mock(LlmCallPermits.class);
        var permit = mock(LlmCallPermits.Permit.class);
        when(permit.revoked()).thenReturn(Mono.never());
        when(permits.acquire(anyLong(), any(), any(), any(), any())).thenReturn(permit);
        var mapper = context.getBean(ObjectMapper.class);
        var budgets = mock(ProxyBudgetGate.class);
        var meters = context.getBean(MeterRegistry.class);
        var ledger = new ProxyCallLedger(
                context.getBean(LlmUsageRecorder.class),
                context.getBean(AgentRuns.class),
                context.getBean(ReviewJobRepository.class),
                context.getBean(MentorTurnRepository.class),
                budgets,
                mapper,
                meters,
                context.getBean(PlatformTransactionManager.class));
        var service = new LlmProxyService(
                Tracer.NOOP,
                context.getBean(WebClient.Builder.class).clone().build(),
                context.getBean(LlmModelResolver.class),
                mapper,
                new ProxyAccounting(budgets, ledger, meters, mapper),
                policy,
                mock(ScanCallGuard.class),
                new ProxyRoutingService(
                        context.getBean(ReviewJobRepository.class),
                        context.getBean(MentorTurnRepository.class),
                        mapper,
                        context.getBean(LlmUsageEventRepository.class),
                        context.getBean(ReviewSnapshotQuery.class),
                        context.getBean(AgentRuns.class)),
                new ProxyCallsInFlight(12),
                new LlmConnectionHealth(CircuitBreakerRegistry.ofDefaults()),
                permits,
                new HephCallProperties(Duration.ofMinutes(5), Duration.ofMinutes(2)));
        return new LlmProxyController(service);
    }
}
