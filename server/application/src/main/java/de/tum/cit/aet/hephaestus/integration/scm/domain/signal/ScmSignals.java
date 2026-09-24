package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import static de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent.TriggerEventNames;

import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalRevision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The signal vocabulary of the shared SCM domain, and the per-signal rule for what counts as a new
 * occurrence.
 *
 * <p>Names are vendor-neutral because {@code PullRequest} and {@code Issue} already are: a practice
 * writes {@code scm.pull_request.merged} once and it works on GitHub and GitLab alike.
 *
 * <p>The revision scheme is per signal rather than per kind. An issue has no commits at all, so keying
 * its signals on anything code-shaped is impossible; and even for a pull request, editing the
 * description moves no SHA, which is why a description-shaped signal must digest what was written
 * instead.
 */
public final class ScmSignals {

    public static final ArtifactKind PULL_REQUEST = ArtifactKind.of("scm.pull_request");
    public static final ArtifactKind ISSUE = ArtifactKind.of("scm.issue");

    public static final SignalName PULL_REQUEST_OPENED = SignalName.of("scm.pull_request.opened");
    public static final SignalName PULL_REQUEST_READY = SignalName.of("scm.pull_request.ready");
    public static final SignalName PULL_REQUEST_SYNCHRONIZED = SignalName.of("scm.pull_request.synchronized");
    public static final SignalName PULL_REQUEST_REVIEWED = SignalName.of("scm.pull_request.reviewed");
    public static final SignalName PULL_REQUEST_MERGED = SignalName.of("scm.pull_request.merged");
    public static final SignalName PULL_REQUEST_CLOSED = SignalName.of("scm.pull_request.closed");
    /**
     * Somebody asked Hephaestus for a review of this pull request by hand.
     *
     * <p>Deliberately not {@code review_requested}: GitHub's {@code pull_request} webhook already uses
     * that action for a human reviewer being assigned. No vendor can raise this signal — its descriptor
     * declares an empty provenance.
     */
    public static final SignalName PULL_REQUEST_MANUAL_REVIEW = SignalName.of("scm.pull_request.manual_review");

    public static final SignalName ISSUE_OPENED = SignalName.of("scm.issue.opened");
    public static final SignalName ISSUE_UPDATED = SignalName.of("scm.issue.updated");
    public static final SignalName ISSUE_CLOSED = SignalName.of("scm.issue.closed");
    public static final SignalName ISSUE_MANUAL_REVIEW = SignalName.of("scm.issue.manual_review");

    /** Fields used by issue review evidence; other mirror changes do not retire its observations. */
    public static final Set<String> REVIEWABLE_ISSUE_FIELDS =
            Set.of("title", "body", "state", "stateReason", "issueType", "milestone", "relationships");

    private static final Map<String, SignalName> BY_TRIGGER_EVENT = Map.of(
            TriggerEventNames.PULL_REQUEST_CREATED,
            PULL_REQUEST_OPENED,
            TriggerEventNames.PULL_REQUEST_READY,
            PULL_REQUEST_READY,
            TriggerEventNames.PULL_REQUEST_SYNCHRONIZED,
            PULL_REQUEST_SYNCHRONIZED,
            TriggerEventNames.REVIEW_SUBMITTED,
            PULL_REQUEST_REVIEWED,
            TriggerEventNames.PULL_REQUEST_MERGED,
            PULL_REQUEST_MERGED,
            TriggerEventNames.PULL_REQUEST_CLOSED,
            PULL_REQUEST_CLOSED,
            TriggerEventNames.ISSUE_CREATED,
            ISSUE_OPENED,
            TriggerEventNames.ISSUE_UPDATED,
            ISSUE_UPDATED,
            TriggerEventNames.ISSUE_CLOSED,
            ISSUE_CLOSED);

