package de.tum.cit.aet.hephaestus.practices.advice;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountMembershipQuery;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackBasisRepository;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceAudience;
import de.tum.cit.aet.hephaestus.practices.spi.AdvicePrior;
import de.tum.cit.aet.hephaestus.practices.spi.AdviceRevisionState;
import de.tum.cit.aet.hephaestus.practices.spi.EvidenceRef;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackModeQuery;
import de.tum.cit.aet.hephaestus.practices.spi.PracticeAdviceReads;
import de.tum.cit.aet.hephaestus.practices.spi.PriorState;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads lasting advice for the mentor's next decision and for a conversation's basis (PRACTICE-ADVICE table B). It
 * never returns advice text. Only the owner's reads find anything, and only while the owner is an active member.
 *
 * <p>A revision counts only while it is not stale and all of its sources are live: a deleted observation deletes its
 * source rows, so a revision with fewer rows than {@code source_count} lost evidence by a path that did not redact it.
 */
@Service
@ConditionalOnServerRole
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PracticeAdviceReader implements PracticeAdviceReads {
    private final AccountMembershipQuery memberships;
    private final FeedbackModeQuery modes;
    private final FeedbackBasisRepository basis;
    private final PracticeAdviceRepository entries;
    private final PracticeAdviceRevisionRepository revisions;
    private final PracticeAdviceSourceRepository sources;

    @Override
    public Optional<AdvicePrior> prior(long workspaceId, long accountId, long practiceId, AdviceAudience audience) {
        if (!ownerReads(workspaceId, accountId, audience)) return Optional.empty();
        return entries.findByWorkspaceIdAndPersonAccountIdAndPracticeId(workspaceId, accountId, practiceId)
                .map(entry -> prior(entry, latest(entry)));
    }

    @Override
    public AdviceRevisionState state(long workspaceId, long accountId, UUID revisionId, AdviceAudience audience) {
        if (!ownerReads(workspaceId, accountId, audience)) return AdviceRevisionState.MISSING;
        var revision = revisions.findByIdAndWorkspaceIdAndPersonAccountId(revisionId, workspaceId, accountId);
        if (revision.isEmpty()) return AdviceRevisionState.MISSING;
        var entry = entries.findByIdAndWorkspaceId(revision.get().getAdviceId(), workspaceId);
        if (entry.isEmpty()) return AdviceRevisionState.MISSING;
        var sourcesOf = sources(revision.get());
        if (!complete(revision.get(), sourcesOf)) return AdviceRevisionState.STALE;
        if (withheld(entry.get(), sourcesOf)) return AdviceRevisionState.HIDDEN;
        var latest = entry.get().getLatestRevision();
        if (latest != null && revision.get().getNumber() < latest) return AdviceRevisionState.SUPERSEDED;
        if (entry.get().getState() == AdviceState.RETIRED) return AdviceRevisionState.RETIRED;
        if (entry.get().getDismissedAt() != null) return AdviceRevisionState.HIDDEN;
        return AdviceRevisionState.VISIBLE;
    }

    private AdvicePrior prior(PracticeAdvice entry, @Nullable PracticeAdviceRevision revision) {
        var sourcesOf = revision == null ? List.<PracticeAdviceSource>of() : sources(revision);
        PriorState state;
        if (revision == null || !complete(revision, sourcesOf) || withheld(entry, sourcesOf)) state = PriorState.HIDDEN;
        else if (entry.getState() == AdviceState.RETIRED) state = PriorState.RETIRED;
        else if (entry.getDismissedAt() != null) state = PriorState.DISMISSED;
        else state = PriorState.VISIBLE;
        var items = entry.getDismissedItems();
        return new AdvicePrior(
                entry.getId(),
                entry.getVersion(),
                entry.getLatestRevision(),
                revision == null ? null : revision.getId(),
                state,
                entry.getRetireReason(),
                items == null ? List.of() : Arrays.stream(items).boxed().toList(),
                state == PriorState.VISIBLE
                        ? sourcesOf.stream()
                                .filter(PracticeAdviceSource::isCited)
                                .map(source -> new EvidenceRef(source.getResultId(), source.getProblemId()))
                                .toList()
                        : List.of());
    }

    private @Nullable PracticeAdviceRevision latest(PracticeAdvice entry) {
        var number = entry.getLatestRevision();
        if (number == null) return null;
        return revisions
                .findByAdviceIdAndWorkspaceIdAndNumber(entry.getId(), entry.getWorkspaceId(), number)
                .orElse(null);
    }

    private List<PracticeAdviceSource> sources(PracticeAdviceRevision revision) {
        return sources.findByWorkspaceIdAndRevisionIdOrderByOrdinalAsc(revision.getWorkspaceId(), revision.getId());
    }

    /** Table B row 1. */
    private boolean ownerReads(long workspaceId, long accountId, AdviceAudience audience) {
        return audience == AdviceAudience.OWNER && memberships.isActiveVisibleMember(workspaceId, accountId);
    }

    /** Table B row 3. */
    private static boolean complete(PracticeAdviceRevision revision, List<PracticeAdviceSource> sourcesOf) {
        return revision.getStaleAt() == null && sourcesOf.size() == revision.getSourceCount();
    }

    /** Table B row 4: feedback is off for the practice, or a source is marked incorrect. */
    private boolean withheld(PracticeAdvice entry, List<PracticeAdviceSource> sourcesOf) {
        var practiceId = entry.getPracticeId();
        return modes.effectiveModes(entry.getWorkspaceId(), List.of(practiceId))
                                .getOrDefault(practiceId, FeedbackMode.OFF)
                        == FeedbackMode.OFF
                || (!sourcesOf.isEmpty()
                        && basis.hasIncorrectResults(
                                entry.getWorkspaceId(),
                                sourcesOf.stream()
                                        .map(PracticeAdviceSource::getResultId)
                                        .toList()));
    }
}
