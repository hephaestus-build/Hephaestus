package de.tum.cit.aet.hephaestus.practices.profile;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.item.Item;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.ReviewOn;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.review.ReviewRepository;
import de.tum.cit.aet.hephaestus.practices.review.model.Review;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewKind;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStatus;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewStopReason;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

class PracticeProfileReviewIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    @MockitoBean
    EvidenceAuthorization evidenceAuthorization;

    @BeforeEach
    void permitStoredEvidence() {
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenAnswer(i -> {
                    long ws = i.getArgument(0);
                    Collection<Result> rows = i.getArgument(1);
                    return rows.stream()
                            .filter(r -> r.getWorkspaceId() == ws)
                            .map(Result::getId)
                            .collect(Collectors.toSet());
                });
    }

    @Autowired
    ItemIndexService index;

    @Autowired
    ItemRepository items;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    ReviewRepository reviews;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    String root() {
        return "/workspaces/" + scenario.workspace().getWorkspaceSlug();
    }

    WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri(root() + path)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }

    @Test
    void shouldListOnlyOwnDirectReviewChildrenWithTheirParentPullRequest() {
        var at = Instant.now();
        var parentResult = data.result(scenario, ResultOutcome.MET, at);
        var first = historyItem(scenario.account(), "scm.pull_request_review", scenario.item());
        var firstResult = data.result(first, ResultOutcome.NOT_MET, at.plusSeconds(1));
        var second = historyItem(scenario.account(), "scm.pull_request_review", scenario.item());
        var secondResult = data.result(second, ResultOutcome.MET, at.plusSeconds(2));
        var anotherDeveloper =
                historyItem(data.member(scenario.workspace().getId()), "scm.pull_request_review", scenario.item());
        data.result(anotherDeveloper, ResultOutcome.NOT_MET, at.plusSeconds(3));
        data.result(historyItem(scenario.account(), "scm.file", scenario.item()), ResultOutcome.MET, at.plusSeconds(4));
        var otherParent = historyItem(scenario.account(), "scm.pull_request", scenario.item());
        data.result(
                historyItem(scenario.account(), "scm.pull_request_review", otherParent.item()),
                ResultOutcome.NOT_MET,
                at.plusSeconds(5));
        data.result(data.scenario(), ResultOutcome.NOT_MET, at.plusSeconds(6));
        scenario.item().setRemovedAt(at.plusSeconds(7));
        items.saveAndFlush(scenario.item());

        get("/practices/items/" + scenario.item().getId() + "/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.content[*].review.id")
                .value(ids -> assertThat(ids)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactlyInAnyOrder(
                                parentResult.getReviewId().toString(),
                                firstResult.getReviewId().toString(),
                                secondResult.getReviewId().toString()));
        get("/practices/items/" + first.item().getId() + "/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(firstResult.getReviewId().toString());
    }

    @Test
    void shouldKeepReviewChildEvidenceHiddenInParentHistoryWhenNotAuthorized() {
        var child = historyItem(scenario.account(), "scm.pull_request_review", scenario.item());
        var result = data.result(child, ResultOutcome.NOT_MET, Instant.now());
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenReturn(Set.of());
        get("/practices/items/" + scenario.item().getId() + "/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(result.getReviewId().toString())
                .jsonPath("$.content[0].results[0].summary")
                .isEmpty()
                .jsonPath("$.content[0].results[0].problems.length()")
                .isEqualTo(0);
    }

    private Scenario historyItem(Account account, String kind, Item parent) {
        var item = new Item();
        item.setWorkspaceId(scenario.workspace().getId());
        item.setKind(new ItemKind(kind));
        item.setParentId(parent.getId());
        item.setSourceId(UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE);
        item.setCreatedAt(Instant.now().minusSeconds(3600));
        items.saveAndFlush(item);
        var definition = DefinitionTestFixtures.definition(
                item.getKind(), "Historical readback", "Check the captured work.", null);
        if (kind.equals("scm.pull_request_review")) {
            definition = DefinitionTestFixtures.withAbout(
                    DefinitionTestFixtures.withReviewOn(
                            definition,
                            new ReviewOn(List.of(new ItemEventName("scm.pull_request_review.submitted")), Map.of())),
                    AboutRole.REVIEWER);
        } else if (kind.equals("scm.file")) {
            definition = DefinitionTestFixtures.withReviewOn(definition, new ReviewOn(List.of(), Map.of()));
        }
        var practice = versions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "history-" + UUID.randomUUID(),
                item.getKind(),
                definition,
                null,
                0,
                account.getId()));
        return new Scenario(scenario.workspace(), account, practice, item);
    }

    @Test
    void profileListsDurableReviewIds() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        get("/practice-profile/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(r.getReviewId().toString());
    }

    @ParameterizedTest
    @EnumSource(ReviewStatus.class)
    void shouldShowAnOwnRequestReceiptWhenItHasNoResults(ReviewStatus status) {
        var review = requestedReview(
                scenario, status, Objects.requireNonNull(scenario.account().getId()));

        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.review.id")
                .isEqualTo(review.getId().toString())
                .jsonPath("$.review.status")
                .isEqualTo(status.name())
                .jsonPath("$.results.length()")
                .isEqualTo(0)
                .jsonPath("$.feedback.length()")
                .isEqualTo(0)
                .jsonPath("$.trace.item.id")
                .isEqualTo(scenario.item().getId().toString())
                .jsonPath("$.trace.review.id")
                .isEqualTo(review.getId().toString())
                .jsonPath("$.trace.results.length()")
                .isEqualTo(0)
                .jsonPath("$.trace.events.length()")
                .isEqualTo(0)
                .jsonPath("$.practiceOutcomes.met")
                .isEqualTo(0)
                .jsonPath("$.practiceOutcomes.notMet")
                .isEqualTo(0)
                .jsonPath("$.practiceOutcomes.doesNotApply")
                .isEqualTo(0)
                .jsonPath("$.practiceOutcomes.couldNotDetermine")
                .isEqualTo(0)
                .jsonPath("$.runtime")
                .doesNotExist()
                .jsonPath("$.usage")
                .doesNotExist();
    }

    @Test
    void shouldHideAnEmptyReceiptWhenAnotherMemberRequestedIt() {
        var review = requestedReview(
                scenario,
                ReviewStatus.QUEUED,
                Objects.requireNonNull(data.member(scenario.workspace().getId()).getId()));
        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotGrantAnEmptyReceiptThroughAdminAuthority() {
        var review = requestedReview(
                scenario,
                ReviewStatus.RUNNING,
                Objects.requireNonNull(data.member(scenario.workspace().getId()).getId()));
        http.get()
                .uri(root() + "/practice-profile/reviews/" + review.getId())
                .headers(h ->
                        h.setBearerAuth("mock-jwt-admin-" + scenario.account().getId()))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotGrantAnOwnReceiptInAnotherWorkspace() {
        var other = data.scenario();
        var review = requestedReview(
                other,
                ReviewStatus.QUEUED,
                Objects.requireNonNull(scenario.account().getId()));
        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldHideAnUnknownReviewReceipt() {
        get("/practice-profile/reviews/" + UUID.randomUUID())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldLimitAnEmptyReceiptTraceToItsRequestedItem() {
        var review = requestedReview(
                scenario,
                ReviewStatus.QUEUED,
                Objects.requireNonNull(scenario.account().getId()));
        var other = historyItem(scenario.account(), "scm.pull_request", scenario.item());
        get("/practices/items/" + other.item().getId() + "/own-trace?reviewId=" + review.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        get("/practices/items/" + scenario.item().getId() + "/own-trace?reviewId=" + review.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.review.id")
                .isEqualTo(review.getId().toString())
                .jsonPath("$.results.length()")
                .isEqualTo(0);
    }

    @Test
    void shouldNotTurnAnEventReviewIntoAnOwnRequestReceipt() {
        var review = requestedReview(
                scenario,
                ReviewStatus.QUEUED,
                Objects.requireNonNull(scenario.account().getId()));
        review.setOrigin(ReviewOrigin.EVENT);
        review.setRequestedById(null);
        reviews.saveAndFlush(review);
        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldNotGrantARepositoryScanThroughAnOwnItemRequestReceipt() {
        var repository = index.upsert(new ItemRegistration(
                scenario.workspace().getId(), new ItemKind("scm.repository"), 987_654_321L, null, null));
        var review = requestedReview(
                scenario,
                ReviewStatus.QUEUED,
                Objects.requireNonNull(scenario.account().getId()));
        review.setItemId(repository.itemId());
        review.setKind(ReviewKind.SCAN);
        review.setBudgetUsd(new BigDecimal("1.0000"));
        reviews.saveAndFlush(review);

        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void shouldKeepAnotherDevelopersResultsOutOfTheRequestersReceipt() {
        var other = new Scenario(
                scenario.workspace(), data.member(scenario.workspace().getId()), scenario.practice(), scenario.item());
        var result = data.result(other, ResultOutcome.NOT_MET, Instant.now());
        var review = reviews.findByIdAndWorkspaceId(
                        result.getReviewId(), scenario.workspace().getId())
                .orElseThrow();
        review.setRequestedById(scenario.account().getId());
        reviews.saveAndFlush(review);

        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results.length()")
                .isEqualTo(0)
                .jsonPath("$.feedback.length()")
                .isEqualTo(0)
                .jsonPath("$.trace.results.length()")
                .isEqualTo(0)
                .jsonPath("$.practiceOutcomes.notMet")
                .isEqualTo(0);
    }

    @Test
    void shouldPreserveOwnResultAccessWhenSomeoneElseRequestedTheReview() {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        var review = reviews.findByIdAndWorkspaceId(
                        result.getReviewId(), scenario.workspace().getId())
                .orElseThrow();
        review.setRequestedById(data.member(scenario.workspace().getId()).getId());
        reviews.saveAndFlush(review);

        get("/practice-profile/reviews/" + review.getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results.length()")
                .isEqualTo(1)
                .jsonPath("$.results[0].id")
                .isEqualTo(result.getId().toString());
    }

    @Test
    void shouldKeepEvidenceHiddenWhenTheRequesterReadsItsReceipt() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenReturn(Set.of());

        get("/practice-profile/reviews/" + result.getReviewId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.results[0].summary")
                .isEmpty()
                .jsonPath("$.results[0].problems.length()")
                .isEqualTo(0)
                .jsonPath("$.trace.results.length()")
                .isEqualTo(0);
    }

    private Review requestedReview(Scenario target, ReviewStatus status, long requester) {
        var at = Instant.now();
        return reviews.saveAndFlush(Review.builder()
                .id(UUID.randomUUID())
                .workspaceId(target.workspace().getId())
                .itemId(target.item().getId())
                .kind(ReviewKind.ITEM)
                .origin(ReviewOrigin.REQUEST)
                .requestedById(requester)
                .status(status)
                .orderedAt(at)
                .createdAt(at)
                .admittedAt(status == ReviewStatus.QUEUED ? null : at)
                .completedAt(status.isTerminal() ? at : null)
                .stopReason(
                        switch (status) {
                            case PARTIAL -> ReviewStopReason.RESULT_WITHHELD;
                            case FAILED -> ReviewStopReason.EXECUTION_FAILED;
                            case CANCELLED -> ReviewStopReason.CANCELLED_BY_ADMIN;
                            default -> null;
                        })
                .build());
    }

    @Test
    void foreignReviewIsNotFound() {
        var r = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        get("/practice-profile/reviews/" + r.getReviewId())
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
    }

    @Test
    void kindFilterIsAppliedBeforePagination() {
        data.result(scenario, ResultOutcome.MET, Instant.now());
        get("/practice-profile/reviews?itemKind=chat.thread&size=1")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0)
                .jsonPath("$.content.length()")
                .isEqualTo(0);
    }

    @Test
    void itemFilterKeepsOnlyTheReviewsOfThatWork() {
        var at = Instant.now();
        var first = data.result(scenario, ResultOutcome.NOT_MET, at);
        // One current result per work and practice: the second review replaces the first.
        data.end(first, at.plusMillis(500));
        var second = data.result(scenario, ResultOutcome.MET, at.plusSeconds(1));
        var other = historyItem(scenario.account(), "scm.issue", scenario.item());
        data.result(other, ResultOutcome.MET, at.plusSeconds(2));

        get("/practice-profile/reviews?itemId=" + scenario.item().getId() + "&size=1")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(second.getReviewId().toString());
        get("/practice-profile/reviews?itemId=" + scenario.item().getId())
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.content[*].review.id")
                .value(ids -> assertThat(ids)
                        .asInstanceOf(InstanceOfAssertFactories.list(String.class))
                        .containsExactly(
                                second.getReviewId().toString(),
                                first.getReviewId().toString()));
    }

    @Test
    void statusFilterIsAppliedBeforePagination() {
        var at = Instant.now();
        var complete = data.result(scenario, ResultOutcome.MET, at);
        data.end(complete, at.plusMillis(500));
        var partial = data.result(scenario, ResultOutcome.NOT_MET, at.plusSeconds(1));
        var review = reviews.findById(partial.getReviewId()).orElseThrow();
        review.setStatus(ReviewStatus.PARTIAL);
        review.setStopReason(ReviewStopReason.RESULT_WITHHELD);
        reviews.saveAndFlush(review);

        get("/practice-profile/reviews?status=PARTIAL&size=1")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(partial.getReviewId().toString())
                .jsonPath("$.content[0].review.status")
                .isEqualTo("PARTIAL");
        get("/practice-profile/reviews?status=COMPLETE")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(complete.getReviewId().toString());
        get("/practice-profile/reviews?status=PARTIAL&status=COMPLETE&order=OLDEST")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2)
                .jsonPath("$.content[0].review.id")
                .isEqualTo(complete.getReviewId().toString())
                .jsonPath("$.content[1].review.id")
                .isEqualTo(partial.getReviewId().toString());
        get("/practice-profile/reviews?itemKind=chat.thread&itemKind=scm.pull_request")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(2);
        get("/practice-profile/reviews?status=FAILED")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(0);
    }

    @Test
    void shouldKeepOwnReviewMetadataWhenItsTextIsHidden() {
        data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        Mockito.when(evidenceAuthorization.permitsAll(
                        ArgumentMatchers.anyLong(), ArgumentMatchers.anyCollection(), ArgumentMatchers.any()))
                .thenReturn(Set.of());
        get("/practice-profile/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1)
                .jsonPath("$.content[0].results[0].summary")
                .isEmpty()
                .jsonPath("$.content[0].results[0].problems.length()")
                .isEqualTo(0);
    }

    @Test
    void endedResultsStillHaveAReviewHistory() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        data.end(r, Instant.now().plusSeconds(1));
        get("/practice-profile/reviews")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(1);
    }

    @Test
    void shouldResolveRetainedFeedbackWorkForAMemberWithoutProviderCapture() {
        long workspace = scenario.workspace().getId();
        long source = 9_007_199_254_740_993L;
        var item = index.upsert(new ItemRegistration(workspace, new ItemKind("scm.pull_request"), source, null, null));
        index.remove(workspace, item.itemId(), Instant.now());
        String root = "/workspaces/" + scenario.workspace().getWorkspaceSlug() + "/practices/items/legacy-feedback/";
        http.get()
                .uri(root + "scm.pull_request/" + source)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.itemId")
                .isEqualTo(Long.toString(item.itemId()));
        http.get()
                .uri(root + "scm.issue/" + source)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        http.get()
                .uri(root + "scm.repository/" + source)
                .headers(h -> h.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange()
                .expectStatus()
                .isNotFound()
                .expectBody(Void.class);
        http.get()
                .uri(root + "scm.pull_request/" + source)
                .exchange()
                .expectStatus()
                .isUnauthorized()
                .expectBody(Void.class);
    }
}