    private static final Map<SignalName, String> TRIGGER_EVENT_BY_SIGNAL = BY_TRIGGER_EVENT.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private static final Map<SignalName, RevisionScheme> SCHEMES = Map.ofEntries(
            Map.entry(PULL_REQUEST_OPENED, RevisionScheme.HEAD_COMMIT),
            Map.entry(PULL_REQUEST_READY, RevisionScheme.HEAD_COMMIT),
            Map.entry(PULL_REQUEST_SYNCHRONIZED, RevisionScheme.HEAD_COMMIT),
            Map.entry(PULL_REQUEST_REVIEWED, RevisionScheme.EVENT_ID),
            Map.entry(PULL_REQUEST_MERGED, RevisionScheme.TERMINAL_STATE),
            Map.entry(PULL_REQUEST_CLOSED, RevisionScheme.TERMINAL_STATE),
            Map.entry(PULL_REQUEST_MANUAL_REVIEW, RevisionScheme.RUN_ID),
            Map.entry(ISSUE_OPENED, RevisionScheme.CONTENT_DIGEST),
            Map.entry(ISSUE_UPDATED, RevisionScheme.CONTENT_DIGEST),
            Map.entry(ISSUE_CLOSED, RevisionScheme.CONTENT_DIGEST),
            Map.entry(ISSUE_MANUAL_REVIEW, RevisionScheme.RUN_ID));

    private ScmSignals() {}

    public static Optional<SignalName> forTriggerEvent(@Nullable String triggerEventName) {
        return Optional.ofNullable(triggerEventName).map(BY_TRIGGER_EVENT::get);
    }

    /** Every trigger-event literal this domain translates, for callers validating an authoring vocabulary. */
    public static Set<String> triggerEventNames() {
        return BY_TRIGGER_EVENT.keySet();
    }

    /** The trigger-event literal a signal came from, for re-running the gate on a pending signal. */
    public static Optional<String> triggerEventFor(SignalName signal) {
        return Optional.ofNullable(TRIGGER_EVENT_BY_SIGNAL.get(signal));
    }

    public static RevisionScheme revisionScheme(SignalName signal) {
        RevisionScheme scheme = SCHEMES.get(signal);
        if (scheme == null) {
            throw new IllegalArgumentException("No revision scheme declared for signal: " + signal);
        }
        return scheme;
    }

    /**
     * The ledger identity of a pull-request signal.
     *
     * @param headRefOid the head commit, needed only by code-shaped signals; empty when the mirror has
     *                   no head ref yet, in which case there is nothing stable to key on
     */
    public static Optional<SignalKey> pullRequestKey(
            long workspaceId,
            long pullRequestId,
            SignalName signal,
            @Nullable String headRefOid,
            String title,
            @Nullable String body) {
        if (!PULL_REQUEST.equals(signal.artifactKind())) {
            return Optional.empty();
        }
        return revisionFor(signal, headRefOid, title, body)
                .map(revision -> new SignalKey(workspaceId, pullRequestId, signal, revision));
    }

    /**
     * The ledger identity of an issue's opening, keyed on what its author wrote.
     *
     * <p>Deliberately blind to labels, assignees, milestone and type: a backfill sweep re-derives this
     * key from the artifact as it stands, so keying it on metadata triage moves would give an untouched
     * issue a fresh identity every time somebody labelled it, and buy a second review of the same prose.
     * {@link #ISSUE_UPDATED} is the one occasion that metadata <em>is</em>.
     */
    public static Optional<SignalKey> issueOpenedKey(
            long workspaceId, long issueId, String title, @Nullable String body) {
        return issueKey(workspaceId, issueId, ISSUE_OPENED, title, body);
    }

    /**
     * The ledger identity of an issue's close: the moment it closed.
     *
     * <p>An issue is not a pull request — it can be reopened, so a close is not a state it cannot leave
     * and a constant revision would let the first close settle the row a later one needs. Two closes are
     * two moments and two retrospective reviews; a redelivery of one close carries the same moment and
     * is inert. The issue's own content is deliberately absent, so triaging a long-closed issue does not
     * make the next backfill sweep re-run the review of how it ended.
     *
     * <p>A mirror row without a close timestamp keys on the absence instead, which is again constant per
     * issue: neither provider guarantees {@code closed_at}, and inventing a moment would mint an
     * occurrence nobody observed.
     */
    public static Optional<SignalKey> issueClosedKey(long workspaceId, long issueId, @Nullable Instant closedAt) {
        return issueKey(workspaceId, issueId, ISSUE_CLOSED, closedAt != null ? closedAt.toString() : null);
    }

