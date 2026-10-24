package de.tum.cit.aet.hephaestus.practices.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.api.ItemIndexService;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.core.item.ItemRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRegistration;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.practices.DefinitionTestFixtures;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeVersionService;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackResponseService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.review.ProductItemQuery;
import de.tum.cit.aet.hephaestus.practices.review.ReviewAdmissionData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewIntegrationData;
import de.tum.cit.aet.hephaestus.practices.review.ReviewLookupData;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackResolution;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustody;
import de.tum.cit.aet.hephaestus.practices.spi.HistoryCustodyQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultEndReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryEntry;
import de.tum.cit.aet.hephaestus.practices.spi.ResultHistoryQuery;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAssignmentId;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewAttemptGuard;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewBudget;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewModelBindings;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRuntimeQuery;
import de.tum.cit.aet.hephaestus.testconfig.BaseIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ResultHistoryIntegrationTest extends BaseIntegrationTest {
    @Autowired
    ReviewIntegrationData data;

    @Autowired
    ReviewLookupData lookup;

    @Autowired
    ItemRepository itemRows;

    @Autowired
    IssueRepository issues;

    @MockitoSpyBean
    ProductItemQuery productItems;

    @Autowired
    ItemIndexService index;

    @Autowired
    JsonMapper json;

    @Autowired
    ResultHistoryQuery history;

    @Autowired
    ReviewAdmissionData admissions;

    @Autowired
    ResultRepository read;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ResultMarkService marks;

    @Autowired
    FeedbackResponseService responses;

    @Autowired
    FeedbackHistoryQuery feedbackHistory;

    @Autowired
    IdentityRepository identities;

    @Autowired
    IdentityProviderRepository identityProviders;

    @Autowired
    PracticeVersionService versions;

    @Autowired
    PracticeVersionRepository historicalVersions;

    @MockitoBean
    ResultSourceAuthorization authorization;

    @MockitoBean
    HistoryCustodyQuery custody;

    @MockitoBean
    ReviewRuntimeQuery runtime;

    @MockitoBean
    ReviewModelBindings models;

    @MockitoBean
    ReviewBudget budget;

    @MockitoBean
    ReviewAttemptGuard ownership;

    ReviewIntegrationData.Scenario scenario;

    long scenarioAccountId;

    @BeforeEach
    void setup() {
        databaseTestUtils.cleanDatabase();
        scenario = data.scenario();
        scenarioAccountId = Objects.requireNonNull(scenario.account().getId());
        when(authorization.custody(any()))
                .thenReturn(new HistoryCustody(true, false, List.of(), List.of(), List.of(UUID.randomUUID())));
        when(authorization.authorized(any(), any(), any())).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"hidden", "left", "suspended"})
    void shouldDenyHistoryWhenMembershipIsNotActiveAndVisible(String restriction) {
        var result = data.result(scenario, ResultOutcome.MET, Instant.now());
        switch (restriction) {
            case "hidden" ->
                jdbc.update(
                        "UPDATE membership SET hidden=true WHERE workspace_id=? AND account_id=?",
                        result.getWorkspaceId(),
                        scenarioAccountId);
            case "left" ->
                jdbc.update(
                        "UPDATE membership SET left_at=now() WHERE workspace_id=? AND account_id=?",
                        result.getWorkspaceId(),
                        scenarioAccountId);
            case "suspended" -> jdbc.update("UPDATE account SET status='SUSPENDED' WHERE id=?", scenarioAccountId);
            default -> throw new IllegalArgumentException(restriction);
        }
        assertThat(history.forDeveloper(result.getWorkspaceId(), scenarioAccountId, Instant.EPOCH, 20))
                .isEmpty();
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isEmpty();
    }

    @Test
    void historyRetainsMarkedAndEndedFactsWithoutResurrectingTheirClaims() {
        var old = data.result(scenario, ResultOutcome.MET, Instant.now().minusSeconds(120));
        data.end(old, Instant.now().minusSeconds(60));
        var current = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        marks.create(
                current.getWorkspaceId(),
                current.getId(),
                ResultMarkKind.INCORRECT,
                scenarioAccountId,
                "The claim has the wrong basis");
        var rows = history.forDeveloper(scenario.workspace().getId(), scenarioAccountId, Instant.EPOCH, 20);
        assertThat(rows).extracting(ResultHistoryEntry::resultId).containsExactly(current.getId(), old.getId());
        assertThat(rows.getFirst().activeMarks()).containsExactly(ResultMarkKind.INCORRECT);
        assertThat(rows.getLast().endedAt()).isNotNull();
        assertThat(rows.getLast().endReason()).isEqualTo(ResultEndReason.REPLACED);
        assertThat(rows.getFirst().endedAt()).isNull();
        assertThat(rows.getFirst().endReason()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABSENT", "PREPARED", "QUEUED", "RUNNING", "FAILED"})
    void shouldKeepAnAddressedResponseSeparateFromANewAssessmentUntilANewResultIsRecorded(String recheck) {
        var provider = identityProviders
                .findByTypeAndServerUrl(IdentityProviderType.GITHUB, "https://github.com")
                .orElseGet(() -> identityProviders.saveAndFlush(
                        new IdentityProvider(IdentityProviderType.GITHUB, "https://github.com")));
        var identity = new Identity();
        identity.setAccount(scenario.account());
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(Long.toString(scenarioAccountId));
        identities.saveAndFlush(identity);
        var at = Instant.now().minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
        var negative = data.result(scenario, ResultOutcome.NOT_MET, at);
        long workspace = scenario.workspace().getId();
        var original = history.find(
                        workspace, negative.getId(), scenarioAccountId, SourceUsePurpose.CONVERSATIONAL_MENTORING)
                .orElseThrow();
        var feedbackId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO feedback(id,workspace_id,review_id,item_id,developer_id,destination,state,body,created_at,delivered_at)"
                        + " VALUES(?,?,?,?,?,'PRACTICE_PAGE','DELIVERED','Explain the purpose.',?,?)",
                feedbackId,
                workspace,
                negative.getReviewId(),
                negative.getItemId(),
                scenarioAccountId,
                Timestamp.from(at),
                Timestamp.from(at));
        jdbc.update(
                "INSERT INTO feedback_basis(id,workspace_id,feedback_id,result_id,developer_id,ordinal) VALUES(?,?,?,?,?,0)",
                UUID.randomUUID(),
                workspace,
                feedbackId,
                negative.getId(),
                scenarioAccountId);
        var response = responses.respond(
                workspace, feedbackId, scenarioAccountId, FeedbackResolution.ADDRESSED, null, "Added the purpose.");
        assertThat(response.resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
        var responseAt = Objects.requireNonNull(response.createdAt()).truncatedTo(ChronoUnit.MICROS);
        assertThat(responseAt).isAfter(original.recordedAt());
        var recheckAt = responseAt.plusMillis(1);

        if (!recheck.equals("ABSENT")) {
            var pending = admissions.pending(scenario, recheckAt, scenarioAccountId);
            assertThat(pending.review().getOrderedAt()).isAfter(responseAt);
            if (recheck.equals("PREPARED")) {
                admissions.removeRuntime(pending);
                jdbc.update(
                        "UPDATE review SET status='QUEUED' WHERE workspace_id=? AND id=?",
                        workspace,
                        pending.review().getId());
            } else {
                jdbc.update(
                        "UPDATE review_job SET status=? WHERE workspace_id=? AND id=?",
                        recheck,
                        workspace,
                        pending.jobId());
            }
            assertThat(read.findByWorkspaceIdAndReviewIdOrderByRecordedAtAscIdAsc(
                            workspace, pending.review().getId()))
                    .isEmpty();
        }

        var visibleResponse =
                feedbackHistory.find(workspace, feedbackId, scenarioAccountId).orElseThrow();
        assertThat(visibleResponse.resolution()).isEqualTo(FeedbackResolution.ADDRESSED);
        assertThat(visibleResponse.resultIds()).containsExactly(negative.getId());
        assertThat(history.forDeveloper(workspace, scenarioAccountId, Instant.EPOCH, 20))
                .singleElement()
                .satisfies(recorded -> {
                    assertThat(recorded.resultId()).isEqualTo(original.resultId());
                    assertThat(recorded.reviewId()).isEqualTo(original.reviewId());
                    assertThat(recorded.outcome()).isEqualTo(ResultOutcome.NOT_MET);
                    assertThat(recorded.summary()).isEqualTo(original.summary());
                    assertThat(recorded.explanation()).isEqualTo(original.explanation());
                    assertThat(recorded.itemVersion()).isEqualTo(original.itemVersion());
                    assertThat(recorded.recordedAt()).isEqualTo(original.recordedAt());
                    assertThat(recorded.endedAt()).isNull();
                });
        assertThat(read.findByWorkspaceIdAndItemIdOrderByRecordedAtDescIdDesc(workspace, negative.getItemId()))
                .extracting(Result::getId)
                .containsExactly(negative.getId());

        var completedAt = recheckAt.plusMillis(1);
        data.end(negative, completedAt);
        var later = data.result(scenario, ResultOutcome.MET, completedAt);
        assertThat(later.getRecordedAt()).isAfter(responseAt).isAfter(recheckAt);
        assertThat(history.forDeveloper(workspace, scenarioAccountId, Instant.EPOCH, 20))
                .extracting(ResultHistoryEntry::resultId)
                .containsExactly(later.getId(), negative.getId());
        var previous = history.find(
                        workspace, negative.getId(), scenarioAccountId, SourceUsePurpose.CONVERSATIONAL_MENTORING)
                .orElseThrow();
        assertThat(previous.outcome()).isEqualTo(ResultOutcome.NOT_MET);
        assertThat(previous.summary()).isEqualTo(original.summary());
        assertThat(previous.explanation()).isEqualTo(original.explanation());
        assertThat(previous.recordedAt()).isEqualTo(original.recordedAt());
        assertThat(previous.endedAt()).isEqualTo(completedAt);
        assertThat(previous.endReason()).isEqualTo(ResultEndReason.REPLACED);
        assertThat(history.find(workspace, later.getId(), scenarioAccountId, SourceUsePurpose.CONVERSATIONAL_MENTORING)
                        .orElseThrow()
                        .reviewId())
                .isEqualTo(later.getReviewId())
                .isNotEqualTo(original.reviewId());
    }

    @Test
    void pinnedCriteriaAndVersionDoNotChangeWhenCurrentDefinitionChanges() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var original = DefinitionTestFixtures.definition();
        var changed = new PracticeDefinition(
                original.name(),
                original.reviewOn(),
                original.appliesWhen(),
                original.about(),
                original.reads(),
                original.reviewedBy(),
                "Use the newly revised standard.",
                original.script(),
                original.scan(),
                original.lineNotes(),
                original.guidance());
        versions.append(scenario.practice(), changed, scenarioAccountId);
        var entry = history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)
                .orElseThrow();
        assertThat(entry.practiceVersion()).isEqualTo(1);
        var criteria = entry.criteria();
        assertThat(criteria).isNotNull();
        assertThat(criteria.asString()).isEqualTo(original.criteria());
    }

    @Test
    void deniedSourceLeavesExplicitlyUnavailableMetadataForContext() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        when(authorization.authorized(any(), any(), any())).thenReturn(false);
        var row = history.forDeveloper(result.getWorkspaceId(), scenarioAccountId, Instant.EPOCH, 10)
                .getFirst();
        assertThat(row.resultId()).isEqualTo(result.getId());
        assertThat(row.copyAuthorityComplete()).isFalse();
        assertThat(row.summary()).isNull();
        assertThat(row.evidence()).isNull();
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isEmpty();
    }

    @Test
    void returnedJsonCannotMutateTheStoredHistorySnapshot() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var row = history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW)
                .orElseThrow();
        var evidence = row.evidence();
        assertThat(evidence).isNotNull();
        ((ObjectNode) evidence).put("privateMutation", true);
        var unchanged = row.evidence();
        assertThat(unchanged).isNotNull();
        assertThat(unchanged.has("privateMutation")).isFalse();
    }

    @Test
    void shouldRefuseOperatorOnlyProofInAMentorRead() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        when(authorization.authorized(any(), any(), eq(SourceUsePurpose.CONVERSATIONAL_MENTORING)))
                .thenReturn(false);
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isPresent();
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isEmpty();
    }

    @Test
    void shouldLimitPrivatePurposeReadsToTheFrozenDeveloperEvenForAnAdmin() {
        var otherDeveloper = data.member(scenario.workspace().getId());
        var other = new ReviewIntegrationData.Scenario(
                scenario.workspace(), otherDeveloper, scenario.practice(), scenario.item());
        var result = data.result(other, ResultOutcome.NOT_MET, Instant.now());
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isPresent();
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.CONVERSATIONAL_MENTORING))
                .isEmpty();
        assertThat(history.find(
                        result.getWorkspaceId(),
                        result.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .isEmpty();
    }

    @Test
    void shouldFilterByTheFrozenDeveloperBeforeLimitingAssignmentHistory() {
        var original = data.result(scenario, ResultOutcome.MET, Instant.now().minusSeconds(60));
        var otherDeveloper = data.member(scenario.workspace().getId());
        var other = new ReviewIntegrationData.Scenario(
                scenario.workspace(), otherDeveloper, scenario.practice(), scenario.item());
        var recent = data.result(other, ResultOutcome.NOT_MET, Instant.now());
        var pending = admissions.pending(scenario, Instant.now(), scenarioAccountId);
        var assignment = new ReviewAssignmentId(
                pending.review().getId(),
                scenario.practice().getId(),
                scenario.item().getId());
        assertThat(history.forItem(
                        scenario.workspace().getId(),
                        scenario.item().getId(),
                        scenario.practice().getId(),
                        1))
                .extracting(ResultHistoryEntry::resultId)
                .containsExactly(recent.getId());
        assertThat(history.forAssignment(scenario.workspace().getId(), assignment, 1))
                .extracting(ResultHistoryEntry::resultId)
                .containsExactly(original.getId());
        assertThat(history.forAssignment(scenario.workspace().getId() + 1, assignment, 1))
                .isEmpty();
    }

    @Test
    void anotherWorkspaceCannotReadTheResultByIdOrItem() {
        var result = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var foreign = data.scenario();
        assertThat(history.find(
                        foreign.workspace().getId(),
                        result.getId(),
                        Objects.requireNonNull(foreign.account().getId()),
                        SourceUsePurpose.OPERATOR_EVIDENCE_REVIEW))
                .isEmpty();
        assertThat(history.forItem(foreign.workspace().getId(), result.getItemId(), result.getPracticeId(), 20))
                .isEmpty();
    }

    @Test
    void shouldReadTheWorkspaceStandardThatExistedAtTheHistoricalEdge() {
        long workspace = scenario.workspace().getId();
        long practiceId = scenario.practice().getId();
        long originalId =
                Objects.requireNonNull(scenario.practice().getCurrentVersion().getId());
        var edge = Instant.now();
        var definition = DefinitionTestFixtures.definition();
        var changed = new PracticeDefinition(
                definition.name(),
                definition.reviewOn(),
                definition.appliesWhen(),
                definition.about(),
                definition.reads(),
                definition.reviewedBy(),
                "A different review standard.",
                definition.script(),
                definition.scan(),
                definition.lineNotes(),
                definition.guidance());
        var appended = versions.append(scenario.practice(), changed, scenarioAccountId);
        var foreign = data.scenario();
        var ids = List.of(practiceId, foreign.practice().getId());
        assertThat(historicalVersions.findAt(workspace, ids, edge))
                .extracting(v -> v.getId())
                .containsExactly(originalId);
        assertThat(historicalVersions.findAt(workspace, ids, Instant.now()))
                .extracting(v -> v.getId())
                .containsExactly(appended.getId());
    }

    @Test
    void shouldReportTheSameStoredWorkVersionWhenTheStoredWorkStillHasTheRecordedVersion() {
        var work = monitoredPullRequest();
        var recorded = data.result(work, ResultOutcome.NOT_MET, Instant.now(), Instant.now(), storedVersion(work));
        assertStoredWorkVersion(recorded, "SAME");
        assertStillAsRecorded(recorded);
    }

    @Test
    void shouldReportADifferentStoredWorkVersionWithoutEndingTheResultWhenOnlyTheDescriptionChanged() {
        var work = monitoredPullRequest();
        var recorded = data.result(work, ResultOutcome.NOT_MET, Instant.now(), Instant.now(), storedVersion(work));
        var pull = issues.findById(Objects.requireNonNull(work.item().getSourceId()))
                .orElseThrow();
        pull.setBody("Description that now explains why");
        issues.saveAndFlush(pull);
        assertThat(storedVersion(work)).isNotEqualTo(recorded.getItemVersion());
        // No event reconciled the edit, so only the history read could end the result.
        assertStillAsRecorded(recorded);
        assertStoredWorkVersion(recorded, "DIFFERENT");
        assertStillAsRecorded(recorded);
    }

    @Test
    void shouldReportAnUnknownStoredWorkVersionWhenTheKindHasNoComparableVersion() {
        var ref = index.upsert(new ItemRegistration(
                scenario.workspace().getId(),
                new ItemKind("slack.thread"),
                ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE),
                null,
                null));
        var thread = new ReviewIntegrationData.Scenario(
                scenario.workspace(),
                scenario.account(),
                scenario.practice(),
                itemRows.findById(ref.itemId()).orElseThrow());
        var recorded = data.result(thread, ResultOutcome.NOT_MET, Instant.now(), Instant.now(), "opaque-thread");
        assertStoredWorkVersion(recorded, "UNKNOWN");
        assertStillAsRecorded(recorded);
    }

    @Test
    void shouldReportAnUnknownStoredWorkVersionWhenNoStoredWorkCanBeRead() {
        var recorded = data.result(scenario, ResultOutcome.NOT_MET, Instant.now());
        var ref = productItems.require(recorded.getWorkspaceId(), recorded.getItemId());
        assertThat(productItems.current(ref)).isEmpty();
        assertStoredWorkVersion(recorded, "UNKNOWN");
        assertStillAsRecorded(recorded);
    }

    @Test
    void shouldNeitherCompareNorDateAResultWhoseWordsAreDenied() {
        var work = monitoredPullRequest();
        var recorded = data.result(work, ResultOutcome.NOT_MET, Instant.now(), Instant.now(), storedVersion(work));
        when(authorization.authorized(any(), any(), any())).thenReturn(false);

        var row = history.forDeveloper(recorded.getWorkspaceId(), scenarioAccountId, Instant.EPOCH, 20)
                .getFirst();
        assertThat(row.copyAuthorityComplete()).isFalse();
        assertThat(row.storedWorkVersion()).isEqualTo(ResultHistoryEntry.StoredWorkVersion.UNKNOWN);
        assertThat(row.capturedAt()).isNull();
        verify(productItems, never()).currentWorkVersion(anyLong(), anyLong());
        verify(authorization, never()).producingJobs(anyLong(), any());
        verify(custody, never()).capturedAt(anyLong(), any(), any());
        assertStillAsRecorded(recorded);
    }

    @Test
    void shouldCompareAnEndedResultWithoutChangingItsRecordedEnd() {
        var work = monitoredPullRequest();
        var recorded = data.result(
                work,
                ResultOutcome.NOT_MET,
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(120),
                storedVersion(work));
        data.end(recorded, Instant.now().minusSeconds(60));
        var ended = read.findByIdAndWorkspaceId(recorded.getId(), recorded.getWorkspaceId())
                .orElseThrow();

        assertStoredWorkVersion(recorded, "SAME");
        var row = read.findByIdAndWorkspaceId(recorded.getId(), recorded.getWorkspaceId())
                .orElseThrow();
        assertThat(row.getEndedAt()).isEqualTo(ended.getEndedAt());
        assertThat(row.getEndReason()).isEqualTo(ResultEndReason.REPLACED);
        assertThat(row.getOutcome()).isEqualTo(ResultOutcome.NOT_MET);
        assertThat(row.getItemVersion()).isEqualTo(recorded.getItemVersion());
    }

    /**
     * The producer lookup and the capture receipt are controlled here; {@code ResultSourceAuthorizationTest} and
     * {@code HistoryCustodyQueryAdapterTest} prove what they answer.
     */
    @Test
    void shouldDateEachResultByItsOwnReviewsProducingJobRatherThanByItsRecordedTime() {
        var work = monitoredPullRequest();
        var recordedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        var other = data.result(
                work, ResultOutcome.MET, recordedAt.minusSeconds(60), recordedAt.minusSeconds(60), storedVersion(work));
        var endedAt = recordedAt.minusSeconds(30);
        data.end(other, endedAt);
        var own = data.result(work, ResultOutcome.NOT_MET, recordedAt, recordedAt, storedVersion(work));
        var job = UUID.randomUUID();
        var capturedAt = recordedAt.minusSeconds(3600);
        long workspace = own.getWorkspaceId();
        when(authorization.producingJobs(eq(workspace), any()))
                .thenReturn(Map.of(own.getId(), job, other.getId(), job));
        // The job belongs to the first result's review, so the second review's receipt names no time.
        when(custody.capturedAt(workspace, own.getReviewId(), job)).thenReturn(Optional.of(capturedAt));

        var rows = history.forDeveloper(workspace, scenarioAccountId, Instant.EPOCH, 20);
        assertThat(rows).extracting(ResultHistoryEntry::resultId).containsExactly(own.getId(), other.getId());
        assertThat(rows.getFirst().capturedAt())
                .isEqualTo(capturedAt)
                .isNotEqualTo(rows.getFirst().recordedAt());
        assertThat(rows.getLast().capturedAt()).isNull();
        assertThat(rows)
                .extracting(ResultHistoryEntry::storedWorkVersion)
                .containsOnly(ResultHistoryEntry.StoredWorkVersion.SAME);
        // One read compares the shared work once and finds the producers of all its rows at once.
        verify(productItems).currentWorkVersion(workspace, work.item().getId());
        verify(authorization).producingJobs(eq(workspace), any());
        verify(custody).capturedAt(workspace, own.getReviewId(), job);
        verify(custody).capturedAt(workspace, other.getReviewId(), job);
        var detail = history.find(workspace, own.getId(), scenarioAccountId, SourceUsePurpose.CONVERSATIONAL_MENTORING)
                .orElseThrow();
        assertThat(detail.capturedAt()).isEqualTo(capturedAt);
        assertThat(detail.storedWorkVersion()).isEqualTo(ResultHistoryEntry.StoredWorkVersion.SAME);
        assertStillAsRecorded(own);
        var storedOther = read.findByIdAndWorkspaceId(other.getId(), workspace).orElseThrow();
        assertThat(storedOther.getEndedAt()).isEqualTo(endedAt);
        assertThat(storedOther.getEndReason()).isEqualTo(ResultEndReason.REPLACED);
        assertThat(storedOther.getItemVersion()).isEqualTo(other.getItemVersion());
        assertThat(storedOther.getOutcome()).isEqualTo(other.getOutcome());
    }

    /** Monitored, mirrored pull request work of the scenario's developer and practice. */
    private ReviewIntegrationData.Scenario monitoredPullRequest() {
        var ref = lookup.work(scenario.workspace(), IdentityProviderType.GITHUB, true);
        return new ReviewIntegrationData.Scenario(
                scenario.workspace(),
                scenario.account(),
                scenario.practice(),
                itemRows.findById(ref.itemId()).orElseThrow());
    }

    private String storedVersion(ReviewIntegrationData.Scenario work) {
        var ref = productItems.require(work.workspace().getId(), work.item().getId());
        return productItems.current(ref).orElseThrow().version().value();
    }

    /** The mentor's overview and its detail read state the same comparison. */
    private void assertStoredWorkVersion(Result recorded, String expected) {
        JsonNode fact = json.valueToTree(expected);
        var overview = history.forDeveloper(recorded.getWorkspaceId(), scenarioAccountId, Instant.EPOCH, 20).stream()
                .filter(entry -> entry.resultId().equals(recorded.getId()))
                .findFirst()
                .orElseThrow();
        var detail = history.find(
                        recorded.getWorkspaceId(),
                        recorded.getId(),
                        scenarioAccountId,
                        SourceUsePurpose.CONVERSATIONAL_MENTORING)
                .orElseThrow();
        for (var entry : List.of(overview, detail)) {
            JsonNode row = json.valueToTree(entry);
            assertThat(row.get("storedWorkVersion")).isEqualTo(fact);
        }
    }

    private void assertStillAsRecorded(Result recorded) {
        var row = read.findByIdAndWorkspaceId(recorded.getId(), recorded.getWorkspaceId())
                .orElseThrow();
        assertThat(row.getItemVersion()).isEqualTo(recorded.getItemVersion());
        assertThat(row.getOutcome()).isEqualTo(recorded.getOutcome());
        assertThat(row.getEndedAt()).isNull();
        assertThat(row.getEndReason()).isNull();
    }
}
