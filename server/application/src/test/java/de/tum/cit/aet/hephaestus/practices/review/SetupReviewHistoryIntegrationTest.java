package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSandboxStub.Judgement;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSetupRefDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewSummaryDTO;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedItem;
import de.tum.cit.aet.hephaestus.practices.spi.SetupReviewRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reviews that a setup started form one history, and each compares only with an earlier review of its own setup
 * (ADR 0167 decisions 4 and 6). A scan that no setup started reads as before setups existed.
 */
@Tag("integration")
class SetupReviewHistoryIntegrationTest extends ScanEndToEndFixtures {

    private static final String REPOSITORY = "scm.repository";
    private static final BigDecimal BUDGET = new BigDecimal("7.5");
    /** What differs between two reviews of the same commit in a report row: each review's own records. */
    private static final Set<String> PER_REVIEW = Set.of("resultId", "sourceReviewId", "recordedAt");

    @Autowired
    private ReviewSummaryQueryService summaries;

    @Test
    void shouldGiveTheSameReportItemsProblemsAndSarifAsTodaysScanOfTheSameCommit() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n", "src/Util.java", "class Util {}\n"));
        var practice = filePractice(upstream);
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, practice);
        sandbox.judgeFiles((slug, path) ->
                path.equals("src/Util.java") ? Judgement.notMet("Name what the helper is for") : Judgement.met());

        // Each review is read right after it closes: a later review's results make the earlier ones stale.
        var reviewed = review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID()));
        assertThat(awaitClosed(reviewed)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var reviewedItems = normalised(items(reviewed));
        var reviewedProblems = normalised(problems(reviewed));
        String reviewedSarif = sarif(reviewed);
        var today = scan(upstream, false, BUDGET, practice);
        assertThat(awaitClosed(today)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");

        assertThat(baseline(reviewed)).isNull();
        assertThat(baseline(today))
                .as("today's scan never compares with a setup's review")
                .isNull();
        assertThat(reviewedItems).isEqualTo(normalised(items(today))).hasSize(2);
        assertThat(reviewedProblems).isEqualTo(normalised(problems(today))).hasSize(1);
        assertThat(reviewedSarif).isEqualTo(sarif(today));
    }

    @Test
    void shouldListAScheduledAndARequestedReviewOfOneSetupNewestFirst() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, filePractice(upstream));
        sandbox.judgeFiles((slug, path) -> Judgement.met());

        var scheduled = review(request(
                upstream,
                setup,
                ReviewOrigin.SCHEDULE,
                SetupReviewRequest.scheduleKey(setup, Instant.parse("2026-12-07T06:00:00Z"))));
        assertThat(awaitClosed(scheduled)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var requested = review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID()));
        assertThat(awaitClosed(requested)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");

        var history = summaries
                .list(
                        upstream.workspace().getId(),
                        new ReviewFilterParams(null, null, null, null, null, null, null, null, null, null),
                        setup,
                        PageRequest.of(0, 20))
                .getContent();

        assertThat(history)
                .extracting(ReviewSummaryDTO::id, ReviewSummaryDTO::origin, ReviewSummaryDTO::itemVersion)
                .containsExactly(
                        tuple(requested.id(), ReviewOrigin.REQUEST, upstream.commit()),
                        tuple(scheduled.id(), ReviewOrigin.SCHEDULE, upstream.commit()));
        assertThat(history)
                .extracting(ReviewSummaryDTO::setup)
                .containsOnly(new ReviewSetupRefDTO(setup, "Repository check"));
        assertThat(requestedBy(scheduled)).isNull();
        assertThat(requestedBy(requested)).isEqualTo(accountId(upstream.admin()));
        assertThat(baseline(requested)).isEqualTo(scheduled.id());
    }

    @Test
    void shouldCompareASetupReviewOnlyWithAnEarlierReviewOfItsSetup() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        var practice = filePractice(upstream);
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, practice);
        UUID otherSetup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, practice);
        sandbox.judgeFiles((slug, path) -> Judgement.met());

        var today = scan(upstream, false, BUDGET, practice);
        assertThat(awaitClosed(today)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var other = review(request(upstream, otherSetup, ReviewOrigin.REQUEST, UUID.randomUUID()));
        assertThat(awaitClosed(other)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var first = review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID()));
        assertThat(awaitClosed(first)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var second = review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID()));
        assertThat(awaitClosed(second)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");

        assertThat(baseline(other)).as("an earlier scan without a setup").isNull();
        assertThat(baseline(first))
                .as("an earlier scan, and an earlier review of another setup")
                .isNull();
        assertThat(baseline(second)).isEqualTo(first.id());
    }

    @Test
    void shouldCompareTodaysScanOnlyWithAnEarlierScanWithoutASetup() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        var practice = filePractice(upstream);
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, practice);
        sandbox.judgeFiles((slug, path) -> Judgement.met());

        var earlier = scan(upstream, false, BUDGET, practice);
        assertThat(awaitClosed(earlier)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var reviewed = review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID()));
        assertThat(awaitClosed(reviewed)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        var later = scan(upstream, false, BUDGET, practice);
        assertThat(awaitClosed(later)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");

        assertThat(baseline(reviewed)).isNull();
        assertThat(baseline(later))
                .as("the newer setup review is not its baseline")
                .isEqualTo(earlier.id());
    }

    private Practice filePractice(Upstream upstream) {
        return practice(upstream.workspace(), "clear-files", List.of("src/**"), List.of());
    }

    private SetupReviewRequest request(Upstream upstream, UUID setup, ReviewOrigin origin, UUID key) {
        return new SetupReviewRequest(
                upstream.workspace().getId(),
                setup,
                new ReviewedItem.RepositoryCommit(upstream.repository().itemId(), null, null),
                origin,
                key,
                origin == ReviewOrigin.REQUEST ? accountId(upstream.admin()) : null);
    }

    private @Nullable Object requestedBy(Scan scan) {
        return jdbc.queryForMap("SELECT requested_by_id FROM review WHERE id = ?", scan.id())
                .get("requested_by_id");
    }

    /** Report rows as JSON, with each review's own record ids and times replaced. */
    private List<JsonNode> normalised(List<?> rows) {
        return rows.stream().map(row -> replace(mapper.valueToTree(row))).toList();
    }

    private static JsonNode replace(JsonNode node) {
        if (node instanceof ObjectNode object) {
            // A problem's own id; an item's id names the same file in both reviews and stays.
            if (object.has("resultId") && object.has("id")) object.put("id", "id");
            for (String name : List.copyOf(object.propertyNames()))
                if (PER_REVIEW.contains(name)) object.put(name, name);
                else replace(object.path(name));
        } else for (JsonNode child : node) replace(child);
        return node;
    }
}
