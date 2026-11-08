package de.tum.cit.aet.hephaestus.practices.reviewoutput;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ProblemRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultMarkService;
import de.tum.cit.aet.hephaestus.practices.result.ResultQueryFilter;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ResultSort;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class ReviewResultListVisibilityIntegrationTest extends BaseIntegrationTest {
    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private ReviewResultQueryService results;

    @Autowired
    private ResultMarkService marks;

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private ReviewResultListRepository listRows;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private QueryPlans plans;

    @Autowired
    private SqlReadMeasurement reads;

    @MockitoBean
    private EvidenceAuthorization evidenceAuthorization;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @Test
    void shouldKeepEvidenceDecisionsMarksAndDistinctFeedbackCountsOnEachResult() {
        var scenario = data.scenario();
        long ws = scenario.workspace().getId();
        long account = Objects.requireNonNull(scenario.account().getId());
        var at = Instant.parse("2026-01-01T00:00:00Z");
        var visible = data.result(scenario, ResultOutcome.NOT_MET, at);
        var hidden = data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, at.plusSeconds(1));
        var foreign = data.result(data.scenario(), ResultOutcome.NOT_MET, at);
        data.mark(foreign, ResultMarkKind.ACCEPTED, Objects.requireNonNull(foreign.getDeveloperId()));
        var retired = data.mark(visible, ResultMarkKind.INCORRECT, account);
        marks.remove(ws, visible.getId(), retired.getId(), account, "Corrected the review decision");
        data.mark(visible, ResultMarkKind.ACCEPTED, account);
        var feedbackId = feedback(visible, true);
        feedback(hidden, false);
        feedback(foreign, false);
        jdbc.sql("""
            INSERT INTO feedback_decision(id,workspace_id,feedback_id,action,actor_account_id,note,decided_at)
            VALUES(:id,:ws,:feedback,'WITHDRAW',:actor,'Withdraw this note.',:at)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", ws)
                .param("feedback", feedbackId)
                .param("actor", account)
                .param("at", Timestamp.from(at.plusSeconds(2)))
                .update();
        when(evidenceAuthorization.permitsAll(eq(ws), anyCollection(), eq(SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)))
                .thenReturn(Set.of(visible.getId(), foreign.getId()));

        var read =
                reads.measure(() -> results.list(ws, ResultQueryFilter.ALL, ResultSort.OLDEST, PageRequest.of(0, 25)));
        var page = read.value();

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        var shown = page.getContent().getFirst();
        assertThat(shown.id()).isEqualTo(visible.getId());
        assertThat(shown.summary()).isEqualTo("Recorded result");
        assertThat(shown.problems()).singleElement().satisfies(problem -> {
            assertThat(problem.summary()).isEqualTo("A recorded problem");
            assertThat(problem.id())
                    .isEqualTo(problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(ws, visible.getId())
                            .getFirst()
                            .getId());
        });
        assertThat(shown.problemCount()).isEqualTo(1);
        assertThat(shown.acceptedAt()).isEqualTo(at);
        assertThat(shown.markedIncorrectAt()).isNull();
        assertThat(shown.feedback().delivered()).isEqualTo(1);
        assertThat(shown.feedback().ready()).isZero();
        assertThat(shown.developer()).isNotNull();
        assertThat(shown.developer().accountId()).isEqualTo(Long.toString(account));
        var withheld = page.getContent().getLast();
        assertThat(withheld.id()).isEqualTo(hidden.getId());
        assertThat(withheld.summary()).isNull();
        assertThat(withheld.problems()).isEmpty();
        assertThat(withheld.problemCount()).isEqualTo(1);
        assertThat(withheld.acceptedAt()).isNull();
        assertThat(withheld.feedback().delivered()).isEqualTo(1);
        assertThat(listRows.problemSummaries(ws, Set.of(foreign.getId()))).isEmpty();
        assertThat(listRows.problemCounts(ws, Set.of(foreign.getId()))).isEmpty();
        assertThat(listRows.feedbackCounts(ws, Set.of(foreign.getId()))).isEmpty();
        assertThat(listRows.markTimes(ws, Set.of(foreign.getId()))).isEmpty();
        assertThat(plans.fullScans(read.sql())).isEmpty();
    }

    @Test
    void shouldKeepVisibleResultReadsBoundedAsThePageGrows() {
        var scenario = data.scenario();
        long ws = scenario.workspace().getId();
        var at = Instant.parse("2026-01-01T00:00:00Z");
        for (int index = 0; index < 2; index++) {
            data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, at.plusSeconds(index));
        }
        when(evidenceAuthorization.permitsAll(eq(ws), anyCollection(), eq(SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)))
                .thenAnswer(invocation -> {
                    Collection<Result> rows = invocation.getArgument(1);
                    return rows.stream().map(Result::getId).collect(Collectors.toSet());
                });
        var small =
                reads.measure(() -> results.list(ws, ResultQueryFilter.ALL, ResultSort.NEWEST, PageRequest.of(0, 32)));
        for (int index = 2; index < 8; index++) {
            var work = data.onOtherWork(scenario);
            var developer = data.member(ws);
            data.result(
                    new ReviewIntegrationData.Scenario(work.workspace(), developer, work.practice(), work.item()),
                    ResultOutcome.NOT_MET,
                    at.plusSeconds(index));
        }

        var larger =
                reads.measure(() -> results.list(ws, ResultQueryFilter.ALL, ResultSort.NEWEST, PageRequest.of(0, 32)));

        assertThat(small.value().getContent()).hasSize(2);
        assertThat(larger.value().getContent()).hasSize(8).allSatisfy(row -> {
            assertThat(row.summary()).isEqualTo("Recorded result");
            assertThat(row.problems())
                    .singleElement()
                    .satisfies(problem -> assertThat(problem.summary()).isEqualTo("A recorded problem"));
            assertThat(row.developer()).isNotNull();
        });
        assertThat(larger.cost().statements())
                .as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements())
                .isLessThanOrEqualTo(15);
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    private UUID feedback(Result result, boolean duplicateBasis) {
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)
            VALUES(:id,:ws,:review,:item,:developer,'ON_WORK','DELIVERED','Review this change.',:at,:at)
            """)
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", result.getDeveloperId())
                .param("at", Timestamp.from(result.getRecordedAt()))
                .update();
        basis(result, id, null, 0);
        if (duplicateBasis) {
            var problem = problems.findByWorkspaceIdAndResultIdOrderByPositionAsc(
                            result.getWorkspaceId(), result.getId())
                    .getFirst();
            basis(result, id, problem.getId(), 1);
        }
        return id;
    }

    private void basis(Result result, UUID feedbackId, @Nullable UUID problemId, int ordinal) {
        jdbc.sql("""
            INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,problem_id,developer_id,ordinal)
            VALUES(:id,:ws,:feedback,:result,:problem,:developer,:ordinal)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", result.getWorkspaceId())
                .param("feedback", feedbackId)
                .param("result", result.getId())
                .param("problem", problemId)
                .param("developer", result.getDeveloperId())
                .param("ordinal", ordinal)
                .update();
    }
}
