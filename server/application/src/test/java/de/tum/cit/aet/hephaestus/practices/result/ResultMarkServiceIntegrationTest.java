package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

class ResultMarkServiceIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

    Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setUp() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
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

    @Autowired
    ResultMarkService service;

    @Autowired
    ResultRepository results;

    @Autowired
    ResultMarkRepository marks;

    @Test
    void incorrectMarkAndRemovalDoNotChangeCurrentOccupancy() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var mark = service.create(
                result.getWorkspaceId(),
                result.getId(),
                ResultMarkKind.INCORRECT,
                scenarioAccountId,
                "Incorrect location");
        assertThat(results.findCurrentClaim(
                        result.getWorkspaceId(), result.getPracticeId(), result.getItemId(), result.getDeveloperId()))
                .get()
                .extracting(Result::getId)
                .isEqualTo(result.getId());
        var removed = service.remove(
                result.getWorkspaceId(), result.getId(), mark.getId(), scenarioAccountId, "The location is verified");
        assertThat(removed.getRemovedAt()).isNotNull();
        assertThat(results.findById(result.getId()).orElseThrow().getEndedAt()).isNull();
    }

    @Test
    void repeatedRemovalKeepsTheFirstActorReasonAndTime() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var mark = service.create(
                result.getWorkspaceId(),
                result.getId(),
                ResultMarkKind.ACCEPTED,
                scenarioAccountId,
                "Intentional tradeoff");
        var first = service.remove(
                result.getWorkspaceId(), result.getId(), mark.getId(), scenarioAccountId, "Reconsidered");
        var firstStored = marks.findById(first.getId()).orElseThrow();
        var second = service.remove(
                result.getWorkspaceId(), result.getId(), mark.getId(), scenarioAccountId, "Second request");
        assertThat(second.getRemovedAt()).isEqualTo(firstStored.getRemovedAt());
        assertThat(second.getRemovalReason()).isEqualTo("Reconsidered");
        assertThat(second.getRemovedById()).isEqualTo(first.getRemovedById());
    }

    @Test
    void foreignResultCannotBeMarked() {
        var result = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        assertThatThrownBy(() -> service.create(
                        scenario.workspace().getId(),
                        result.getId(),
                        ResultMarkKind.INCORRECT,
                        scenarioAccountId,
                        "Wrong workspace"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void activeDuplicateMarkIsAConflict() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        service.create(
                result.getWorkspaceId(), result.getId(), ResultMarkKind.ACCEPTED, scenarioAccountId, "Intentional");
        assertThatThrownBy(() -> service.create(
                        result.getWorkspaceId(), result.getId(), ResultMarkKind.ACCEPTED, scenarioAccountId, "Again"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                                ((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(409));
    }
}
