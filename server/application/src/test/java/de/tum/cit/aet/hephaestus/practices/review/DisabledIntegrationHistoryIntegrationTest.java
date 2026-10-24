package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.integration.chat.item.ThreadEvents;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewTimingFactsQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@TestPropertySource(
        properties = {"hephaestus.integration.slack.enabled=false", "hephaestus.integration.outline.enabled=false"})
class DisabledIntegrationHistoryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    ItemIndexService index;

    @Autowired
    ItemRepository storedItems;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    ProductReferenceQuery references;

    @Autowired
    ProductItemQuery items;

    @Autowired
    PullRequestRepository pulls;

    @Autowired
    ReviewTimingFactsQuery timing;

    @Autowired
    WebTestClient http;

    Scenario scenario;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
    }

    @Test
    void shouldReadMixedHistoryWithoutEnablingNewCaptureForDisabledIntegrations() {
        var github = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        var thread = historyItem(ThreadEvents.THREAD, 101);
        var document = historyItem(DocumentEvents.DOCUMENT, 102);
        var refs = List.of(github, thread, document);
        for (var ref : refs) {
            var result =
                    data.result(history(ref), ResultOutcome.MET, Instant.now().minusSeconds(60));
            get("/practices/reviews/" + result.getReviewId())
                    .expectStatus()
                    .isOk()
                    .expectBody()
                    .jsonPath("$.item.id")
                    .isEqualTo(Long.toString(ref.itemId()));
        }
        var named = references.items(
                scenario.workspace().getId(), refs.stream().map(ItemRef::itemId).toList());
        assertThat(named).containsOnlyKeys(refs.stream().map(ItemRef::itemId).toArray(Long[]::new));
        assertThat(Objects.requireNonNull(named.get(github.itemId())).label()).isEqualTo("#5");
        assertThat(Objects.requireNonNull(named.get(thread.itemId())).label()).isEqualTo("Conversation thread");
        assertThat(Objects.requireNonNull(named.get(document.itemId())).label()).isEqualTo("Document");
        for (var ref : List.of(thread, document)) {
            var label = Objects.requireNonNull(named.get(ref.itemId()));
            assertThat(label.url()).isNull();
            assertThatThrownBy(() -> items.pin(ref, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("The item has no unambiguous source.");
        }
        for (var route : List.of(
                "/practice-groups/standings",
                "/practices/standings",
                "/practice-profile/overview",
                "/practices/reviews",
                "/practices/results")) {
            get(route).expectStatus().isOk().expectBody();
        }
        var foreign = data.scenario();
        assertThat(references.items(
                        foreign.workspace().getId(),
                        refs.stream().map(ItemRef::itemId).toList()))
                .isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = IdentityProviderType.class,
            names = {"GITHUB", "GITLAB"})
    void shouldKeepMergeTimeUnknownWhenAMergedPullRequestHasNoTimestamp(IdentityProviderType provider) {
        var ref = lookup.work(scenario.workspace(), provider, true);
        var pull = pulls.findById(Objects.requireNonNull(ref.sourceId())).orElseThrow();
        pull.setMerged(true);
        pull.setMergedAt(null);
        pulls.saveAndFlush(pull);
        var at = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        var result = data.result(history(ref), ResultOutcome.MET, at);
        assertThat(timing.timelines(ref.workspaceId(), List.of(result.getReviewId()))
                        .getFirst()
                        .mergedAt())
                .isNull();
        assertThat(timing.facts(ref.workspaceId(), List.of(result.getReviewId()), at.minusSeconds(1), at.plusSeconds(1))
                        .finishedAfterMerge())
                .isZero();
        get("/practices/reviews/timing?allTime=true")
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.finishedAfterMerge")
                .isEqualTo(0);
        pull.setMergedAt(at.minusSeconds(30));
        pulls.saveAndFlush(pull);
        assertThat(timing.timelines(ref.workspaceId(), List.of(result.getReviewId()))
                        .getFirst()
                        .mergedAt())
                .isEqualTo(at.minusSeconds(30));
        assertThat(timing.facts(ref.workspaceId(), List.of(result.getReviewId()), at.minusSeconds(1), at.plusSeconds(1))
                        .finishedAfterMerge())
                .isOne();
    }

    private ItemRef historyItem(ItemKind kind, long sourceId) {
        return index.upsert(new ItemRegistration(scenario.workspace().getId(), kind, sourceId, null, null));
    }

    private Scenario history(ItemRef ref) {
        var practice = versions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                "history-" + ref.itemId(),
                ref.kind(),
                DefinitionTestFixtures.definition(ref.kind(), "Describe the work", "Explain the purpose.", null),
                null,
                0,
                scenario.account().getId()));
        var item = storedItems
                .findByIdAndWorkspaceId(ref.itemId(), ref.workspaceId())
                .orElseThrow();
        return new Scenario(scenario.workspace(), scenario.account(), practice, item);
    }

    private WebTestClient.ResponseSpec get(String path) {
        return http.get()
                .uri("/workspaces/" + scenario.workspace().getWorkspaceSlug() + path)
                .headers(headers -> headers.setBearerAuth(ReviewIntegrationData.memberToken(scenario)))
                .exchange();
    }
}
