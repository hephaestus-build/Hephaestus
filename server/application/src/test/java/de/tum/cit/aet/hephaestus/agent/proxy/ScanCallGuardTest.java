package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.scan.ScanBudget;
import de.tum.cit.aet.hephaestus.agent.scan.ScanModelUnavailableException;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.PinnedPractice;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewJobStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewMethod;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewSnapshotQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ScanCallGuardTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final ScanBudget budget = mock(ScanBudget.class);
    private final JsonMapper mapper = new JsonMapper();
    private final ReviewJob job = RuntimeReviewTestData.job();
    private final ScanCallGuard guard =
            new ScanCallGuard(jobs, reviews, budget, new SandboxGatewayProperties(8081, 10000, 120), mapper);

    private ProxyRouting route() {
        return route(Instant.now().plusSeconds(60));
    }

    private ProxyRouting route(Instant workDeadline) {
        return new ProxyRouting(
                "job",
                "openai-completions",
                "https://model.example",
                UsageFundingSource.INSTANCE,
                1L,
                1L,
                1L,
                new ProxyRouting.BilledAttempt(UsageSourceType.REVIEW_JOB, job.getId(), 0, BigDecimal.ZERO, LEASE),
                null,
                null,
                workDeadline);
    }

    @Test
    void shouldStopWaitingForTheAttemptsPreviousCallAtTheWorkDeadlineWithoutTakingItsLease() throws Exception {
        byte[] body = "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);
        var executor = Executors.newSingleThreadExecutor();
        try (var held = guard.acquire(route(), body).orElseThrow()) {
            assertThat(held).isNotNull();
            // The lock is reentrant, so the waiting call runs on a thread of its own.
            Future<?> waiting =
                    executor.submit(() -> guard.acquire(route(Instant.now().plusSeconds(1)), body));

            assertThatThrownBy(() -> waiting.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ResponseStatusException.class)
                    .cause()
                    .satisfies(failure -> {
                        var refusal = (ResponseStatusException) failure;
                        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                        assertThat(refusal.getReason()).isEqualTo(LlmProxyService.WORK_TIME_OVER);
                    });
        } finally {
            executor.shutdownNow();
        }
        // The refused waiter took no lease and released none: after its holder released it, the next call of the
        // attempt gets the lease at once.
        var next = Executors.newSingleThreadExecutor();
        try {
            assertThat(next.submit(() -> {
                                try (var lease = guard.acquire(route(), body).orElseThrow()) {
                                    return lease != null;
                                }
                            })
                            .get(10, TimeUnit.SECONDS))
                    .isTrue();
        } finally {
            next.shutdownNow();
        }
    }

    @Test
    void shouldRefuseAWaitingCallThatIsInterruptedAndKeepItsInterrupt() throws Exception {
        byte[] body = "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);
        var executor = Executors.newSingleThreadExecutor();
        try (var held = guard.acquire(route(), body).orElseThrow()) {
            assertThat(held).isNotNull();
            var interrupted = new CompletableFuture<Boolean>();
            Future<?> waiting = executor.submit(() -> {
                Thread.currentThread().interrupt();
                try {
                    guard.acquire(route(), body);
                    interrupted.complete(false);
                } catch (ResponseStatusException refusal) {
                    interrupted.complete(refusal.getStatusCode() == HttpStatus.FORBIDDEN
                            && Thread.currentThread().isInterrupted()
                            && refusal.getCause() instanceof InterruptedException);
                }
            });
            waiting.get(10, TimeUnit.SECONDS);
            assertThat(interrupted.get()).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @BeforeEach
    void setup() {
        lenient().when(budget.callCap(ArgumentMatchers.anyInt())).thenReturn(36L);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setLeaseId(LEASE);
        job.setLeasedUntil(Instant.now().plus(Duration.ofMinutes(10)));
        var price = new LlmPriceSnapshot(
                UsageFundingSource.INSTANCE,
                UsagePricingState.PRICED,
                1L,
                null,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                null);
        job.setConfigSnapshot(mapper.valueToTree(new ConfigSnapshot(
                ConfigSnapshot.SCHEMA_VERSION,
                new de.tum.cit.aet.hephaestus.agent.config.FrozenModel(
                        LlmApiProtocol.parse("openai-completions").orElseThrow(),
                        "https://model.example",
                        "test",
                        UsageFundingSource.INSTANCE,
                        1L,
                        1L,
                        null,
                        DataHandlingTier.IN_HOUSE,
                        null,
                        price,
                        false),
                1000,
                100,
                60,
                null)));
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        // The database answers from the row: a replaced attempt or another lease holds no live lease.
        when(jobs.hasLiveLease(eq(job.getId()), eq(1L), anyInt(), any(UUID.class)))
                .thenAnswer(call -> job.getAttempt() == (int) call.getArgument(2)
                        && call.getArgument(3).equals(job.getLeaseId()));
        var old = RuntimeReviewTestData.assignment(null, null).practice();
        var model = new FrozenModel(
                "instance:1",
                "openai-completions",
                "test",
                null,
                Map.of("contextWindow", mapper.valueToTree(1000), "maxOutputTokens", mapper.valueToTree(100)));
        var pinned = new PinnedPractice(
                old.practiceId(),
                old.practiceVersionId(),
                old.definition(),
                old.reviewDigest(),
                new ReviewMethod(1, "b".repeat(64), List.of(new BoundModelSlot("review", ModelKind.CHAT, model))));
        when(reviews.find(1, job.getReviewId()))
                .thenReturn(Optional.of(new ReviewSnapshot(
                        job.getReviewId(),
                        1,
                        RuntimeReviewTestData.ITEM,
                        ReviewKind.SCAN,
                        new ItemVersion("test:1", null),
                        ReviewOrigin.REQUEST,
                        1,
                        List.of(pinned))));
        lenient().when(reviews.budgetUsd(1, job.getReviewId())).thenReturn(Optional.of(BigDecimal.ONE));
        lenient()
                .when(budget.spent(1, job.getReviewId()))
                .thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, true));
        lenient().when(budget.inFlight(any(), any())).thenReturn(BigDecimal.ZERO);
    }

    private void refused(String json) {
        assertThatThrownBy(() -> guard.acquire(route(), json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED));
    }

    @Test
    void permitsBoundedTextAndReleasesItsLease() {
        try (var lease = guard.acquire(route(), "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8))
                .orElseThrow()) {
            assertThat(lease).isNotNull();
        }
        try (var lease = guard.acquire(route(), "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8))
                .orElseThrow()) {
            assertThat(lease).isNotNull();
        }
    }

    @Test
    void refusesTheNextCallAfterTheCapIsSpent() {
        when(budget.spent(1, job.getReviewId())).thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ONE, true));
        refused("{\"max_tokens\":10}");
    }

    @Test
    void refusesUnverifiableSpend() {
        when(budget.spent(1, job.getReviewId())).thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, false));
        refused("{}");
    }

    @Test
    void refusesUnavailableFrozenPricing() {
        when(budget.inFlight(any(), any())).thenThrow(new ScanModelUnavailableException("Unpriced model"));
        refused("{}");
    }

    @Test
    void programmingFailureDoesNotBecomeABudgetRefusal() {
        var bug = new IllegalStateException("Invariant failed");
        when(budget.inFlight(any(), any())).thenThrow(bug);
        assertThatThrownBy(() -> guard.acquire(route(), "{}".getBytes(StandardCharsets.UTF_8)))
                .isSameAs(bug);
    }

    @Test
    void refusesAnOutputAboveTheFrozenLimit() {
        refused("{\"max_tokens\":101}");
        refused("{\"generationConfig\":{\"maxOutputTokens\":101}}");
    }

    @Test
    void refusesMultimediaWhoseCostCannotBeBoundedByTextBytes() {
        refused(
                "{\"messages\":[{\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.org/image\"}}]}]}");
    }

    @Test
    void refusesAReplacedAttempt() {
        job.setAttempt(1);
        refused("{}");
    }
}
