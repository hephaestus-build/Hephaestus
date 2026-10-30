package de.tum.cit.aet.hephaestus.practices.review;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReviewQueueOrderIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    ReviewItemRepository items;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldStartWithoutACurrentResultThenByOldestLastVerdictWithinTheWorkspace() {
        var current = data.scenario();
        var old = anotherPractice(current, "old");
        var recent = anotherPractice(current, "recent");
        var missing = anotherPractice(current, "missing");
        var now = Instant.now();
        data.result(current, ResultOutcome.MET, now.minusSeconds(172800), now.minusSeconds(172800), "current");
        data.result(old, ResultOutcome.MET, now.minusSeconds(86400), now.minusSeconds(86400), "previous");
        data.result(recent, ResultOutcome.MET, now.minusSeconds(3600), now.minusSeconds(3600), "previous");
        var pending = admissions.pending(current, now, current.account().getId(), "current");
        admissions.assigned(pending, old.practice());
        admissions.assigned(pending, recent.practice());
        admissions.assigned(pending, missing.practice());

        assertThat(items.executablePracticeIds(
                        current.workspace().getId(),
                        pending.review().getId(),
                        current.item().getId()))
                .containsExactly(
                        missing.practice().getId(),
                        old.practice().getId(),
                        recent.practice().getId(),
                        current.practice().getId());
        var other = data.scenario();
        assertThat(items.executablePracticeIds(
                        other.workspace().getId(),
                        pending.review().getId(),
                        current.item().getId()))
                .isEmpty();
    }

    private Scenario anotherPractice(Scenario scenario, String slug) {
        var practice = versions.create(new PracticeVersionService.NewPractice(
                scenario.workspace(),
                slug,
                scenario.item().getKind(),
                DefinitionTestFixtures.definition(),
                null,
                0,
                scenario.account().getId()));
        return new Scenario(scenario.workspace(), scenario.account(), practice, scenario.item());
    }
}
