package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.practices.feedback.dto.FeedbackCopyCountsDTO;
import de.tum.cit.aet.hephaestus.practices.feedback.response.FeedbackResponseRepository;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.result.ResultRepository;
import de.tum.cit.aet.hephaestus.practices.result.ResultViewQuery;
import de.tum.cit.aet.hephaestus.practices.result.ResultVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.ProductReferenceQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.reviewoutput.ReviewResultQueryService;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.MockSecurityContextUtils;
import de.tum.cit.aet.hephaestus.testconfig.QueryPlans;
import de.tum.cit.aet.hephaestus.testconfig.SqlReadMeasurement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

class FeedbackListVisibleReadIntegrationTest extends BaseIntegrationTest {
    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final FeedbackQueryFilter ALL = new FeedbackQueryFilter(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Autowired
    private ReviewIntegrationData data;

    @Autowired
    private FeedbackBasisRepository basisRows;

    @Autowired
    private ResultRepository results;

    @Autowired
    private ResultViewQuery views;

    @Autowired
    private ReviewResultQueryService resultPresentation;

    @Autowired
    private FeedbackQueryRepository queries;

    @Autowired
    private ProductReferenceQuery references;

    @Autowired
    private FeedbackCopyRepository copies;

    @Autowired
    private FeedbackResponseRepository responses;

    @Autowired
    private FeedbackVisibilityService visibility;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private SqlReadMeasurement reads;

    @Autowired
    private QueryPlans plans;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clean() {
        databaseTestUtils.cleanDatabase();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldKeepPrivateAndPartlyPermittedTextHiddenAndCountCopies() {
        var scenario = data.scenario();
        authenticate(scenario);
        var permitted = data.result(scenario, ResultOutcome.NOT_MET, AT);
        var denied = data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, AT.plusSeconds(1));
        var visibleId = feedback(permitted, FeedbackDestination.ON_WORK, "a".repeat(241));
        var hiddenId = feedback(denied, FeedbackDestination.ON_WORK, "Hidden feedback");
        var copyOnlyId = feedback(denied, FeedbackDestination.ON_WORK, null);
        copy(copyOnlyId, scenario.workspace().getId(), FeedbackCopyState.PLANNED, 0, "Kept copy");
        var privateIds = List.of(
                feedback(permitted, FeedbackDestination.PRACTICE_PAGE, "Private page text"),
                feedback(permitted, FeedbackDestination.CONVERSATION, "Private conversation text"));
        var mixedId = feedback(permitted, FeedbackDestination.ON_WORK, "Mixed evidence");
        basis(mixedId, denied, 1);
        int position = 0;
        for (var entry : Map.of(
                        FeedbackCopyState.PLANNED,
                        1,
                        FeedbackCopyState.WRITE_STARTED,
                        2,
                        FeedbackCopyState.POSTED,
                        3,
                        FeedbackCopyState.FAILED,
                        4,
                        FeedbackCopyState.WITHHELD,
                        5,
                        FeedbackCopyState.RETRACTED,
                        6)
                .entrySet()) {
            for (int index = 0; index < entry.getValue(); index++)
                copy(visibleId, scenario.workspace().getId(), entry.getKey(), position++, "Copy text");
        }
        var policy = mock(ResultVisibilityPolicy.class);
        when(policy.permitsHistory(
                        eq(scenario.workspace().getId()),
                        anyCollection(),
                        eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenReturn(Set.of(permitted.getId()));
        var query = query(policy);

        var measured = reads.measure(() -> query.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32)));

        var byId = measured.value().getContent().stream().collect(Collectors.toMap(row -> row.id(), row -> row));
        var visible = Objects.requireNonNull(byId.get(visibleId));
        assertThat(visible.bodyPreview()).isEqualTo("a".repeat(240));
        assertThat(visible.bodyTruncated()).isTrue();
        assertThat(visible.textHidden()).isFalse();
        assertThat(visible.copyCounts()).isEqualTo(new FeedbackCopyCountsDTO(1, 2, 3, 4, 5, 6));
        for (var id : List.of(hiddenId, copyOnlyId, mixedId, privateIds.getFirst(), privateIds.getLast())) {
            var hidden = Objects.requireNonNull(byId.get(id));
            assertThat(hidden.bodyPreview()).isNull();
            assertThat(hidden.textHidden()).isTrue();
            assertThat(hidden.bodyTruncated()).isFalse();
        }
        assertThat(Objects.requireNonNull(byId.get(mixedId)).resultCount()).isEqualTo(2);
        assertThat(Objects.requireNonNull(byId.get(copyOnlyId)).copyCounts().planned())
                .isEqualTo(1);
        assertThat(plans.fullScans(measured.sql())).isEmpty();
    }

    @Test
    void shouldKeepVisibleFeedbackReadsBoundedAsThePageGrows() {
        var scenario = data.scenario();
        authenticate(scenario);
        for (int index = 0; index < 2; index++)
            feedback(
                    data.result(data.onOtherWork(scenario), ResultOutcome.NOT_MET, AT.plusSeconds(index)),
                    FeedbackDestination.ON_WORK,
                    "Visible feedback");
        var policy = mock(ResultVisibilityPolicy.class);
        when(policy.permitsHistory(
                        eq(scenario.workspace().getId()),
                        anyCollection(),
                        eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(invocation -> {
                    Collection<Result> rows = invocation.getArgument(1);
                    return rows.stream().map(Result::getId).collect(Collectors.toSet());
                });
        var query = query(policy);
        var small = reads.measure(() -> query.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32)));
        for (int index = 2; index < 8; index++) {
            var work = data.onOtherWork(scenario);
            var developer = data.member(scenario.workspace().getId());
            feedback(
                    data.result(
                            new ReviewIntegrationData.Scenario(
                                    work.workspace(), developer, work.practice(), work.item()),
                            ResultOutcome.NOT_MET,
                            AT.plusSeconds(index)),
                    FeedbackDestination.ON_WORK,
                    "Visible feedback");
        }

        var larger = reads.measure(() -> query.list(scenario.workspace().getId(), ALL, PageRequest.of(0, 32)));

        assertThat(small.value().getContent()).hasSize(2);
        assertThat(larger.value().getContent()).hasSize(8).allSatisfy(row -> {
            assertThat(row.bodyPreview()).isEqualTo("Visible feedback");
            assertThat(row.textHidden()).isFalse();
            assertThat(row.resultCount()).isEqualTo(1);
        });
        assertThat(larger.cost().statements())
                .as("small=%s, larger=%s", small.cost(), larger.cost())
                .isEqualTo(small.cost().statements());
        assertThat(plans.fullScans(small.sql())).isEmpty();
        assertThat(plans.fullScans(larger.sql())).isEmpty();
    }

    // Substitute only the policy without adding a Spring context for this visibility matrix.
    private FeedbackListReadQuery query(ResultVisibilityPolicy policy) {
        var snapshots = new FeedbackBasisRead(basisRows, results, views, policy, resultPresentation);
        return new FeedbackListReadQuery(queries, snapshots, references, copies, responses, visibility, mapper);
    }

    private void authenticate(ReviewIntegrationData.Scenario scenario) {
        SecurityContextHolder.setContext(MockSecurityContextUtils.createSecurityContext(
                "feedback-reader",
                Objects.requireNonNull(scenario.account().getId()).toString(),
                new String[0],
                "feedback-reader-token"));
    }

    private UUID feedback(Result result, FeedbackDestination destination, @Nullable String body) {
        var id = UUID.randomUUID();
        jdbc.sql("""
            INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)
            VALUES(:id,:ws,:review,:item,:developer,:destination,'DELIVERED',:body,:at,:at)
            """)
                .param("id", id)
                .param("ws", result.getWorkspaceId())
                .param("review", result.getReviewId())
                .param("item", result.getItemId())
                .param("developer", result.getDeveloperId())
                .param("destination", destination.name())
                .param("body", body)
                .param("at", Timestamp.from(result.getRecordedAt()))
                .update();
        basis(id, result, 0);
        return id;
    }

    private void basis(UUID feedbackId, Result result, int ordinal) {
        jdbc.sql("""
            INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal)
            VALUES(:id,:ws,:feedback,:result,:developer,:ordinal)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", result.getWorkspaceId())
                .param("feedback", feedbackId)
                .param("result", result.getId())
                .param("developer", result.getDeveloperId())
                .param("ordinal", ordinal)
                .update();
    }

    private void copy(UUID feedbackId, long workspaceId, FeedbackCopyState state, int position, String body) {
        jdbc.sql("""
            INSERT INTO feedback_copy(id,workspace_id,feedback_id,kind,position,path,body,state,created_at,write_started_at,posted_at)
            VALUES(:id,:ws,:feedback,'FILE_NOTE',:position,'src/Example.java',:body,:state,:at,:started,:posted)
            """)
                .param("id", UUID.randomUUID())
                .param("ws", workspaceId)
                .param("feedback", feedbackId)
                .param("position", position)
                .param("body", body)
                .param("state", state.name())
                .param("at", Timestamp.from(AT.plusSeconds(10)))
                .param("started", state == FeedbackCopyState.WRITE_STARTED ? Timestamp.from(AT.plusSeconds(10)) : null)
                .param(
                        "posted",
                        state == FeedbackCopyState.POSTED || state == FeedbackCopyState.RETRACTED
                                ? Timestamp.from(AT.plusSeconds(10))
                                : null)
                .update();
    }
}
