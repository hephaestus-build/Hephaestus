package de.tum.cit.aet.hephaestus.practices.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheck;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCopyState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.workspace.AccountType;
import de.tum.cit.aet.hephaestus.workspace.Membership;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

class FeedbackGateIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    FeedbackGate gate;

    @MockitoBean(name = "scmItemService", enforceOverride = true)
    ScmItemService source;

    @MockitoBean
    EvidenceAuthorization authorization;

    @Nullable
    Feedback supportedFeedback;

    ItemRef supportedItem;
    long supportedPracticeId;

    Feedback piece;

    @BeforeEach
    void admitPolicyRevision() {
        jdbc.update("""
            UPDATE workspace SET practices_enabled=true,practice_rollout_revision=4 WHERE id=?
            """, workspace.getId());
        jdbc.update("UPDATE review SET rollout_revision=4 WHERE workspace_id=? AND id=?", workspace.getId(), reviewId);
        piece = piece(FeedbackDestination.PRACTICE_PAGE, FeedbackState.READY, null);
        supportedItem = new ItemRef(workspace.getId(), itemId, new ItemKind("scm.pull_request"), 42L, null, null);
    }

    private void supportOnWorkFeedback() {
        supportOnWorkFeedback(resultWithStateFilter("state", "open"));
    }

    private void supportOnWorkFeedback(Result result) {
        jdbc.update("""
            INSERT INTO instance_settings(id,silent_mode_engaged,version) VALUES(1,false,0)
            ON CONFLICT(id) DO UPDATE SET silent_mode_engaged=false
            """);
        supportedFeedback = piece(FeedbackDestination.ON_WORK, FeedbackState.READY, null);
        bind(supportedFeedback, result);
        supportedPracticeId = result.getPracticeId();
        jdbc.update("UPDATE account SET feedback_delivery_enabled=true WHERE id=?", developerId);
        when(source.supports(supportedItem)).thenReturn(true);
        when(source.current(supportedItem)).thenReturn(Optional.of(snapshot(supportedItem, "open", "v1")));
        when(authorization.permitsForNewDelivery(
                        eq(workspace.getId()), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenReturn(Set.of(result.getId()));
    }

    @AfterEach
    void restoreInstanceDeliveryBrake() {
        jdbc.update("UPDATE instance_settings SET silent_mode_engaged=true WHERE id=1");
    }

    private FeedbackCheck check() {
        return gate.evaluate(Objects.requireNonNull(supportedFeedback), FeedbackCheckStage.EGRESS, false);
    }

    private static ItemSnapshot snapshot(ItemRef item, String state, String version) {
        return snapshot(item, Map.of("state", state), version);
    }

    private static ItemSnapshot snapshot(ItemRef item, Map<String, String> state, String version) {
        return ItemFixtures.snapshot(item, state)
                .version(new ItemVersion(version, null))
                .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, null))
                .label(ItemFixtures.label()
                        .itemId(item.itemId())
                        .title("Change")
                        .noun("pull request")
                        .integration(IntegrationKind.GITHUB)
                        .build())
                .build();
    }

    @Test
    void shouldRefuseAnEditedPracticeAfterReadingItsPersistedCurrentVersion() {
        supportOnWorkFeedback();
        assertThat(check().allowed()).isTrue();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Long next = jdbc.queryForObject("""
                SELECT nextval(pg_get_serial_sequence('practice_version','id'))
                """, Long.class);
            jdbc.update("""
                INSERT INTO practice_version(id,workspace_id,practice_id,number,definition)
                SELECT ?,workspace_id,practice_id,number+1,
                    jsonb_set(definition,'{criteria}','"A changed standard."'::jsonb)
                FROM practice_version
                WHERE id=(SELECT current_version_id FROM practice WHERE id=?)
                """, next, supportedPracticeId);
            jdbc.update("UPDATE practice SET current_version_id=? WHERE id=?", next, supportedPracticeId);
        });
        assertThat(check().reason()).isEqualTo(FeedbackStopReason.REVIEWED_VERSION_CHANGED);
    }

    @Test
    void shouldRefuseChangedWorkAndClosedWorkAgainstThePersistedStateFilter() {
        supportOnWorkFeedback();
        when(source.current(supportedItem)).thenReturn(Optional.of(snapshot(supportedItem, "open", "v2")));
        assertThat(check().reason()).isEqualTo(FeedbackStopReason.REVIEWED_VERSION_CHANGED);
        when(source.current(supportedItem)).thenReturn(Optional.of(snapshot(supportedItem, "closed", "v1")));
        var check = check();
        assertThat(check.reason()).isEqualTo(FeedbackStopReason.ITEM_STATE_INELIGIBLE);
        assertThat(check.facts().currentVersion()).isTrue();
    }

    @Test
    void shouldDeliverOnlyToWorkWhosePublishedDraftStatusTheVersionAllows() {
        supportOnWorkFeedback(resultWithStateFilter("draftStatus", "NOT_DRAFT"));
        when(source.current(supportedItem))
                .thenReturn(Optional.of(
                        snapshot(supportedItem, Map.of("state", "OPEN", "draftStatus", "NOT_DRAFT"), "v1")));
        assertThat(check().allowed()).isTrue();
        when(source.current(supportedItem))
                .thenReturn(
                        Optional.of(snapshot(supportedItem, Map.of("state", "OPEN", "draftStatus", "DRAFT"), "v1")));
        assertThat(check().reason()).isEqualTo(FeedbackStopReason.ITEM_STATE_INELIGIBLE);
        // A snapshot without the required dimension stays refused, as before the adapter published it.
        when(source.current(supportedItem))
                .thenReturn(Optional.of(
                        snapshot(supportedItem, Map.of("state", "OPEN", "draft", "false", "merged", "false"), "v1")));
        assertThat(check().reason()).isEqualTo(FeedbackStopReason.ITEM_STATE_INELIGIBLE);
    }

    @Test
    void shouldAdmitAnyStateWhenTheVersionRequiresNone() {
        supportOnWorkFeedback(result());
        when(source.current(supportedItem)).thenReturn(Optional.of(snapshot(supportedItem, Map.of(), "v1")));
        assertThat(check().allowed()).isTrue();
    }

    @Test
    void shouldDecideEveryPieceInOneReadExactlyAsItIsDecidedOnItsOwn() {
        supportOnWorkFeedback();
        var admitted = Objects.requireNonNull(supportedFeedback);
        var closedOnly = onWork(resultWithStateFilter("state", "closed"));
        var incorrect = onWork(resultWithStateFilter("state", "open"));
        jdbc.update(
                "INSERT INTO result_mark(id,workspace_id,result_id,kind,reason,created_at)"
                        + " VALUES(gen_random_uuid(),?,?,'INCORRECT','This observation is wrong.',now())",
                workspace.getId(),
                resultsOf(incorrect).getFirst().getId());
        var fenced = onWork(resultWithStateFilter("state", "open"));
        summary(fenced, FeedbackCopyState.WRITE_STARTED);
        var cancelledReview = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at,"
                        + "completed_at,stop_reason) VALUES (?,?,?,'ITEM','v1','REQUEST','CANCELLED',?,?,?,'CANCELLED_BY_ADMIN')",
                cancelledReview,
                workspace.getId(),
                itemId,
                Timestamp.from(AT),
                Timestamp.from(AT),
                Timestamp.from(AT));
        var cancelled = feedback.saveAndFlush(copyOf(piece, cancelledReview, developerId, workspace.getId(), itemId));
        var secondRecipient = Objects.requireNonNull(
                ensureMembership(workspace, persistUser("feedback-second"), Membership.WorkspaceRole.MEMBER)
                        .getAccountId());
        jdbc.update("UPDATE account SET feedback_delivery_enabled=false WHERE id=?", secondRecipient);
        var otherRecipient = feedback.saveAndFlush(copyOf(piece, reviewId, secondRecipient, workspace.getId(), itemId));
        var denied = onWork(resultWithStateFilter("state", "open"));
        var elsewhereOwner = persistUser("feedback-elsewhere-owner");
        var elsewhere = createWorkspace(
                "feedback-elsewhere", "Feedback elsewhere", "feedback-elsewhere-org", AccountType.ORG, elsewhereOwner);
        var foreignItem = jdbc.queryForObject(
                "INSERT INTO item(workspace_id,kind,source_id,created_at) VALUES (?,'scm.pull_request',43,?) RETURNING id",
                Long.class,
                elsewhere.getId(),
                Timestamp.from(AT));
        var foreignReview = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO"
                        + " review(id,workspace_id,item_id,kind,item_version,origin,status,ordered_at,created_at)"
                        + " VALUES (?,?,?,'ITEM','v1','REQUEST','RUNNING',?,?)",
                foreignReview,
                elsewhere.getId(),
                foreignItem,
                Timestamp.from(AT),
                Timestamp.from(AT));
        var foreign = feedback.saveAndFlush(copyOf(
                piece,
                foreignReview,
                accountId(elsewhereOwner),
                elsewhere.getId(),
                Objects.requireNonNull(foreignItem)));
        var allowed = Stream.of(admitted, closedOnly, incorrect, fenced)
                .map(each -> resultsOf(each).getFirst().getId())
                .collect(Collectors.toSet());
        when(authorization.permitsForNewDelivery(
                        eq(workspace.getId()), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(i -> i.<Collection<Result>>getArgument(1).stream()
                        .map(Result::getId)
                        .filter(allowed::contains)
                        .collect(Collectors.toSet()));
        var pieces = List.of(admitted, closedOnly, incorrect, fenced, denied, cancelled, otherRecipient, piece);

        var together = gate.evaluateAll(
                workspace.getId(),
                Stream.concat(pieces.stream(), Stream.of(foreign)).toList(),
                FeedbackCheckStage.EGRESS,
                false);

        // A piece of another workspace in the batch gets no answer; every other piece gets the one it gets alone.
        assertThat(together.keySet())
                .containsExactlyElementsOf(pieces.stream().map(Feedback::getId).toList());
        for (var each : pieces) {
            var alone = gate.evaluate(each, FeedbackCheckStage.EGRESS, false);
            assertThat(together.get(each.getId()))
                    .usingRecursiveComparison()
                    .ignoringFields("checkedAt")
                    .isEqualTo(alone);
        }
        assertThat(checkOf(together, admitted).allowed()).isTrue();
        assertThat(checkOf(together, closedOnly).reason()).isEqualTo(FeedbackStopReason.ITEM_STATE_INELIGIBLE);
        assertThat(checkOf(together, incorrect).reason()).isEqualTo(FeedbackStopReason.RESULT_MARKED_INCORRECT);
        assertThat(checkOf(together, fenced).facts().writeStarted()).isTrue();
        assertThat(checkOf(together, admitted).facts().writeStarted()).isFalse();
        assertThat(checkOf(together, cancelled).reason()).isEqualTo(FeedbackStopReason.REVIEW_CANCELLED);
        assertThat(checkOf(together, denied).reason()).isEqualTo(FeedbackStopReason.EVIDENCE_NOT_PERMITTED);
        assertThat(checkOf(together, otherRecipient).reason()).isEqualTo(FeedbackStopReason.DELIVERY_OFF);
    }

    private Feedback onWork(Result result) {
        var piece = piece(FeedbackDestination.ON_WORK, FeedbackState.READY, null);
        bind(piece, result);
        return piece;
    }

    private static Feedback copyOf(Feedback source, UUID review, long developer, long workspaceId, long item) {
        return Feedback.builder()
                .id(UUID.randomUUID())
                .workspaceId(workspaceId)
                .reviewId(review)
                .itemId(item)
                .developerId(developer)
                .destination(source.getDestination())
                .state(FeedbackState.READY)
                .body(source.getBody())
                .createdAt(AT)
                .build();
    }

    private static FeedbackCheck checkOf(Map<UUID, FeedbackCheck> checks, Feedback piece) {
        return Objects.requireNonNull(checks.get(piece.getId()));
    }

    private List<Result> resultsOf(Feedback feedback) {
        return results.findByWorkspaceIdAndIdIn(
                workspace.getId(),
                basis.findByWorkspaceIdAndFeedbackIdOrderByOrdinalAscIdAsc(workspace.getId(), feedback.getId()).stream()
                        .map(FeedbackBasis::getResultId)
                        .toList());
    }

    @Test
    void shouldRefuseChangedRolloutWhenNativePolicyNoLongerMatchesAdmission() {
        jdbc.update("UPDATE workspace SET practice_rollout_revision=5 WHERE id=?", workspace.getId());
        var check = gate.evaluate(piece, FeedbackCheckStage.EGRESS, false);
        assertThat(check.reason()).isEqualTo(FeedbackStopReason.STALE_ROLLOUT_REVISION);
        assertThat(check.facts().admittedRolloutRevision()).isEqualTo(4);
        assertThat(check.facts().evaluatedRolloutRevision()).isEqualTo(5);
    }

    @Test
    void shouldRefuseOptedOutDeveloperWhenTheNativeAccountChoiceChanges() {
        jdbc.update("UPDATE account SET ai_choice='NO_AI',ai_choice_at=now() WHERE id=?", developerId);
        assertThat(gate.evaluate(piece, FeedbackCheckStage.EGRESS, false).reason())
                .isEqualTo(FeedbackStopReason.NO_AI);
    }

    @Test
    void shouldRefuseUnselectedDeveloperWhenNativeMembershipLeavesSelectedScope() {
        jdbc.update("UPDATE workspace SET review_scope_people='SELECTED' WHERE id=?", workspace.getId());
        jdbc.update(
                "UPDATE membership SET review_selected=false WHERE workspace_id=? AND account_id=?",
                workspace.getId(),
                developerId);
        assertThat(gate.evaluate(piece, FeedbackCheckStage.EGRESS, false).reason())
                .isEqualTo(FeedbackStopReason.OUTSIDE_CURRENT_SCOPE);
    }
}
