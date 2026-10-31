package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewRequestReason;
import de.tum.cit.aet.hephaestus.practices.PracticeDefinition;
import de.tum.cit.aet.hephaestus.practices.PracticeItemOptions;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.dto.MemberReviewReadinessDTO;
import de.tum.cit.aet.hephaestus.practices.review.dto.ReviewReadinessBlocker;
import de.tum.cit.aet.hephaestus.practices.review.feedbackmode.FeedbackModeResolver;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewOrigin;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.AiRefusalReason;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiReadiness;
import de.tum.cit.aet.hephaestus.workspace.spi.MembershipReviewSelection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether the workspace reviews one member's work automatically, by the rules that decide it: the gate's workspace
 * checks for work activity ({@link ReviewGate}), the rollout scope and eligibility that review selection applies to
 * a subject, the member AI rule of review admission, and the practices the gate can select. It judges the member,
 * not a piece of work: the repository rollout, a practice's state filter and the work's own source are judged when
 * the work arrives.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberReviewReadinessService {
    private final WorkspaceRepository workspaces;
    private final ReviewGate gate;
    private final PracticeReviewCoverageService coverage;
    private final MembershipReviewSelection selection;
    private final MemberAiReadiness ai;
    private final PracticeRepository practices;
    private final PracticeItemOptions items;
    private final WorkspaceReviewDefaultsProvider defaults;

    public MemberReviewReadinessDTO readiness(long workspaceId, long accountId) {
        var workspace = workspaces.findById(workspaceId).orElseThrow();
        var reviewing = reviewingPractices(workspaceId);
        var events = reviewing.stream()
                .flatMap(practice -> practice.definition().reviewOn().events().stream())
                .map(ItemEventName::value)
                .distinct()
                .sorted()
                .toList();
        var mode = defaults.rawDefaultFeedbackMode(workspaceId);
        boolean approval = reviewing.stream()
                .anyMatch(practice ->
                        FeedbackModeResolver.effectiveModeOf(practice.practice(), mode) == FeedbackMode.APPROVE_FIRST);
        return new MemberReviewReadinessDTO(blocker(workspace, accountId, !reviewing.isEmpty()), events, approval);
    }

    private @Nullable ReviewReadinessBlocker blocker(Workspace workspace, long accountId, boolean anyPractice) {
        var refusal = gate.refusal(workspace, ReviewOrigin.EVENT);
        boolean onRequestOnly = false;
        if (refusal.isPresent()) {
            var reason = refusal.orElseThrow().eventReason();
            if (reason == ReviewRequestReason.WORKSPACE_INACTIVE) return ReviewReadinessBlocker.WORKSPACE_INACTIVE;
            if (reason != ReviewRequestReason.GATE_WITHHELD
                    || !Boolean.TRUE.equals(workspace.getFeatures().getPracticeReviewManualTriggerEnabled()))
                return ReviewReadinessBlocker.REVIEWS_OFF;
            onRequestOnly = true;
        }
        // A requested review passes every rule below, so the automatic start is the last reason to name.
        var aiRefusal = ai.practiceReviewRefusal(workspace.getId(), accountId).orElse(null);
        if (aiRefusal == AiRefusalReason.PERSON_ERASED) return ReviewReadinessBlocker.PERSON_ERASED;
        if (aiRefusal == AiRefusalReason.NO_AI) return ReviewReadinessBlocker.NO_AI;
        // Repository coverage is judged per piece of work: work without a repository, such as a Slack
        // thread, stays reviewable under any repository selection.
        if (!coverage.scope(workspace).admitsPerson(accountId)) return ReviewReadinessBlocker.OUTSIDE_REVIEW_SCOPE;
        if (!selection.eligibleAccountIds(workspace.getId(), List.of(accountId)).contains(accountId))
            return ReviewReadinessBlocker.NOT_ELIGIBLE;
        if (aiRefusal == AiRefusalReason.CHOICE_REQUIRED) return ReviewReadinessBlocker.CHOICE_REQUIRED;
        if (aiRefusal == AiRefusalReason.UNAVAILABLE) return ReviewReadinessBlocker.NO_MODEL;
        if (!anyPractice) return ReviewReadinessBlocker.NO_PRACTICE;
        return onRequestOnly ? ReviewReadinessBlocker.ON_REQUEST_ONLY : null;
    }

    /**
     * The practices the gate can select for a review about the person who did the work: reviewed by Hephaestus,
     * offered by their item kind, and with an About role. A practice of an item kind that no integration registers
     * reviews nothing.
     */
    private List<ReviewingPractice> reviewingPractices(long workspaceId) {
        Map<ItemKind, ItemDescriptor> descriptors =
                items.all().stream().collect(Collectors.toMap(ItemDescriptor::kind, Function.identity()));
        return practices.findByWorkspaceId(workspaceId).stream()
                .flatMap(practice -> {
                    var descriptor = descriptors.get(practice.getKind());
                    if (descriptor == null) return Stream.empty();
                    return gate.selectable(practice, descriptor).stream()
                            .filter(definition -> definition.about() != null)
                            .map(definition -> new ReviewingPractice(practice, definition));
                })
                .toList();
    }

    private record ReviewingPractice(Practice practice, PracticeDefinition definition) {}
}
