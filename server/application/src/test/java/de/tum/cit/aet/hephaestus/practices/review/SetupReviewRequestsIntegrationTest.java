package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSandboxStub.Judged;
import de.tum.cit.aet.hephaestus.practices.review.ReviewSandboxStub.Judgement;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedItem;
import de.tum.cit.aet.hephaestus.practices.spi.SetupReviewRequest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * A review setup starts a review of a repository at a commit: the setup gives the practices, the budget and the
 * audience, and the review runs and closes as a scan does. The refusals run no scan; each one records no review.
 */
@Tag("integration")
class SetupReviewRequestsIntegrationTest extends ScanEndToEndFixtures {

    private static final String REPOSITORY = "scm.repository";
    private static final BigDecimal BUDGET = new BigDecimal("7.5");

    @Test
    void shouldRecordTheSetupOnARequestedReviewWhenItClosesWithItsReport() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n", "src/Util.java", "class Util {}\n"));
        var selected = practice(upstream.workspace(), "clear-files", List.of("src/**"), List.of());
        practice(upstream.workspace(), "not-selected", List.of("src/**"), List.of());
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, selected);
        sandbox.judgeFiles((slug, path) ->
                path.equals("src/Util.java") ? Judgement.notMet("Name what the helper is for") : Judgement.met());

        var scan = review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID(), null));

        assertThat(awaitClosed(scan)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        assertThat(jdbc.queryForMap(
                        "SELECT kind, setup_id, audience, version_kind, origin, requested_by_id, batch_id, item_version"
                                + " FROM review WHERE id = ? AND workspace_id = ?",
                        scan.id(),
                        scan.workspaceId()))
                .containsEntry("kind", "SCAN")
                .containsEntry("setup_id", setup)
                .containsEntry("audience", "OPERATIONAL")
                .containsEntry("version_kind", "REPOSITORY_COMMIT")
                .containsEntry("origin", "REQUEST")
                .containsEntry("requested_by_id", accountId(upstream.admin()))
                .containsEntry("batch_id", null)
                .containsEntry("item_version", upstream.commit());
        assertThat(jdbc.queryForObject("SELECT budget_usd FROM review WHERE id = ?", BigDecimal.class, scan.id()))
                .isEqualByComparingTo(BUDGET);
        assertThat(jdbc.queryForList(
                        "SELECT practice_id FROM review_practice WHERE review_id = ? AND workspace_id = ?",
                        Long.class,
                        scan.id(),
                        scan.workspaceId()))
                .containsExactly(selected.getId());
        assertThat(sandbox.judged)
                .containsExactlyInAnyOrder(
                        new Judged("clear-files", "src/Main.java"), new Judged("clear-files", "src/Util.java"));
        assertThat(problems(scan))
                .singleElement()
                .satisfies(problem -> assertThat(problem.item().path()).isEqualTo("src/Util.java"));
    }

    @Test
    void shouldReturnTheSameReviewWhenTheKeyRepeatsAndRefuseTheKeyWhenTheInputDiffers() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, filePractice(upstream));
        sandbox.judgeFiles((slug, path) -> Judgement.met());
        UUID key = UUID.randomUUID();

        var scan = review(request(upstream, setup, ReviewOrigin.REQUEST, key, null));

        assertThat(review(request(upstream, setup, ReviewOrigin.REQUEST, key, null)))
                .isEqualTo(scan);
        assertRefused(
                () -> review(request(upstream, setup, ReviewOrigin.REQUEST, key, upstream.commit())),
                ReviewRequestRejected.Reason.REQUEST_CONFLICT);
        assertThat(awaitClosed(scan)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        assertThat(reviews(upstream.workspace(), setup)).containsExactly(scan.id());
    }

    @Test
    void shouldRefuseTheRequestWhenItNamesAnotherBranchOrACommitThatIsNotTheHead() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        push(upstream, Map.of("src/Util.java", "class Util {}\n"));
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, filePractice(upstream));

        assertRefused(
                () -> review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID(), upstream.commit())),
                ReviewRequestRejected.Reason.VERSION_CHANGED);
        assertRefused(
                () -> review(new SetupReviewRequest(
                        upstream.workspace().getId(),
                        setup,
                        new ReviewedItem.RepositoryCommit(upstream.repository().itemId(), "develop", null),
                        ReviewOrigin.REQUEST,
                        UUID.randomUUID(),
                        accountId(upstream.admin()))),
                ReviewRequestRejected.Reason.REF_UNSUPPORTED);
        assertThat(reviews(upstream.workspace(), setup)).isEmpty();
    }

    @Test
    void shouldRecordNoRequesterWhenTheSetupSchedulesTheReview() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        UUID setup = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, filePractice(upstream));
        sandbox.judgeFiles((slug, path) -> Judgement.met());
        UUID key = SetupReviewRequest.scheduleKey(setup, Instant.parse("2026-12-07T06:00:00Z"));

        var scan = review(request(upstream, setup, ReviewOrigin.SCHEDULE, key, null));

        assertThat(review(request(upstream, setup, ReviewOrigin.SCHEDULE, key, null)))
                .as("a second writer of the same due time")
                .isEqualTo(scan);
        assertThat(awaitClosed(scan)).as("%s", sandbox.calls).containsEntry("status", "COMPLETE");
        assertThat(jdbc.queryForMap(
                        "SELECT setup_id, origin, requested_by_id, batch_id, request_key FROM review"
                                + " WHERE id = ? AND workspace_id = ?",
                        scan.id(),
                        scan.workspaceId()))
                .containsEntry("setup_id", setup)
                .containsEntry("origin", "SCHEDULE")
                .containsEntry("requested_by_id", null)
                .containsEntry("batch_id", null)
                .containsEntry("request_key", key);
    }

    @Test
    void shouldRefuseEverySetupAlikeWhenItIsMissingElsewhereRemovedOrTurnedOff() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        var practice = filePractice(upstream);
        var elsewhere = upstream(Map.of("src/Main.java", "class Main {}\n"));
        UUID removed = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, practice);
        jdbc.update("UPDATE review_setup SET removed_at = now() WHERE id = ?", removed);
        UUID turnedOff = setup(upstream.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, practice);
        jdbc.update("UPDATE review_setup SET enabled = false WHERE id = ?", turnedOff);
        UUID otherWorkspace = setup(elsewhere.workspace(), REPOSITORY, "OPERATIONAL", BUDGET, filePractice(elsewhere));

        for (UUID setup : List.of(UUID.randomUUID(), otherWorkspace, removed, turnedOff)) {
            assertThatThrownBy(() -> review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID(), null)))
                    .isInstanceOfSatisfying(ReviewRequestRejected.class, refusal -> {
                        assertThat(refusal.reason()).isEqualTo(ReviewRequestRejected.Reason.SETUP_UNAVAILABLE);
                        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(refusal.getBody().getDetail()).isEqualTo("This review setup does not exist.");
                    });
            assertThat(reviews(upstream.workspace(), setup)).isEmpty();
        }
        assertThat(reviews(elsewhere.workspace(), otherWorkspace)).isEmpty();
    }

    @Test
    void shouldRefuseTheSetupWhenItCannotReviewARepository() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        var workspace = upstream.workspace();
        var practice = filePractice(upstream);
        var refusals = Map.of(
                setup(workspace, "team.board", "OPERATIONAL", BUDGET, practice),
                ReviewRequestRejected.Reason.ITEM_INELIGIBLE,
                setup(workspace, REPOSITORY, "OPERATIONAL", null, practice),
                ReviewRequestRejected.Reason.SETUP_UNSUPPORTED,
                setup(workspace, REPOSITORY, "OPERATIONAL", BUDGET),
                ReviewRequestRejected.Reason.SETUP_UNSUPPORTED,
                setup(workspace, REPOSITORY, "TEAM", BUDGET, practice),
                ReviewRequestRejected.Reason.SETUP_UNSUPPORTED,
                setup(workspace, REPOSITORY, "PERSONAL", BUDGET, practice),
                ReviewRequestRejected.Reason.SETUP_UNSUPPORTED);

        refusals.forEach((setup, reason) -> {
            assertRefused(
                    () -> review(request(upstream, setup, ReviewOrigin.REQUEST, UUID.randomUUID(), null)), reason);
            assertThat(reviews(workspace, setup)).isEmpty();
        });
    }

    @Test
    void shouldRefuseASubmittedVersionUntilTheRequestCanReviewOne() throws Exception {
        var upstream = upstream(Map.of("src/Main.java", "class Main {}\n"));
        UUID setup = setup(upstream.workspace(), "team.board", "OPERATIONAL", null, filePractice(upstream));

        assertRefused(
                () -> review(new SetupReviewRequest(
                        upstream.workspace().getId(),
                        setup,
                        new ReviewedItem.SubmittedVersion(UUID.randomUUID()),
                        ReviewOrigin.REQUEST,
                        UUID.randomUUID(),
                        accountId(upstream.admin()))),
                ReviewRequestRejected.Reason.ITEM_INELIGIBLE);
        assertThat(reviews(upstream.workspace(), setup)).isEmpty();
    }

    private Practice filePractice(Upstream upstream) {
        return practice(upstream.workspace(), "clear-files", List.of("src/**"), List.of());
    }

    private SetupReviewRequest request(
            Upstream upstream, UUID setup, ReviewOrigin origin, UUID key, @Nullable String commit) {
        return new SetupReviewRequest(
                upstream.workspace().getId(),
                setup,
                new ReviewedItem.RepositoryCommit(upstream.repository().itemId(), null, commit),
                origin,
                key,
                origin == ReviewOrigin.REQUEST ? accountId(upstream.admin()) : null);
    }

    private List<@Nullable UUID> reviews(Workspace workspace, UUID setup) {
        return jdbc.queryForList(
                "SELECT id FROM review WHERE setup_id = ? AND workspace_id = ?", UUID.class, setup, workspace.getId());
    }

    private static void assertRefused(Runnable request, ReviewRequestRejected.Reason reason) {
        assertThatThrownBy(request::run)
                .isInstanceOfSatisfying(
                        ReviewRequestRejected.class,
                        refusal -> assertThat(refusal.reason()).isEqualTo(reason));
    }
}