    /**
     * The ledger identity of an issue signal raised by a domain event, dispatched to the rule for that
     * occasion. {@link #ISSUE_UPDATED} digests the whole review-relevant snapshot, because that occasion
     * is precisely "this snapshot differs from the last one", so two deliveries describing the same
     * snapshot are one occurrence. Labels and assignees are sorted here, because a provider may order
     * them however it likes and the digest may not depend on that.
     */
    public static Optional<SignalKey> issueKey(long workspaceId, SignalName signal, ScmEventPayload.IssueData issue) {
        if (signal.equals(ISSUE_CLOSED)) {
            return issueClosedKey(workspaceId, issue.id(), issue.closedAt());
        }
        if (!signal.equals(ISSUE_UPDATED)) {
            return issueKey(workspaceId, issue.id(), signal, issue.title(), issue.body());
        }
        return Optional.of(new SignalKey(workspaceId, issue.id(), signal, issueUpdatedRevision(issue)));
    }

    /** Canonical issue evidence identity, shared by occasion discovery and the capture fence. */
    public static SignalRevision issueUpdatedRevision(ScmEventPayload.IssueData issue) {
        return SignalRevision.ofContentDigest(
                issue.title(),
                issue.body(),
                issue.state().name(),
                issue.stateReason(),
                issue.issueType(),
                issue.milestone(),
                joinSorted(issue.labels()),
                joinSorted(issue.assignees()));
    }

    private static Optional<SignalKey> issueKey(
            long workspaceId, long issueId, SignalName signal, @Nullable String... content) {
        if (!ISSUE.equals(signal.artifactKind())) {
            return Optional.empty();
        }
        return revisionFor(signal, null, content)
                .map(revision -> new SignalKey(workspaceId, issueId, signal, revision));
    }

    /** The ledger identity of an explicitly requested review: the ask itself, so two asks are two runs. */
    public static SignalKey manualKey(long workspaceId, long artifactId, SignalName signal, UUID runId) {
        return new SignalKey(workspaceId, artifactId, signal, SignalRevision.ofRunId(runId));
    }

    /** One submitted review is one occurrence, even when several reviews concern the same head commit. */
    public static SignalKey pullRequestReviewKey(long workspaceId, long pullRequestId, long reviewId) {
        return new SignalKey(workspaceId, pullRequestId, PULL_REQUEST_REVIEWED, SignalRevision.ofEventId(reviewId));
    }

    private static Optional<SignalRevision> revisionFor(
            SignalName signal, @Nullable String headRefOid, @Nullable String... content) {
        return switch (revisionScheme(signal)) {
            case HEAD_COMMIT ->
                headRefOid == null || headRefOid.isBlank()
                        ? Optional.empty()
                        : Optional.of(SignalRevision.ofHeadCommit(headRefOid));
            case CONTENT_DIGEST -> Optional.of(SignalRevision.ofContentDigest(content));
            // Constant by construction: the artifact reached a state it cannot leave, so the signal can
            // occur once and a redelivery has nothing new to say.
            case TERMINAL_STATE -> Optional.of(SignalRevision.ofTerminalState(lastSegmentOf(signal)));
            // A requested run carries its own identity; no domain event raises one.
            case RUN_ID -> Optional.empty();
            case EVENT_ID -> Optional.empty();
        };
    }

    private static String lastSegmentOf(SignalName signal) {
        return signal.value().substring(signal.value().lastIndexOf('.') + 1);
    }

    /** Unit separator: no label or login may contain it, so no two lists join to the same string. */
    private static String joinSorted(List<String> values) {
        return values.stream().sorted().collect(Collectors.joining("\u001f"));
    }
}
