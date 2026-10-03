package de.tum.cit.aet.hephaestus.integration.scm.domain.signal;

import static de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent.TriggerEventNames;
import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.core.signal.RevisionScheme;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalKey;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What counts as a new occurrence is the whole reason the ledger works, so these tests are written as
 * the coaching scenarios they protect rather than as mapping-table assertions.
 */
class ScmSignalsTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 7L;
    private static final long ARTIFACT_ID = 42L;

    private Optional<SignalKey> pullRequest(
            String triggerEvent, @Nullable String headRefOid, String title, String body) {
        return ScmSignals.pullRequestKey(
                WORKSPACE_ID,
                ARTIFACT_ID,
                ScmSignals.forTriggerEvent(triggerEvent).orElseThrow(),
                headRefOid,
                title,
                body);
    }

    private Optional<SignalKey> issue(String triggerEvent, String title, String body) {
        return ScmSignals.issueKey(
                WORKSPACE_ID, ScmSignals.forTriggerEvent(triggerEvent).orElseThrow(), issueData(title, body));
    }

    private SignalKey updated(List<String> labels, List<String> assignees) {
        ScmEventPayload.IssueData issue = new ScmEventPayload.IssueData(
                ARTIFACT_ID,
                1,
                "Bug",
                "Steps",
                Issue.State.OPEN,
                null,
                null,
                false,
                new RepositoryRef(1L, "owner/repo", "main"),
                null,
                "Bug",
                "v1",
                labels,
                assignees,
                null,
                null,
                null);
        return ScmSignals.issueKey(WORKSPACE_ID, ScmSignals.ISSUE_UPDATED, issue)
                .orElseThrow();
    }

    private ScmEventPayload.IssueData issueData(String title, String body) {
        return new ScmEventPayload.IssueData(
                ARTIFACT_ID,
                1,
                title,
                body,
                Issue.State.OPEN,
                null,
                null,
                false,
                new RepositoryRef(1L, "owner/repo", "main"),
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null);
    }

    private SignalKey closed(ScmEventPayload.IssueData issue) {
        return ScmSignals.issueKey(WORKSPACE_ID, ScmSignals.ISSUE_CLOSED, issue).orElseThrow();
    }

    private ScmEventPayload.IssueData closedData(Instant closedAt) {
        return new ScmEventPayload.IssueData(
                ARTIFACT_ID,
                1,
                "Bug",
                "Steps",
                Issue.State.CLOSED,
                "COMPLETED",
                null,
                false,
                new RepositoryRef(1L, "owner/repo", "main"),
                null,
                "Bug",
                null,
                List.of(),
                List.of(),
                null,
                closedAt,
                closedAt);
    }

    @Test
    void shouldNotLetProviderOrderingOfLabelsAndAssigneesMoveTheRevision() {
        SignalKey asGitHubSentThem = updated(List.of("backend", "urgent"), List.of("alice", "bob"));
        SignalKey asGitLabSentThem = updated(List.of("urgent", "backend"), List.of("bob", "alice"));

        assertThat(asGitLabSentThem).isEqualTo(asGitHubSentThem);
    }

    @Test
    void shouldReMeasureAnIssueWhoseTriageMetadataMoved() {
        SignalKey before = updated(List.of("backend", "urgent"), List.of("alice", "bob"));

        assertThat(updated(List.of("backend"), List.of("alice", "bob"))).isNotEqualTo(before);
        assertThat(updated(List.of("backend", "urgent"), List.of("alice"))).isNotEqualTo(before);
    }

    @Test
    void shouldNotReMeasureAnIssueWhoseTriageReturnedItToASnapshotAlreadySeen() {
        SignalKey before = updated(List.of("backend"), List.of("alice"));
        SignalKey labelled = updated(List.of("backend", "urgent"), List.of("alice"));
        SignalKey labelRemovedAgain = updated(List.of("backend"), List.of("alice"));

        assertThat(labelled).isNotEqualTo(before);
        assertThat(labelRemovedAgain).isEqualTo(before);
    }

    @Test
    void shouldMakeARedeliveredCloseOfTheSameIssueInert() {
        Instant closedAt = Instant.parse("2026-09-04T08:00:00Z");

        SignalKey first = closed(closedData(closedAt));
        SignalKey redelivered = closed(closedData(closedAt));

        assertThat(redelivered).isEqualTo(first);
    }

    /**
     * An issue can be reopened, so its close is not a state it cannot leave: the outcome of the second
     * round of work is a second thing to review, and a constant revision would let the first close
     * settle the row the second one needs.
     */
    @Test
    void shouldOccasionASecondCloseAfterAReopen() {
        SignalKey first = closed(closedData(Instant.parse("2026-09-04T08:00:00Z")));
        SignalKey afterReopeningAndClosingAgain = closed(closedData(Instant.parse("2026-09-04T09:00:00Z")));

        assertThat(afterReopeningAndClosingAgain).isNotEqualTo(first);
    }

    /**
     * The occasion a backfill sweep measures a closed issue at. Keying it on the issue's content would
     * make labelling a long-closed issue re-run the review of how it ended on the next sweep.
     */
    @Test
    void shouldKeepAnIssueClosedIdentityOutOfReachOfTriage() {
        Instant closedAt = Instant.parse("2026-09-04T08:00:00Z");
        ScmEventPayload.IssueData triagedSinceClosing = new ScmEventPayload.IssueData(
                ARTIFACT_ID,
                1,
                "Bug, renamed after the fact",
                "Steps",
                Issue.State.CLOSED,
                "COMPLETED",
                null,
                false,
                new RepositoryRef(1L, "owner/repo", "main"),
                null,
                "Bug",
                "v2",
                List.of("wontfix"),
                List.of("alice"),
                null,
                closedAt,
                closedAt);

        assertThat(closed(triagedSinceClosing)).isEqualTo(closed(closedData(closedAt)));
        assertThat(ScmSignals.issueClosedKey(WORKSPACE_ID, ARTIFACT_ID, closedAt))
                .contains(closed(closedData(closedAt)));
    }

    /** A provider that omits the close moment leaves nothing to tell two closes apart. */
    @Test
    void shouldFallBackToOneCloseOccasionWhenTheProviderNamesNoCloseMoment() {
        assertThat(ScmSignals.issueClosedKey(WORKSPACE_ID, ARTIFACT_ID, null))
                .isEqualTo(ScmSignals.issueClosedKey(WORKSPACE_ID, ARTIFACT_ID, null))
                .isNotEqualTo(
                        ScmSignals.issueClosedKey(WORKSPACE_ID, ARTIFACT_ID, Instant.parse("2026-09-04T08:00:00Z")));
    }

    /**
     * The occasion a backfill sweep measures an open issue at. Keying it on the whole snapshot would give
     * a triaged issue a fresh identity, and the next sweep would review it again as if newly opened.
     */
    @Test
    void shouldKeepAnIssueOpenedIdentityOutOfReachOfTriage() {
        SignalKey fromProse = ScmSignals.issueOpenedKey(WORKSPACE_ID, ARTIFACT_ID, "Bug", "Steps")
                .orElseThrow();
        SignalKey afterTriage = ScmSignals.issueKey(
                        WORKSPACE_ID,
                        ScmSignals.ISSUE_OPENED,
                        new ScmEventPayload.IssueData(
                                ARTIFACT_ID,
                                1,
                                "Bug",
                                "Steps",
                                Issue.State.OPEN,
                                null,
                                null,
                                false,
                                new RepositoryRef(1L, "owner/repo", "main"),
                                null,
                                "Bug",
                                "v1",
                                List.of("needs-triage"),
                                List.of("alice"),
                                null,
                                null,
                                null))
                .orElseThrow();

        assertThat(afterTriage).isEqualTo(fromProse);
    }

    @Test
    void shouldReMeasureAnIssueAfterItsAuthorRewritesTheDescription() {
        SignalKey before =
                issue(TriggerEventNames.ISSUE_CREATED, "Bug", "it broke").orElseThrow();
        SignalKey after = issue(TriggerEventNames.ISSUE_CREATED, "Bug", "Login 500s on expired token")
                .orElseThrow();

        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @Test
    void shouldNotReMeasureAnIssueThatWasMerelyRedelivered() {
        SignalKey first =
                issue(TriggerEventNames.ISSUE_CREATED, "Bug", "it broke").orElseThrow();
        SignalKey redelivered =
                issue(TriggerEventNames.ISSUE_CREATED, "Bug", "it broke").orElseThrow();

        assertThat(redelivered).isEqualTo(first);
    }

    @Test
    void shouldNotReReviewUnchangedCodeBecauseTheDescriptionWasEdited() {
        SignalKey before = pullRequest(TriggerEventNames.PULL_REQUEST_SYNCHRONIZED, "abc123", "Add cache", "faster")
                .orElseThrow();
        SignalKey after = pullRequest(
                        TriggerEventNames.PULL_REQUEST_SYNCHRONIZED, "abc123", "Add cache", "cuts p99 by 40%")
                .orElseThrow();

        assertThat(after).isEqualTo(before);
    }

    @Test
    void shouldReReviewAfterAPush() {
        SignalKey before = pullRequest(TriggerEventNames.PULL_REQUEST_SYNCHRONIZED, "abc123", "Add cache", "body")
                .orElseThrow();
        SignalKey after = pullRequest(TriggerEventNames.PULL_REQUEST_SYNCHRONIZED, "def456", "Add cache", "body")
                .orElseThrow();

        assertThat(after.revision()).isNotEqualTo(before.revision());
    }

    @Test
    void shouldMakeARedeliveredMergeInert() {
        SignalKey merged = pullRequest(TriggerEventNames.PULL_REQUEST_MERGED, "abc123", "Add cache", "body")
                .orElseThrow();
        SignalKey redelivered = pullRequest(TriggerEventNames.PULL_REQUEST_MERGED, "def456", "Add cache", "body")
                .orElseThrow();

        assertThat(redelivered).isEqualTo(merged);
    }

    @Test
    void shouldKeepMergedAndClosedApart() {
        SignalKey merged = pullRequest(TriggerEventNames.PULL_REQUEST_MERGED, "abc123", "t", "b")
                .orElseThrow();
        SignalKey closed = pullRequest(TriggerEventNames.PULL_REQUEST_CLOSED, "abc123", "t", "b")
                .orElseThrow();

        assertThat(closed).isNotEqualTo(merged);
    }

    @Test
    void shouldDeclineToKeyACodeShapedSignalWithNoHeadCommit() {
        assertThat(pullRequest(TriggerEventNames.PULL_REQUEST_READY, null, "t", "b"))
                .isEmpty();
    }

    @Test
    void shouldDeriveTheArtifactKindFromTheSignalRatherThanTheCaller() {
        SignalKey key = pullRequest(TriggerEventNames.PULL_REQUEST_READY, "abc123", "t", "b")
                .orElseThrow();

        assertThat(key.artifactKind()).isEqualTo(ScmSignals.PULL_REQUEST);
    }

    @Test
    void shouldRoundTripEverySignalBackToTheTriggerEventThatRaisedIt() {
        for (String triggerEvent : new String[] {
            TriggerEventNames.PULL_REQUEST_CREATED,
            TriggerEventNames.PULL_REQUEST_READY,
            TriggerEventNames.PULL_REQUEST_SYNCHRONIZED,
            TriggerEventNames.REVIEW_SUBMITTED,
            TriggerEventNames.PULL_REQUEST_MERGED,
            TriggerEventNames.PULL_REQUEST_CLOSED,
            TriggerEventNames.ISSUE_CREATED,
            TriggerEventNames.ISSUE_UPDATED,
            TriggerEventNames.ISSUE_CLOSED,
        }) {
            var signal = ScmSignals.forTriggerEvent(triggerEvent).orElseThrow();
            assertThat(ScmSignals.triggerEventFor(signal)).as(triggerEvent).contains(triggerEvent);
        }
    }

    @Test
    void shouldGiveEveryDeclaredSignalARevisionScheme() {
        for (var signal : new SignalName[] {
            ScmSignals.PULL_REQUEST_OPENED,
            ScmSignals.PULL_REQUEST_READY,
            ScmSignals.PULL_REQUEST_SYNCHRONIZED,
            ScmSignals.PULL_REQUEST_REVIEWED,
            ScmSignals.PULL_REQUEST_MERGED,
            ScmSignals.PULL_REQUEST_CLOSED,
            ScmSignals.PULL_REQUEST_MANUAL_REVIEW,
            ScmSignals.ISSUE_OPENED,
            ScmSignals.ISSUE_UPDATED,
            ScmSignals.ISSUE_CLOSED,
            ScmSignals.ISSUE_MANUAL_REVIEW,
        }) {
            assertThat(ScmSignals.revisionScheme(signal)).as(signal.value()).isNotNull();
        }
        assertThat(ScmSignals.revisionScheme(ScmSignals.ISSUE_OPENED)).isEqualTo(RevisionScheme.CONTENT_DIGEST);
        assertThat(ScmSignals.revisionScheme(ScmSignals.ISSUE_UPDATED)).isEqualTo(RevisionScheme.CONTENT_DIGEST);
        assertThat(ScmSignals.revisionScheme(ScmSignals.ISSUE_CLOSED)).isEqualTo(RevisionScheme.CONTENT_DIGEST);
        assertThat(ScmSignals.revisionScheme(ScmSignals.PULL_REQUEST_READY)).isEqualTo(RevisionScheme.HEAD_COMMIT);
        assertThat(ScmSignals.revisionScheme(ScmSignals.PULL_REQUEST_REVIEWED)).isEqualTo(RevisionScheme.EVENT_ID);
    }

    @Test
    void shouldTreatDistinctSubmittedReviewsAsDistinctOccurrences() {
        var first = ScmSignals.pullRequestReviewKey(1L, 42L, 100L);
        var second = ScmSignals.pullRequestReviewKey(1L, 42L, 101L);

        assertThat(first.revision()).isNotEqualTo(second.revision());
        assertThat(first.revision().scheme()).contains(RevisionScheme.EVENT_ID);
    }
}
