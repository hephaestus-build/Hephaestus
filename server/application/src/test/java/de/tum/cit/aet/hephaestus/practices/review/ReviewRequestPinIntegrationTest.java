package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeReadSource;
import de.tum.cit.aet.hephaestus.practices.PracticeReads;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.scan.ScanReportTestData;
import de.tum.cit.aet.hephaestus.practices.spi.BoundModelSlot;
import de.tum.cit.aet.hephaestus.practices.spi.FrozenModel;
import de.tum.cit.aet.hephaestus.practices.spi.ModelKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReadNeed;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCaptureDecision;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRequest;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewService;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.testconfig.WorkspaceTestFixtures;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class ReviewRequestPinIntegrationTest extends AbstractResultAdmissionIntegrationTest {
    private static final String COMMIT = "a".repeat(40);
    private static final String GITLAB = "https://gitlab.example.test";

    @Autowired
    private ScanReportTestData data;

    @Autowired
    private ReviewService service;

    @Autowired
    private ReviewRepository reviews;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ReviewRequestService requests;

    @Autowired
    private ReviewIntegrationData reviewData;

    @Autowired
    private ReviewPracticeRepository assignments;

    @Autowired
    private PracticeVersionService practiceVersions;

    @Autowired
    private ItemIndexService writer;

    @Autowired
    private ConnectionRepository connections;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private RepositoryRepository repositories;

    @Autowired
    private RepositoryToMonitorRepository monitors;

    private long workspaceId;
    private long accountId;
    private ItemRef repository;
    private ItemSnapshot pinned;

    @BeforeEach
    void setUp() {
        var scenario = data.scenario();
        var workspace = scenario.owner().workspace();
        workspaceId = workspace.getId();
        accountId = Objects.requireNonNull(scenario.owner().account().getId());
        repository = monitoredRepository(workspace);
        pinned = ItemFixtures.snapshot(repository, Map.of("defaultBranch", "main"))
                .version(new ItemVersion(COMMIT, null))
                .build();
        when(budget.canRequest(eq(workspaceId), eq(ReviewKind.SCAN), any())).thenReturn(true);
        when(models.models(eq(workspaceId), anyList()))
                .thenReturn(List.of(new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of()))));
    }

    @Test
    void shouldLeaveTheWorkspaceUnlockedWhenTheScanPinFetches() throws Exception {
        var pinning = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var transactionAtPin = new AtomicReference<Boolean>();
        doAnswer(call -> {
                    transactionAtPin.set(TransactionSynchronizationManager.isActualTransactionActive());
                    pinning.countDown();
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                    return pinned;
                })
                .when(productItems)
                .pin(eq(repository), isNull());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var requested = executor.submit(() -> service.request(scan(UUID.randomUUID())));
            assertThat(pinning.await(30, TimeUnit.SECONDS)).isTrue();

            var locked = new TransactionTemplate(transactions)
                    .execute(status -> jdbc.queryForObject(
                            "SELECT id FROM workspace WHERE id = ? FOR UPDATE NOWAIT", Long.class, workspaceId));
            release.countDown();

            assertThat(locked).isEqualTo(workspaceId);
            assertThat(transactionAtPin.get()).isFalse();
            var review = reviews.findById(requested.get(30, TimeUnit.SECONDS)).orElseThrow();
            assertThat(review.getItemVersion()).isEqualTo(COMMIT);
            assertThat(review.getChecks().path("ref").asString()).isEqualTo("main");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldReturnTheReviewThatAReplayRecordedWhileThePinFetched() throws Exception {
        var key = UUID.randomUUID();
        var pin = blockFirstPin();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.request(scan(key)));
            assertThat(pin.pinning().await(30, TimeUnit.SECONDS)).isTrue();
            var replay = service.request(scan(key));
            pin.release().countDown();

            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(replay);
            assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(workspaceId, repository.itemId()))
                    .singleElement()
                    .satisfies(review -> assertThat(review.getId()).isEqualTo(replay));
        } finally {
            pin.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRefuseAScanWhenAnotherScanStartedWhileThePinFetched() throws Exception {
        var pin = blockFirstPin();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> service.request(scan(UUID.randomUUID())));
            assertThat(pin.pinning().await(30, TimeUnit.SECONDS)).isTrue();
            var other = service.request(scan(UUID.randomUUID()));
            pin.release().countDown();

            assertThatThrownBy(() -> first.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ReviewRequestRejected.class,
                            refusal ->
                                    assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.SCAN_ACTIVE));
            assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(workspaceId, repository.itemId()))
                    .singleElement()
                    .satisfies(review -> assertThat(review.getId()).isEqualTo(other));
        } finally {
            pin.release().countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRefuseAnItemReviewOfARepositoryBeforeItsPin() {
        doThrow(new AssertionError("A refused request pins nothing"))
                .when(productItems)
                .pin(any(), any());

        assertThatThrownBy(() -> requests.request(workspaceId, repository.itemId(), accountId))
                .isInstanceOfSatisfying(
                        ReviewRequestRejected.class,
                        refusal ->
                                assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.ITEM_INELIGIBLE));
    }

    @Test
    void shouldReadTheQueuedSourcesWithNoTransactionWhenAnItemReviewIsRequested() {
        var review = itemReview();
        var transactionAtRead = recordFingerprintReads(review.ref());

        var id = service.request(review.request());

        assertThat(transactionAtRead).containsExactly(false);
        var decision = Objects.requireNonNull(assignments
                .findByWorkspaceIdAndReviewIdAndPracticeId(
                        review.workspace(), id, review.practice().getId())
                .orElseThrow()
                .getCaptureDecision());
        assertThat(decision.path(ReviewCaptureDecision.SOURCE_FINGERPRINTS).properties())
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.getValue().asString()).isEqualTo("read"));
    }

    @Test
    void shouldRefuseARequestThatStartsInsideATransaction() {
        var review = itemReview();
        doThrow(new AssertionError("A refused request pins nothing"))
                .when(productItems)
                .pin(any(), any());

        assertThatThrownBy(() ->
                        new TransactionTemplate(transactions).execute(status -> service.request(review.request())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                        review.workspace(), review.ref().itemId()))
                .isEmpty();
    }

    @Test
    void shouldAskForTheItemReviewAgainWhenAPracticeReadsAnotherSourceDuringTheEarlyRead() throws Exception {
        var review = itemReview();
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> {
                    reading.countDown();
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                    Set<SourceKind> kinds = call.getArgument(1);
                    return kinds.stream().collect(Collectors.toMap(kind -> kind, kind -> "read"));
                })
                .when(productItems)
                .fingerprints(eq(review.ref()), any(), isNull());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var requested = executor.submit(() -> service.request(review.request()));
            assertThat(reading.await(30, TimeUnit.SECONDS)).isTrue();
            var reads = review.definition().reads();
            practiceVersions.append(
                    review.practice(),
                    withReads(
                            review.definition(),
                            new PracticeReads(
                                    reads.contract(),
                                    Stream.concat(
                                                    reads.sources().stream(),
                                                    Stream.of(new PracticeReadSource(
                                                            new SourceKind("scm.review-threads"), ReadNeed.REQUIRED)))
                                            .toList())),
                    null);
            release.countDown();

            assertThatThrownBy(() -> requested.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOfSatisfying(
                            ReviewRequestRejected.class,
                            refusal -> assertThat(refusal.reason())
                                    .isEqualTo(ReviewRequestRejected.Reason.PRACTICES_CHANGED));
            assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(
                            review.workspace(), review.ref().itemId()))
                    .isEmpty();
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private record ItemReview(
            long workspace,
            Practice practice,
            PracticeDefinition definition,
            ItemRef ref,
            ItemSnapshot pinned,
            ReviewRequest request) {}

    /** An item review of a practice about no role, so the practice is queued whoever authored the work. */
    private ItemReview itemReview() {
        var definition = withReads(
                DefinitionTestFixtures.definition(),
                DefinitionTestFixtures.definition().reads());
        var scenario = reviewData.scenario(definition);
        long workspace = scenario.workspace().getId();
        var ref = productItems.require(workspace, scenario.item().getId());
        var snapshot = ItemFixtures.snapshot(ref, Map.of("state", "OPEN", "draft", "false"))
                .version(new ItemVersion("selected-version", null))
                .build();
        doReturn(snapshot).when(productItems).pin(eq(ref), isNull());
        when(budget.canRequest(workspace, ReviewKind.ITEM, null)).thenReturn(true);
        when(models.models(eq(workspace), anyList()))
                .thenReturn(List.of(new BoundModelSlot(
                        "review",
                        ModelKind.CHAT,
                        new FrozenModel("instance:1", "OPENAI_COMPLETIONS", "test-model", null, Map.of()))));
        var request = ReviewRequest.builder()
                .workspaceId(workspace)
                .itemId(ref.itemId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(scenario.account().getId())
                .practiceIds(List.of(scenario.practice().getId()))
                .build();
        return new ItemReview(workspace, scenario.practice(), definition, ref, snapshot, request);
    }

    /** The definition about no role, with {@code reads}. */
    private static PracticeDefinition withReads(PracticeDefinition original, PracticeReads reads) {
        return new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                null,
                reads,
                original.reviewedBy(),
                original.criteria(),
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
    }

    /** Answers every fingerprint read and records whether a transaction was active at it. */
    private List<Boolean> recordFingerprintReads(ItemRef ref) {
        var transactionAtRead = new CopyOnWriteArrayList<Boolean>();
        doAnswer(call -> {
                    transactionAtRead.add(TransactionSynchronizationManager.isActualTransactionActive());
                    Set<SourceKind> kinds = call.getArgument(1);
                    return kinds.stream().collect(Collectors.toMap(kind -> kind, kind -> "read"));
                })
                .when(productItems)
                .fingerprints(eq(ref), any(), isNull());
        return transactionAtRead;
    }

    @Test
    void shouldReturnAReplayWithoutPinningWhenItsItemIsGone() {
        doReturn(pinned).when(productItems).pin(eq(repository), isNull());
        var key = UUID.randomUUID();
        var first = service.request(scan(key));
        jdbc.update("UPDATE item SET removed_at = now() WHERE id = ?", repository.itemId());
        doThrow(new AssertionError("A replay pins nothing")).when(productItems).pin(any(), any());

        assertThat(service.request(scan(key))).isEqualTo(first);
    }

    @Test
    void shouldRefuseAScanOfAnotherBranchOrVersionWithoutRecordingIt() {
        doReturn(pinned).when(productItems).pin(eq(repository), isNull());
        var otherBranch = scan(UUID.randomUUID(), "release", null);
        var otherVersion = scan(UUID.randomUUID(), null, "b".repeat(40));

        assertThatThrownBy(() -> service.request(otherBranch))
                .isInstanceOfSatisfying(ReviewRequestRejected.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.REF_UNSUPPORTED);
                    assertThat(refusal.getBody().getProperties()).containsEntry("supportedRef", "main");
                });
        assertThatThrownBy(() -> service.request(otherVersion))
                .isInstanceOfSatisfying(ReviewRequestRejected.class, refusal -> {
                    assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.VERSION_CHANGED);
                    assertThat(refusal.getBody().getProperties()).containsEntry("currentVersion", COMMIT);
                });
        assertThat(reviews.findByWorkspaceIdAndItemIdOrderByOrderedAtDesc(workspaceId, repository.itemId()))
                .isEmpty();
    }

    private record BlockedPin(CountDownLatch pinning, CountDownLatch release) {}

    /** The first pin of the repository waits for {@code release}; later pins return at once. */
    private BlockedPin blockFirstPin() {
        var pin = new BlockedPin(new CountDownLatch(1), new CountDownLatch(1));
        var calls = new AtomicInteger();
        doAnswer(call -> {
                    if (calls.getAndIncrement() == 0) {
                        pin.pinning().countDown();
                        assertThat(pin.release().await(30, TimeUnit.SECONDS)).isTrue();
                    }
                    return pinned;
                })
                .when(productItems)
                .pin(eq(repository), isNull());
        return pin;
    }

    /** A repository of the workspace's GitLab connection that the workspace monitors, so a scan admits it. */
    private ItemRef monitoredRepository(Workspace workspace) {
        WorkspaceTestFixtures.connectGitLab(connections, workspace, GITLAB);
        var provider = providers
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, GITLAB)
                .orElseGet(() -> providers.saveAndFlush(new IdentityProvider(IdentityProviderType.GITLAB, GITLAB)));
        var row = new Repository();
        row.setNativeId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE));
        row.setProvider(provider);
        row.setName("source");
        row.setNameWithOwner(workspace.getWorkspaceSlug() + "/source");
        row.setHtmlUrl(GITLAB + "/" + workspace.getWorkspaceSlug() + "/source");
        row.setDefaultBranch("main");
        row.setVisibility(Repository.Visibility.PUBLIC);
        row.setPushedAt(Instant.now());
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        row = repositories.saveAndFlush(row);
        monitors.saveAndFlush(
                WorkspaceTestFixtures.repositoryMonitor(workspace, row.getNameWithOwner(), row.getNativeId()));
        return writer.upsert(new ItemRegistration(workspaceId, ScmItemEvents.REPOSITORY, row.getId(), null, null));
    }

    private ReviewRequest scan(UUID key) {
        return scan(key, null, null);
    }

    private ReviewRequest scan(UUID key, @Nullable String scanRef, @Nullable String expectedVersion) {
        return ReviewRequest.builder()
                .workspaceId(workspaceId)
                .itemId(repository.itemId())
                .kind(ReviewKind.SCAN)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(accountId)
                .budgetUsd(new BigDecimal("1.0000"))
                .requestKey(key)
                .requestDigest("d".repeat(64))
                .practiceIds(List.of())
                .reuse(true)
                .scanRef(scanRef)
                .expectedVersion(expectedVersion)
                .build();
    }
}
