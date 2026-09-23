package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PracticeReviewDetectionGate {

    private static final Logger log = LoggerFactory.getLogger(PracticeReviewDetectionGate.class);
    private final PracticeReviewReadiness practiceDetectionReadiness;
    private final PracticeRepository practiceRepository;
    private final WorkspaceResolver workspaceResolver;
    private final PracticeSignalOptions signalOptions;
    private final PracticeReviewCoverageService coverageService;

    public PracticeReviewDetectionGate(
            PracticeReviewReadiness practiceDetectionReadiness,
            PracticeRepository practiceRepository,
            WorkspaceResolver workspaceResolver,
            PracticeSignalOptions signalOptions,
            PracticeReviewCoverageService coverageService) {
        this.practiceDetectionReadiness = practiceDetectionReadiness;
        this.practiceRepository = practiceRepository;
        this.workspaceResolver = workspaceResolver;
        this.signalOptions = signalOptions;
        this.coverageService = coverageService;
    }

    public GateDecision evaluate(
            @NonNull PullRequest pullRequest, @NonNull SignalName signal, @NonNull TriggerMode triggerMode) {
        return evaluate(pullRequest, signal, triggerMode, pullRequest.reviewSubject());
    }

    public GateDecision evaluate(
            @NonNull PullRequest pullRequest,
            @NonNull SignalName signal,
            @NonNull TriggerMode triggerMode,
            @NonNull ReviewSubject subject) {
        return evaluateReviewable(pullRequest, pullRequest.isDraft(), signal, triggerMode, false, subject);
    }

    public GateDecision evaluateAdministrative(PullRequest pullRequest, SignalName signal) {
        return evaluateReviewable(
                pullRequest, pullRequest.isDraft(), signal, TriggerMode.MANUAL, true, pullRequest.reviewSubject());
    }

    public GateDecision evaluateIssue(
            @NonNull Issue issue,
            @NonNull Workspace workspace,
            @NonNull SignalName signal,
            @NonNull TriggerMode triggerMode) {
        return evaluateReviewableInWorkspace(
                issue, workspace, false, signal, triggerMode, false, issue.reviewSubject());
    }

    public GateDecision evaluateIssue(
            @NonNull Issue issue, long workspaceId, @NonNull SignalName signal, @NonNull TriggerMode triggerMode) {
        String repository =
                issue.getRepository() != null ? issue.getRepository().getNameWithOwner() : null;
        return workspaceResolver.resolveAllForRepository(repository).stream()
                .filter(workspace -> Objects.equals(workspace.getId(), workspaceId))
                .findFirst()
                .<GateDecision>map(workspace -> evaluateIssue(issue, workspace, signal, triggerMode))
                .orElseGet(() -> new GateDecision.Skip(
                        "workspace no longer monitors this issue", SignalStateReason.OUT_OF_REVIEW_SCOPE));
    }

    public GateDecision evaluateIssueAdministrative(Issue issue, Workspace workspace, SignalName signal) {
        return evaluateReviewableInWorkspace(
                issue, workspace, false, signal, TriggerMode.MANUAL, true, issue.reviewSubject());
    }

    public GateDecision evaluateSignal(
            @NonNull Workspace workspace,
            @NonNull SignalName signal,
            @NonNull TriggerMode triggerMode,
            @NonNull ReviewSubject subject) {
        var coverage = coverageService.assessRepositoryless(workspace, subject);
        GateDecision.@Nullable Skip scopeSkip = coverage.admitted()
                ? null
                : new GateDecision.Skip(
                        "the artifact or linked subject is outside review coverage",
                        coverage.subjectStatus() == ReviewSubjectStatus.RESOLVED_LINKED_HUMAN
                                ? SignalStateReason.OUT_OF_REVIEW_SCOPE
                                : SignalStateReason.SUBJECT_UNLINKED);
        return evaluateWorkspaceAndSignal(
                workspace, signal, false, triggerMode, "workspace:" + workspace.getId(), scopeSkip);
    }

    private GateDecision evaluateReviewable(
            @NonNull Issue reviewable,
            boolean draft,
            @NonNull SignalName signal,
            @NonNull TriggerMode triggerMode,
            boolean allowOutsideCoverage,
            @NonNull ReviewSubject subject) {
        String nameWithOwner =
                reviewable.getRepository() != null ? reviewable.getRepository().getNameWithOwner() : null;
        Workspace workspace =
                workspaceResolver.resolveForRepository(nameWithOwner).orElse(null);
        if (workspace == null) {
            log.debug(
                    "Practice review gate: SKIP, reason=noWorkspace, prId={}, repo={}",
                    reviewable.getId(),
                    nameWithOwner);
            return new GateDecision.Skip("no workspace");
        }

        return evaluateReviewableInWorkspace(
                reviewable, workspace, draft, signal, triggerMode, allowOutsideCoverage, subject);
    }

    private GateDecision evaluateReviewableInWorkspace(
            Issue reviewable,
            Workspace workspace,
            boolean draft,
            SignalName signal,
            TriggerMode triggerMode,
            boolean allowOutsideCoverage,
            ReviewSubject subject) {
        String nameWithOwner =
                reviewable.getRepository() != null ? reviewable.getRepository().getNameWithOwner() : null;

        GateDecision.@Nullable Skip scopeSkip = null;
        String targetBranch = reviewable instanceof PullRequest pr ? pr.getBaseRefName() : null;
        if (!allowOutsideCoverage
                && !(reviewable instanceof PullRequest
                        ? coverageService.admits(workspace, nameWithOwner, targetBranch, subject)
                        : coverageService.admits(workspace, nameWithOwner, null, subject, false))) {
            log.debug(
                    "Practice review gate: SKIP, reason=outsideCoverage, artifactId={}, repo={}, targetBranch={}, subjectId={}",
                    reviewable.getId(),
                    nameWithOwner,
                    targetBranch,
                    subject.actorId());
            scopeSkip = new GateDecision.Skip(
                    "the repository, branch, or linked subject is outside review coverage",
                    subject.actorId() == null
                            ? SignalStateReason.SUBJECT_UNLINKED
                            : SignalStateReason.OUT_OF_REVIEW_SCOPE);
        }

        GateDecision shared = evaluateWorkspaceAndSignal(
                workspace, signal, draft, triggerMode, String.valueOf(reviewable.getId()), scopeSkip);
        return shared;
    }

    private GateDecision evaluateWorkspaceAndSignal(
            Workspace workspace,
            SignalName signal,
            boolean draft,
            TriggerMode triggerMode,
            String subject,
            GateDecision.@Nullable Skip scopeSkip) {
        if (!Boolean.TRUE.equals(workspace.getFeatures().getPracticesEnabled())) {
            log.debug(
                    "Practice review gate: SKIP, reason=practicesDisabled, subject={}, workspaceId={}",
                    subject,
                    workspace.getId());
            return new GateDecision.Skip("practices disabled for workspace");
        }

        if (scopeSkip != null) {
            return scopeSkip;
        }

        if (triggerMode == TriggerMode.AUTO
                && !Boolean.TRUE.equals(workspace.getFeatures().getPracticeReviewAutoTriggerEnabled())) {
            log.debug(
                    "Practice review gate: SKIP, reason=autoTriggerDisabled, subject={}, workspaceId={}",
                    subject,
                    workspace.getId());
            return new GateDecision.Skip("auto-trigger disabled for workspace");
        }
        if (triggerMode == TriggerMode.MANUAL
                && !Boolean.TRUE.equals(workspace.getFeatures().getPracticeReviewManualTriggerEnabled())) {
            log.debug(
                    "Practice review gate: SKIP, reason=manualTriggerDisabled, subject={}, workspaceId={}",
                    subject,
                    workspace.getId());
            return new GateDecision.Skip("manual trigger disabled for workspace");
        }

        if (!practiceDetectionReadiness.hasRunnableAgent(workspace.getId())) {
            log.debug(
                    "Practice review gate: SKIP, reason=noRunnableDetectionAgent, subject={}, workspaceId={}",
                    subject,
                    workspace.getId());
            return new GateDecision.Skip("no runnable practice-review agent");
        }

        SignalMatch match = findMatchingPractices(workspace, signal, draft);
        if (match.admitted().isEmpty()) {
            if (match.hasDisabledPractice()) {
                log.debug(
                        "Practice review gate: SKIP, reason=allBoundPracticesOff, subject={}, signal={}, workspaceId={}",
                        subject,
                        signal,
                        workspace.getId());
                return new GateDecision.Skip(
                        "every practice bound to this signal is off", SignalStateReason.PRACTICE_AUTONOMY_OFF);
            }
            log.debug(
                    "Practice review gate: SKIP, reason=noMatchingPractices, subject={}, signal={}, draft={}, "
                            + "workspaceId={}",
                    subject,
                    signal,
                    draft,
                    workspace.getId());
            return new GateDecision.Skip(
                    draft ? "no practices bound to this signal on drafts" : "no matching practices");
        }
        return new GateDecision.Detect(
                workspace, match.admitted(), workspace.getReviewSettings().getRolloutRevision(), triggerMode);
    }

    private record SignalMatch(List<Practice> admitted, boolean hasDisabledPractice) {}

    private SignalMatch findMatchingPractices(Workspace workspace, SignalName signal, boolean draft) {
        boolean requestedByHand = signalOptions.isManualRequest(signal);
        List<Practice> bound = practiceRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(p -> p.getBindings().stream()
                        .anyMatch(binding -> requestedByHand
                                ? binding.appliesTo(signal.artifactKind())
                                : binding.occasionedBy(signal, draft)))
                .toList();
        PracticeAutonomy workspaceDefault =
                WorkspaceReviewDefaults.of(workspace).defaultAutonomy();
        List<Practice> admitted = bound.stream()
                .filter(p -> AutonomyResolver.effectiveAutonomyOf(p, workspaceDefault)
                        .admitsReview())
                .toList();
        return new SignalMatch(admitted, admitted.isEmpty() && !bound.isEmpty());
    }
}
