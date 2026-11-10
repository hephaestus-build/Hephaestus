package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmApiProtocol;
import de.tum.cit.aet.hephaestus.agent.config.ConfigSnapshot;
import de.tum.cit.aet.hephaestus.agent.gateway.SandboxGatewayProperties;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRunKind;
import de.tum.cit.aet.hephaestus.agent.job.ReviewRuntimeRepository;
import de.tum.cit.aet.hephaestus.agent.job.RuntimeJobTestData;
import de.tum.cit.aet.hephaestus.agent.run.AgentRunRef;
import de.tum.cit.aet.hephaestus.agent.run.AgentRuns;
import de.tum.cit.aet.hephaestus.agent.scan.ScanBudget;
import de.tum.cit.aet.hephaestus.agent.usage.LlmPriceSnapshot;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageEventRepository;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.core.spi.UsagePricingState;
import de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties;
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
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class ScanCallGuardTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("fafb277e-9c90-58ff-9167-1f1b3b0dce88");

    private final ReviewJobRepository jobs = mock(ReviewJobRepository.class);
    private final AgentRuns runs = mock(AgentRuns.class);
    private final ReviewSnapshotQuery reviews = mock(ReviewSnapshotQuery.class);
    private final ScanBudget budget = mock(ScanBudget.class);
    private final ScanCallLeases leases = mock(ScanCallLeases.class);
    /** The lease row as the database holds it: one call at a time, whichever replica asks. */
    private final AtomicReference<@Nullable UUID> leaseRow = new AtomicReference<>();

    private final LlmUsageEventRepository usage = mock(LlmUsageEventRepository.class);
    private long calls;
    private long outputTokens;
    private final JsonMapper mapper = new JsonMapper();
    private final ReviewJob job = RuntimeReviewTestData.job();
    private ReviewSnapshot scan;
    private static final PracticeReviewProperties PRACTICE_REVIEW =
            new PracticeReviewProperties(false, 15, 5, null, 36, 48_000, Duration.ofMinutes(10));
    private final ScanCallGuard guard = new ScanCallGuard(
            jobs,
            runs,
            reviews,
            budget,
            new SandboxGatewayProperties(8081, 10000, 120, 1000, 4096),
            mapper,
            PRACTICE_REVIEW,
            usage,
            leases);

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
                new ProxyRouting.BilledAttempt(UsageSourceType.REVIEW_JOB, job.getId(), 0, LEASE),
                workDeadline,
                100L);
    }

    @Test
    void shouldStopWaitingForTheAttemptsPreviousCallAtTheWorkDeadlineWithoutTakingItsLease() throws Exception {
        byte[] body = "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);
        var executor = Executors.newSingleThreadExecutor();
        try (var held = guard.acquire(route(), body).orElseThrow()) {
            assertThat(held).isNotNull();
            // The waiting call runs on a thread of its own, as a call that reached another replica would.
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
        lenient().when(leases.take(any(UUID.class), anyLong())).thenAnswer(call -> {
            UUID lease = UUID.randomUUID();
            return leaseRow.compareAndSet(null, lease) ? Optional.of(lease) : Optional.empty();
        });
        lenient()
                .doAnswer(call -> {
                    leaseRow.compareAndSet(call.getArgument(2), null);
                    return null;
                })
                .when(leases)
                .release(any(UUID.class), anyLong(), any(UUID.class));
        lenient().when(budget.callCap(ArgumentMatchers.anyInt())).thenReturn(36L);
        job.setStatus(ReviewJobStatus.RUNNING);
        job.setRunId(LEASE);
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
                60)));
        when(jobs.findByIdAndWorkspaceId(job.getId(), 1L)).thenReturn(Optional.of(job));
        // The database answers from the run: a replaced attempt or another run holds no live lease.
        when(runs.isCurrent(any(AgentRunRef.class), anyInt()))
                .thenAnswer(call -> ReviewRunKind.epoch(job.getAttempt()) == (int) call.getArgument(1)
                        && call.<AgentRunRef>getArgument(0).id().equals(job.getRunId()));
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
        scan = new ReviewSnapshot(
                job.getReviewId(),
                1,
                RuntimeReviewTestData.ITEM,
                ReviewKind.SCAN,
                new ItemVersion("test:1", null),
                ReviewOrigin.REQUEST,
                1,
                List.of(pinned));
        when(reviews.find(1, job.getReviewId())).thenReturn(Optional.of(scan));
        lenient().when(reviews.budgetUsd(1, job.getReviewId())).thenReturn(Optional.of(BigDecimal.ONE));
        lenient()
                .when(budget.spent(1, job.getReviewId()))
                .thenReturn(new ReviewRuntimeRepository.Spend(BigDecimal.ZERO, true));
        lenient().when(usage.countReviewCalls(anyLong(), any(), anyInt())).thenAnswer(invocation -> reviewCalls());
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

    /** An earlier call whose usage was not measured counts at its upper bound and blocks no later call. */
    @Test
    void permitsTheNextCallWithinTheCapWhenEarlierCallsCountAtTheirUpperBound() {
        byte[] body = "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8);
        when(budget.spent(1, job.getReviewId()))
                .thenReturn(new ReviewRuntimeRepository.Spend(new BigDecimal("0.999000"), true));
        try (var lease = guard.acquire(route(), body).orElseThrow()) {
            assertThat(lease).isNotNull();
        }

        // The next call's own bound, 17 bytes and 10 output tokens at $1 per 1M, must fit as well.
        when(budget.spent(1, job.getReviewId()))
                .thenReturn(new ReviewRuntimeRepository.Spend(new BigDecimal("0.999974"), true));
        refused("{\"max_tokens\":10}");
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
    void shouldCountTheAttemptsCallsWithoutUsageAgainstTheScanCallCap() {
        // Every call the attempt opened is a row, also one whose provider reported no usage.
        calls = 36;
        refused("{\"max_tokens\":10}");

        calls = 35;
        assertThat(guard.acquire(route(), "{\"max_tokens\":10}".getBytes(StandardCharsets.UTF_8)))
                .isPresent()
                .get()
                .satisfies(ScanCallGuard.Lease::close);
    }

    /** A request above the frozen limit is forwarded clamped to it, so its bound is the frozen limit's. */
    @Test
    void boundsAnOutputAboveTheFrozenLimitAtTheFrozenLimit() {
        byte[] body = "{\"max_tokens\":16384}".getBytes(StandardCharsets.UTF_8);
        // 20 bytes and 100 output tokens at $1 per 1M: 0.000120.
        when(budget.spent(1, job.getReviewId()))
                .thenReturn(new ReviewRuntimeRepository.Spend(new BigDecimal("0.999880"), true));
        try (var lease = guard.acquire(route(), body).orElseThrow()) {
            assertThat(lease).isNotNull();
        }

        when(budget.spent(1, job.getReviewId()))
                .thenReturn(new ReviewRuntimeRepository.Spend(new BigDecimal("0.999881"), true));
        refused("{\"max_tokens\":16384}");
    }

    @Test
    void refusesAnOutputLimitThatIsNotAPositiveWholeNumber() {
        refused("{\"max_tokens\":0}");
        refused("{\"max_tokens\":1.5}");
    }

    @Test
    void refusesMultimediaWhoseCostCannotBeBoundedByTextBytes() {
        refused(
                "{\"messages\":[{\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.org/image\"}}]}]}");
    }

    @Test
    void refusesAReplacedAttempt() {
        RuntimeJobTestData.attempt(job, 1);
        refused("{}");
    }

    /** The same review, on one item instead of a scan: no scan budget, only the attempt's work bound. */
    private void ordinaryReview() {
        when(reviews.find(1, job.getReviewId()))
                .thenReturn(Optional.of(new ReviewSnapshot(
                        scan.id(),
                        scan.workspaceId(),
                        scan.item(),
                        ReviewKind.ITEM,
                        scan.version(),
                        scan.origin(),
                        scan.rolloutRevision(),
                        scan.practices(),
                        scan.eventId(),
                        scan.evidenceExpiresAt())));
    }

    private long loopCalls() {
        return (long) job.getInput().assignments().size() * PRACTICE_REVIEW.practiceModelCalls();
    }

    private long loopOutputTokens() {
        return (long) job.getInput().assignments().size() * PRACTICE_REVIEW.practiceOutputTokens();
    }

    private void refusedForWork(ProxyRouting route) {
        assertThatThrownBy(() -> guard.acquire(route, "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                    assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(failure.getReason()).isEqualTo(ScanCallGuard.ATTEMPT_WORK_SPENT);
                });
    }

    @Test
    void shouldServeAReviewThatStaysWithinTwiceWhatItsLoopMaySpend() {
        ordinaryReview();
        calls = ScanCallGuard.WORK_MARGIN * loopCalls() - 1;
        outputTokens = ScanCallGuard.WORK_MARGIN * loopOutputTokens() - 1;

        assertThat(guard.acquire(route(), "{}".getBytes(StandardCharsets.UTF_8)))
                .isEmpty();
    }

    @Test
    void shouldRefuseADirectCallPastTwiceTheCallsOrTokensThatTheLoopMaySpend() {
        ordinaryReview();
        calls = ScanCallGuard.WORK_MARGIN * loopCalls();
        refusedForWork(route());

        calls = 0;
        outputTokens = ScanCallGuard.WORK_MARGIN * loopOutputTokens();
        refusedForWork(route());
    }

    private LlmUsageEventRepository.ReviewCalls reviewCalls() {
        long counted = calls;
        long output = outputTokens;
        return new LlmUsageEventRepository.ReviewCalls() {
            @Override
            public long getCalls() {
                return counted;
            }

            @Override
            public long getOutputTokens() {
                return output;
            }
        };
    }
}
