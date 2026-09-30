package de.tum.cit.aet.hephaestus.practices.review;

import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalStateReason;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewSubject;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeSignalOptions;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.ObservationKind;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.observation.LatestRun;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import de.tum.cit.aet.hephaestus.practices.review.autonomy.AutonomyResolver;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeReviewReadiness;
import de.tum.cit.aet.hephaestus.practices.spi.RevisedWorkLookup;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceResolver;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
    private final AutomatedReviewFence fence;
    private final ObservationRepository observations;
    private final ObservationVisibilityPolicy observationVisibility;
    private final RevisedWorkLookup revisedWork;

    public PracticeReviewDetectionGate(
            PracticeReviewReadiness practiceDetectionReadiness,
            PracticeRepository practiceRepository,
            WorkspaceResolver workspaceResolver,
            PracticeSignalOptions signalOptions,
            PracticeReviewCoverageService coverageService,
            AutomatedReviewFence fence,
            ObservationRepository observations,
            ObservationVisibilityPolicy observationVisibility,
            RevisedWorkLookup revisedWork) {
        this.practiceDetectionReadiness = practiceDetectionReadiness;
        this.practiceRepository = practiceRepository;
        this.workspaceResolver = workspaceResolver;
        this.signalOptions = signalOptions;
        this.coverageService = coverageService;
        this.fence = fence;
        this.observations = observations;
        this.observationVisibility = observationVisibility;
        this.revisedWork = revisedWork;
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

    /**
     * The pull request as reviewed by the workspace that queued its occasion: a repository monitored elsewhere since
     * is refused, not reviewed there. An occasion that {@code revisedWork} also admits the practices whose current
     * word on the pull request is a problem of its author's, whatever occasion they are bound to.
     */
    public GateDecision evaluateQueued(
            @NonNull PullRequest pullRequest,
            long workspaceId,
            @NonNull SignalName signal,
            @NonNull ReviewSubject subject,
            boolean revisedWork) {
        String repository = pullRequest.getRepository() != null
                ? pullRequest.getRepository().getNameWithOwner()
                : null;
        return workspaceResolver.resolveAllForRepository(repository).stream()
                .filter(workspace -> Objects.equals(workspace.getId(), workspaceId))
                .findFirst()
                .<GateDecision>map(workspace -> evaluateReviewableInWorkspace(
                        pullRequest,
                        workspace,
                        pullRequest.isDraft(),
                        signal,
                        TriggerMode.AUTO,
                        false,
                        subject,
                        revisedWork))
                .orElseGet(() -> new GateDecision.Skip(
                        "workspace no longer monitors this pull request", SignalStateReason.OUT_OF_REVIEW_SCOPE));
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
                issue, workspace, false, signal, triggerMode, false, issue.reviewSubject(), false);
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
                issue, workspace, false, signal, TriggerMode.MANUAL, true, issue.reviewSubject(), false);
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
                workspace, signal, false, triggerMode, "workspace:" + workspace.getId(), scopeSkip, Set.of());
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
                reviewable, workspace, draft, signal, triggerMode, allowOutsideCoverage, subject, false);
    }

    private GateDecision evaluateReviewableInWorkspace(
            Issue reviewable,
            Workspace workspace,
            boolean draft,
            SignalName signal,
            TriggerMode triggerMode,
            boolean allowOutsideCoverage,
            ReviewSubject subject,
            boolean recheck) {
        String nameWithOwner =
                reviewable.getRepository() != null ? reviewable.getRepository().getNameWithOwner() : null;

        GateDecision.@Nullable Skip scopeSkip = null;
        String targetBranch = reviewable instanceof PullRequest pr ? pr.getBaseRefName() : null;
        var coverage = allowOutsideCoverage
                ? null
                : coverageService.assess(
                        workspace, nameWithOwner, targetBranch, subject, reviewable instanceof PullRequest);
        if (coverage != null && !coverage.admitted()) {
            log.debug(
                    "Practice review gate: SKIP, reason=outsideCoverage, artifactId={}, repo={}, targetBranch={}, subjectId={}, subjectStatus={}",
                    reviewable.getId(),
                    nameWithOwner,
                    targetBranch,
                    subject.actorId(),
                    coverage.subjectStatus());
            scopeSkip = new GateDecision.Skip(
                    "the repository, branch, or linked subject is outside review coverage", scopeSkipReason(coverage));
        }

        return evaluateWorkspaceAndSignal(
                workspace,
                signal,
                draft,
                triggerMode,
                String.valueOf(reviewable.getId()),
                scopeSkip,
                recheck && scopeSkip == null
                        ? practicesToRecheck(reviewable, workspace, draft, signal, triggerMode, subject)
                        : Set.of());
    }

    /**
     * The practices whose current word on this pull request is a problem of its author's, whatever occasion
     * they are bound to, once the work has changed since that word. The claim is read the way every current read
     * reads it — each claim's latest run, then currentness, invalidation and evidence authorization — so a
     * later positive or abstention stands and a withdrawn verdict starts nothing. The work has changed only where
     * the run that recorded it captured a different title, description or head than the pull request holds now: an
     * occasion that settles after a review already read this very work, or a capture that cannot be compared, admits
     * no repair.
     */
    private Set<Long> practicesToRecheck(
            Issue reviewable,
            Workspace workspace,
            boolean draft,
            SignalName signal,
            TriggerMode triggerMode,
            ReviewSubject subject) {
        Long authorId = subject.actorId();
        if (!(reviewable instanceof PullRequest pullRequest)
                || !reviewable.isOpen()
                || draft
                || triggerMode != TriggerMode.AUTO
                || authorId == null) {
            return Set.of();
        }
        List<Observation> negative = LatestRun.perClaim(observations.findStandingForWork(
                        workspace.getId(), signal.artifactKind(), reviewable.getId(), authorId))
                .stream()
                .filter(observation -> ObservationKind.of(observation).isNegative())
                .toList();
        if (negative.isEmpty()) {
            return Set.of();
        }
        Set<UUID> current = observationVisibility.permitsAll(
                workspace.getId(), negative, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
        List<Observation> standing = negative.stream()
                .filter(observation -> current.contains(observation.getId()))
                .toList();
        if (standing.isEmpty()) {
            return Set.of();
        }
        Set<UUID> revised = revisedWork.recordedOnOtherWork(
                workspace.getId(),
                pullRequest.getId(),
                pullRequest.getTitle(),
                pullRequest.getBody(),
                pullRequest.getHeadRefOid(),
                standing);
        return standing.stream()
                .filter(observation -> revised.contains(observation.getId()))
                .map(observation -> observation.getPractice().getId())
                .collect(Collectors.toSet());
    }

    /**
     * A repository or base branch outside the selection is a fact of the work, so that refusal is terminal. An author
     * who is not a member the workspace reviews, or no known author, can change with the next roster sync or link,
     * so that refusal waits and the work is offered again. A member the selection leaves out is the workspace's
     * choice, and terminal like a repository.
     */
    private static SignalStateReason scopeSkipReason(PracticeReviewCoverageService.CoverageAssessment coverage) {
        if (!coverage.repositoryMatched() || !coverage.branchMatched()) {
            return SignalStateReason.OUT_OF_REVIEW_SCOPE;
        }
        return switch (coverage.subjectStatus()) {
            case MISSING, UNLINKED -> SignalStateReason.SUBJECT_UNLINKED;
            case NON_HUMAN, RESOLVED_LINKED_HUMAN -> SignalStateReason.OUT_OF_REVIEW_SCOPE;
        };
    }

    private GateDecision evaluateWorkspaceAndSignal(
            Workspace workspace,
            SignalName signal,
            boolean draft,
            TriggerMode triggerMode,
            String subject,
            GateDecision.@Nullable Skip scopeSkip,
            Set<Long> rechecks) {
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

        SignalMatch match = findMatchingPractices(workspace, signal, draft, rechecks);
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
                workspace,
                match.admitted(),
                workspace.getReviewSettings().getRolloutRevision(),
                triggerMode,
                match.admitted().stream()
                        .filter(p -> isCandidate(p, rechecks) && !occasionedBy(p, signal, draft))
                        .map(Practice::getSlug)
                        .collect(Collectors.toSet()));
    }

    private record SignalMatch(List<Practice> admitted, boolean hasDisabledPractice) {}

    /** A practice rechecked beside the occasion is still one about this work and its author. */
    private static boolean rechecked(Practice practice, SignalName signal, Set<Long> rechecks) {
        return isCandidate(practice, rechecks)
                && practice.getBindings().stream().anyMatch(binding -> binding.appliesTo(signal.artifactKind()))
                && PracticeBinding.subjectRoleOf(practice.getBindings(), null) == ActorRole.AUTHOR;
    }

    private static boolean isCandidate(Practice practice, Set<Long> rechecks) {
        Long id = practice.getId();
        return id != null && rechecks.contains(id);
    }

    private static boolean occasionedBy(Practice practice, SignalName signal, boolean draft) {
        return practice.getBindings().stream().anyMatch(binding -> binding.occasionedBy(signal, draft));
    }

    private SignalMatch findMatchingPractices(
            Workspace workspace, SignalName signal, boolean draft, Set<Long> rechecks) {
        boolean requestedByHand = signalOptions.isManualRequest(signal);
        List<Practice> bound = practiceRepository.findByWorkspaceId(workspace.getId()).stream()
                .filter(p -> requestedByHand
                        ? p.getBindings().stream().anyMatch(binding -> binding.appliesTo(signal.artifactKind()))
                        : occasionedBy(p, signal, draft) || rechecked(p, signal, rechecks))
                // Withdrawn from automated review whatever its stored policy says: bound, but no occasion.
                .filter(p -> fence.withdrawal(p).isEmpty())
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
