package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasis;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.model.Problem;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData.Scenario;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.dto.ReviewLineageEdgeDTO;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.LineageKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.reactive.server.WebTestClient;

class ReviewLineageIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    WebTestClient http;

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

    @Autowired
    ReviewLineageService lineage;

    @Autowired
    ProblemRepository problems;

    @Autowired
    FeedbackRepository feedback;

    @Autowired
    FeedbackBasisRepository basis;

    @Test
    void shouldRetainExactProblemBasisWithoutAttributingFeedbackToItsSibling() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var problem = problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(result.getWorkspaceId(), result.getId())
                .getFirst();
        var sibling = problems.saveAndFlush(Problem.builder()
                .id(UUID.randomUUID())
                .workspaceId(result.getWorkspaceId())
                .resultId(result.getId())
                .position(1)
                .key("problem-2")
                .severity(problem.getSeverity())
                .summary("A different problem")
                .explanation(problem.getExplanation())
                .citations(problem.getCitations())
                .basis(problem.getBasis())
                .build());
        var specific = feedbackFrom(result, problem.getId());
        var wholeResult = feedbackFrom(result, null);

        var graph = lineage.graph(
                result.getWorkspaceId(),
                new ReviewLineageFilterParams(LineageKind.PROBLEM, null, null, null, problem.getId(), null));

        assertThat(graph.edges())
                .contains(
                        new ReviewLineageEdgeDTO("problem:" + problem.getId(), "feedback:" + specific, "BASIS"),
                        new ReviewLineageEdgeDTO("result:" + result.getId(), "feedback:" + specific, "BASIS"),
                        new ReviewLineageEdgeDTO("result:" + result.getId(), "feedback:" + wholeResult, "BASIS"));
        assertThat(graph.edges())
                .noneMatch(edge -> edge.relation().equals("BASIS")
                        && (edge.from().equals("problem:" + sibling.getId())
                                || (edge.from().equals("problem:" + problem.getId())
                                        && edge.to().equals("feedback:" + wholeResult))));
    }

    private UUID feedbackFrom(Result result, @Nullable UUID problemId) {
        var developerId = requireNonNull(scenario.account().getId());
        var piece = feedback.saveAndFlush(Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(result.getWorkspaceId())
                .reviewId(result.getReviewId())
                .itemId(result.getItemId())
                .developerId(developerId)
                .destination(FeedbackDestination.ON_WORK)
                .state(FeedbackState.WITHHELD)
                .stopReason(FeedbackStopReason.WORKSPACE_DELIVERY_PAUSED)
                .createdAt(result.getRecordedAt())
                .build());
        basis.saveAndFlush(FeedbackBasis.builder()
                .id(UUID.randomUUID())
                .workspaceId(result.getWorkspaceId())
                .feedbackId(piece.getId())
                .resultId(result.getId())
                .problemId(problemId)
                .developerId(developerId)
                .ordinal(0)
                .build());
        return piece.getId();
    }

    @Test
    void resultLineageUsesDurableReviewAndProblemIds() {
        var r = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var graph = lineage.graph(
                r.getWorkspaceId(),
                new ReviewLineageFilterParams(LineageKind.RESULT, null, null, r.getId(), null, null));
        assertThat(graph.rootId()).isEqualTo("result:" + r.getId());
        assertThat(graph.nodes())
                .extracting(n -> n.kind())
                .contains(LineageKind.ITEM, LineageKind.REVIEW, LineageKind.RESULT, LineageKind.PROBLEM);
        assertThat(graph.edges())
                .allMatch(e -> graph.nodes().stream().anyMatch(n -> n.id().equals(e.from()))
                        && graph.nodes().stream().anyMatch(n -> n.id().equals(e.to())));
    }

    @Test
    void wrongWorkspaceCannotExpandResult() {
        var r = data.result(data.scenario(), ResultOutcome.MET, Instant.now());
        assertThatThrownBy(() -> lineage.graph(
                        scenario.workspace().getId(),
                        new ReviewLineageFilterParams(LineageKind.RESULT, null, null, r.getId(), null, null)))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void ambiguousAndMismatchedRootsAreRejected() {
        var r = data.result(scenario, ResultOutcome.MET, Instant.now());
        assertThatThrownBy(() -> lineage.graph(
                        r.getWorkspaceId(),
                        new ReviewLineageFilterParams(LineageKind.RESULT, r.getItemId(), null, r.getId(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lineage.graph(
                        r.getWorkspaceId(),
                        new ReviewLineageFilterParams(LineageKind.REVIEW, null, null, r.getId(), null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
