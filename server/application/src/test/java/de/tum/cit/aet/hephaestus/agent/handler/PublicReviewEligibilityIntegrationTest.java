package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersion;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemService;
import de.tum.cit.aet.hephaestus.practices.feedback.AbstractFeedbackIntegrationTest;
import de.tum.cit.aet.hephaestus.practices.feedback.Feedback;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackGate;
import de.tum.cit.aet.hephaestus.practices.model.Result;
import de.tum.cit.aet.hephaestus.practices.spi.AdmittedResultSnapshot;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceAuthorization;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackCheckStage;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackDestination;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackState;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackStopReason;
import de.tum.cit.aet.hephaestus.practices.spi.ResultCandidate;
import de.tum.cit.aet.hephaestus.practices.spi.ResultOutcome;
import de.tum.cit.aet.hephaestus.testconfig.ItemFixtures;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

/** Public feedback eligibility follows the feedback mode in force, while the gate stays the final word. */
class PublicReviewEligibilityIntegrationTest extends AbstractFeedbackIntegrationTest {
    @Autowired
    PublicReviewEligibility eligibility;

    @Autowired
    FeedbackGate gate;

    @MockitoBean(name = "scmItemService", enforceOverride = true)
    ScmItemService source;

    @MockitoBean
    EvidenceAuthorization authorization;

    @BeforeEach
    void permitDelivery() {
        jdbc.update("""
            UPDATE workspace SET practices_enabled=true,practice_rollout_revision=4 WHERE id=?
            """, workspace.getId());
        jdbc.update("UPDATE review SET rollout_revision=4 WHERE workspace_id=? AND id=?", workspace.getId(), reviewId);
        jdbc.update("""
            INSERT INTO instance_settings(id,silent_mode_engaged,version) VALUES(1,false,0)
            ON CONFLICT(id) DO UPDATE SET silent_mode_engaged=false
            """);
        jdbc.update("UPDATE account SET feedback_delivery_enabled=true WHERE id=?", developerId);
        var item = new ItemRef(workspace.getId(), itemId, new ItemKind("scm.pull_request"), 42L, null, null);
        when(source.supports(item)).thenReturn(true);
        when(source.pin(item, null))
                .thenReturn(ItemFixtures.snapshot(item, Map.of("state", "open"))
                        .version(new ItemVersion("v1", null))
                        .integration(new IntegrationRef(IntegrationKind.GITHUB, 1, null))
                        .label(ItemFixtures.label()
                                .itemId(item.itemId())
                                .title("Change")
                                .noun("pull request")
                                .integration(IntegrationKind.GITHUB)
                                .build())
                        .build());
        when(authorization.permitsForNewDelivery(
                        eq(workspace.getId()), any(), eq(SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY)))
                .thenAnswer(call -> call.<Collection<Result>>getArgument(1).stream()
                        .map(Result::getId)
                        .collect(Collectors.toSet()));
    }

    @AfterEach
    void restoreInstanceDeliveryBrake() {
        jdbc.update("UPDATE instance_settings SET silent_mode_engaged=true WHERE id=1");
    }

    @Test
    void shouldComposePublicFeedbackOnlyFromPracticesWhoseFeedbackIsOn() {
        var off = negativeResult();
        var approveFirst = negativeResult();
        practiceMode(off, "OFF");
        practiceMode(approveFirst, "APPROVE_FIRST");

        // Both practices were reviewed and both results are recorded.
        assertThat(results.findAllById(List.of(off.getId(), approveFirst.getId())))
                .extracting(Result::getOutcome)
                .containsOnly(ResultOutcome.NOT_MET)
                .hasSize(2);
        assertThat(publicResultIds(off, approveFirst)).containsExactly(approveFirst.getId());
        var allowed = onWork(approveFirst);
        assertThat(gate.evaluate(allowed, FeedbackCheckStage.COMPOSITION, false).allowed())
                .isTrue();
        assertThat(gate.evaluate(onWork(off, approveFirst), FeedbackCheckStage.COMPOSITION, false)
                        .reason())
                .isEqualTo(FeedbackStopReason.FEEDBACK_OFF);

        // A mode turned off while the model composes is still refused by the gate.
        practiceMode(approveFirst, "OFF");
        assertThat(publicResultIds(off, approveFirst)).isEmpty();
        assertThat(gate.evaluate(allowed, FeedbackCheckStage.COMPOSITION, false).reason())
                .isEqualTo(FeedbackStopReason.FEEDBACK_OFF);
    }

    @Test
    void shouldResolveThePracticeThenGroupThenWorkspaceMode() {
        var overridden = negativeResult();
        var groupOff = negativeResult();
        var groupOn = negativeResult();
        var inheriting = negativeResult();
        long off = group("testing-your-changes", "OFF");
        long on = group("reviewing", "APPROVE_FIRST");
        inGroup(overridden, off, "AUTOMATIC");
        inGroup(groupOff, off, null);
        inGroup(groupOn, on, null);
        practiceMode(inheriting, null);

        workspaceMode("OFF");
        assertThat(publicResultIds(overridden, groupOff, groupOn, inheriting))
                .containsExactlyInAnyOrder(overridden.getId(), groupOn.getId());

        // Without a workspace choice the instance default, approve first, applies.
        workspaceMode(null);
        assertThat(publicResultIds(inheriting)).containsExactly(inheriting.getId());
    }

    private Set<UUID> publicResultIds(Result... admitted) {
        var job = TestEntities.reviewJob();
        job.setWorkspaceId(workspace.getId());
        job.setReviewId(reviewId);
        return eligibility.publicResultIds(
                job, Stream.of(admitted).map(this::snapshot).toList());
    }

    private AdmittedResultSnapshot snapshot(Result result) {
        var candidate = mock(ResultCandidate.class);
        when(candidate.practiceId()).thenReturn(result.getPracticeId());
        when(candidate.practiceVersionId()).thenReturn(result.getPracticeVersionId());
        when(candidate.itemId()).thenReturn(result.getItemId());
        when(candidate.developerId()).thenReturn(result.getDeveloperId());
        return new AdmittedResultSnapshot(result.getId(), candidate, List.of());
    }

    private Feedback onWork(Result... support) {
        var piece = piece(FeedbackDestination.ON_WORK, FeedbackState.READY, null);
        for (var result : support) bind(piece, result);
        return piece;
    }

    private void practiceMode(Result result, @Nullable String mode) {
        jdbc.update("UPDATE practice SET feedback_mode=? WHERE id=?", mode, result.getPracticeId());
    }

    private void inGroup(Result result, long groupId, @Nullable String mode) {
        jdbc.update("UPDATE practice SET group_id=?,feedback_mode=? WHERE id=?", groupId, mode, result.getPracticeId());
    }

    private long group(String slug, String mode) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "INSERT INTO practice_group(workspace_id,slug,name,feedback_mode) VALUES (?,?,?,?) RETURNING id",
                Long.class,
                workspace.getId(),
                slug,
                slug,
                mode));
    }

    private void workspaceMode(@Nullable String mode) {
        jdbc.update("UPDATE workspace SET practice_default_feedback_mode=? WHERE id=?", mode, workspace.getId());
    }
}
