package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.CitationSide;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyReceipt;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.LineNote;
import de.tum.cit.aet.hephaestus.integration.core.spi.LiveHead;
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.ScriptedCommentThreads;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GitLabBulkPublicationTest extends BaseUnitTest {
    private static final String MARKER = "<!-- hephaestus-feedback-copy:summary -->";
    private static final FeedbackTarget TARGET = ScmFeedbackFixtures.target(
            new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", "c".repeat(40));
    private final OutboundEgressGuard guard = mock(OutboundEgressGuard.class);
    private final ScriptedGitLabNotes provider = new ScriptedGitLabNotes(guard);
    private final GitLabTokenService tokens = mock(GitLabTokenService.class);
    private final GitLabDiffVersionReader versions = mock(GitLabDiffVersionReader.class);
    private GitLabLineNoteWriter writer;

    @BeforeEach
    void setUp() {
        writer = new GitLabLineNoteWriter(
                provider.provider(),
                new GitLabNotesApi(provider.provider(), () -> provider.rest(), tokens, guard),
                tokens,
                guard,
                ScmFeedbackFixtures.resolver(),
                versions,
                ScmFeedbackFixtures.formatter());
        lenient().when(tokens.resolveServerUrl(1L)).thenReturn("https://gitlab.example");
        lenient().when(tokens.getAccessToken(1L)).thenReturn("test-token");
        lenient().when(versions.read(eq(1L), any(), any())).thenReturn(GitLabDiffPositionsTest.VERSION);
    }

    @Test
    void shouldPublishTheSummaryAndAllDraftNotesWithOneBulkNotification() {
        var publication = publication();
        var result = writer.postPackage(TARGET, publication, (ids, done) -> true);
        assertThat(result.receipts()).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(provider.bulkPublications()).isEqualTo(1);
        assertThat(provider.pendingDrafts()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42)))
                .hasSize(3)
                .allSatisfy(note -> assertThat(note.body())
                        .contains(
                                "AI-generated",
                                "Answer or disagree",
                                "settings#practice-feedback",
                                "<!-- hephaestus-feedback-copy:"));
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42)))
                .anySatisfy(note -> assertThat(note.body())
                        .isEqualTo(ScmFeedbackFixtures.formatter()
                                .footer(TARGET.item())
                                .summary(Objects.requireNonNull(publication.summary()))));
    }

    @Test
    void shouldPostCodeAndPunctuationAsWrittenWhenANoteHoldsMarkdown() {
        var line = new FeedbackAnchor("new.java", null, 21, CitationSide.NEW);
        var publication = new OnWorkPackage(
                null,
                List.of(
                        note("mention", line, "Ask @alice"),
                        note("code", line, "`deactivateUser` returns `false` here; log it, @alice.")));

        writer.postPackage(TARGET, publication, (ids, done) -> true);

        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42)))
                .extracting(ScriptedCommentThreads.Comment::body)
                .containsExactly(
                        delivered("Ask `@alice`", "mention"),
                        delivered("`deactivateUser` returns `false` here; log it, `@alice`.", "code"));
    }

    @Test
    void shouldDeleteItsPartialDraftsWithoutPostingAPartialReview() {
        provider.failDraft(2);
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).hasSize(3).noneMatch(CopyReceipt::unconfirmed);
        assertThat(provider.pendingDrafts()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
        assertThat(provider.bulkPublications()).isZero();
    }

    @Test
    void shouldKeepTheDraftsForReadbackWhenAProxyAnswersADraftCreationWithAServerError() {
        provider.gatewayTimeoutOnDraft(2);
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).hasSize(3).allMatch(CopyReceipt::unconfirmed);
        assertThat(provider.pendingDrafts()).isEqualTo(2);
        assertThat(provider.bulkPublications()).isZero();
    }

    @Test
    void shouldKeepTheDraftsForReadbackWhenADraftCreationLostItsAnswer() {
        provider.loseDraftAnswer(2);
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).hasSize(3).allMatch(CopyReceipt::unconfirmed);
        assertThat(provider.pendingDrafts()).isEqualTo(2);
        assertThat(provider.bulkPublications()).isZero();

        assertThat(writer.findPosted(TARGET, publication())).isEmpty();
        assertThat(provider.pendingDrafts()).isZero();
    }

    @Test
    void shouldAdoptPartialDraftsThatCouldNotBeDeletedOnRetryWithoutPostingAPartialReview() {
        provider.failDraft(2);
        provider.failDelete(true);
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).noneMatch(CopyReceipt::acknowledged).allMatch(CopyReceipt::unconfirmed);
        assertThat(provider.pendingDrafts()).isEqualTo(1);
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
        assertThat(provider.bulkPublications()).isZero();
        provider.failDraft(0);
        provider.failDelete(false);
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .hasSize(3)
                .allMatch(CopyReceipt::acknowledged);
        assertThat(provider.bulkPublications()).isEqualTo(1);
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).hasSize(3);
    }

    @Test
    void shouldReadBackThePackageAfterALostBulkPublishResponseWithoutPublishingTwice() {
        provider.losePublish(true);
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .noneMatch(CopyReceipt::acknowledged);
        assertThat(writer.findPosted(TARGET, publication())).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .hasSize(3)
                .allMatch(CopyReceipt::acknowledged);
        assertThat(provider.bulkPublications()).isEqualTo(1);
    }

    @Test
    void shouldRefuseUnrelatedDraftNotesWithoutPublishingOrDeletingThem() {
        provider.foreignDraft();
        assertThat(writer.postPackage(TARGET, publication(), (ids, done) -> true)
                        .receipts())
                .noneMatch(CopyReceipt::acknowledged);
        assertThat(provider.pendingDrafts()).isEqualTo(1);
        assertThat(provider.bulkPublications()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
    }

    @Test
    void shouldDeleteItsDraftsSoTheNextPackageDeliversWhenTheFenceIsRevokedAfterCreatingThem() {
        var refused = writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        assertThat(refused.receipts()).hasSize(3).noneMatch(CopyReceipt::unconfirmed);
        assertThat(provider.pendingDrafts()).isZero();
        assertThat(provider.bulkPublications()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();

        var next = writer.postPackage(TARGET, otherPublication(), (ids, done) -> true);
        assertThat(next.receipts()).hasSize(3).allMatch(CopyReceipt::acknowledged);
        assertThat(provider.bulkPublications()).isEqualTo(1);
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42)))
                .hasSize(3)
                .allSatisfy(note -> assertThat(note.body()).contains("hephaestus-feedback-copy:other-"));
    }

    @Test
    void shouldStayUnconfirmedWhenItsDraftsCannotBeDeletedAfterTheFenceIsRevoked() {
        provider.failDelete(true);
        var refused = writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        assertThat(refused.receipts()).hasSize(3).allMatch(CopyReceipt::unconfirmed);
        assertThat(provider.pendingDrafts()).isEqualTo(2);
        assertThat(provider.bulkPublications()).isZero();
    }

    @Test
    void shouldLeaveAPersonsDraftThatQuotesTheMarkersWhenItDeletesTheLeftover() {
        provider.failDelete(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        provider.failDelete(false);
        provider.personDraft("See this note:\n" + "<!-- hephaestus-feedback-copy:line -->\n"
                + ScmFeedbackFormatter.deliveryTag("line") + "\nI disagree with it.");

        assertThat(writer.findPosted(TARGET, publication())).isEmpty();
        assertThat(provider.pendingDrafts()).isEqualTo(1);
    }

    @Test
    void shouldReadEveryDraftOnceWhenGitLabAnswersMoreThanOnePageOfThem() {
        provider.failDelete(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        provider.failDelete(false);
        for (int draft = 0; draft < 120; draft++) provider.personDraft("A person's draft " + draft);

        assertThat(writer.findPosted(TARGET, publication())).isEmpty();
        assertThat(provider.pendingDrafts()).isEqualTo(120);
    }

    @Test
    void shouldFindAPublicationThatLandsWhileReadbackDeletesTheDrafts() {
        provider.failDelete(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        provider.failDelete(false);
        provider.publishOnNextDraftRead();

        var found = writer.findPosted(TARGET, publication());

        assertThat(provider.bulkPublications()).isEqualTo(1);
        assertThat(found)
                .extracting(CopyReceipt::copyId)
                .containsExactlyInAnyOrderElementsOf(
                        publication().notes().stream().map(LineNote::copyId).toList());
        assertThat(found).allMatch(CopyReceipt::acknowledged);
    }

    @Test
    void shouldDeleteTheLeftoverDraftsBeforeProvingAbsence() {
        provider.failDelete(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        assertThat(writer.findPosted(TARGET, publication())).isNull();

        provider.failDelete(false);
        assertThat(writer.findPosted(TARGET, publication())).isEmpty();
        assertThat(provider.pendingDrafts()).isZero();
    }

    @Test
    void shouldRecoverAfterA429WithoutDuplicatingDraftsOrNotifications() {
        provider.failDraft(2);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        when(provider.provider().isRateLimitCritical(1L)).thenReturn(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(provider.pendingDrafts()).isZero();
        assertThat(provider.bulkPublications()).isZero();
        when(provider.provider().isRateLimitCritical(1L)).thenReturn(false);
        provider.failDraft(0);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(provider.bulkPublications()).isEqualTo(1);
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).hasSize(3);
    }

    @Test
    void shouldReadTheMergeRequestHeadFromGitLabNotFromThisServer() {
        provider.head(42, "c".repeat(40));
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.CURRENT);

        provider.head(42, "d".repeat(40));
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.MOVED);

        provider.head(42, null);
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.UNKNOWN);
    }

    @Test
    void shouldSeeAPushOnTheSourceBranchBeforeGitLabRefreshesTheMergeRequest() {
        provider.head(42, "c".repeat(40));
        provider.branchHead(42, "d".repeat(40));
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.MOVED);
    }

    @Test
    void shouldAcceptTheReviewedHeadOfAMergedRequestAfterSourceBranchDeletion() {
        provider.head(42, "c".repeat(40));
        provider.merge(42);
        provider.deleteBranch(42);

        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.CURRENT);
    }

    @Test
    void shouldReportTheMissingSourceBranchOfAnOpenRequest() {
        provider.head(42, "c".repeat(40));
        provider.deleteBranch(42);

        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.SOURCE_BRANCH_MISSING);
    }

    @Test
    void shouldAcceptTheReviewedHeadWhenMergeAndBranchDeletionHappenDuringTheCheck() {
        provider.head(42, "c".repeat(40));
        provider.mergeAndDeleteOnBranchRead(42);

        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.CURRENT);
    }

    @Test
    void shouldReportAMergedRequestWithADifferentDiffHeadAsMoved() {
        provider.head(42, "d".repeat(40));
        provider.merge(42);
        provider.deleteBranch(42);

        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.MOVED);
    }

    @Test
    void shouldKeepTheOpenRequestHeadUnknownWhenTheSourceRepositoryIsUnreadable() {
        provider.head(42, "c".repeat(40));
        provider.hideSourceRepository(42);

        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.UNKNOWN);
    }

    @Test
    void shouldNotReadTheHeadWhileTheRateLimitIsCritical() {
        provider.head(42, "c".repeat(40));
        when(provider.provider().isRateLimitCritical(1L)).thenReturn(true);
        assertThat(writer.liveHead(TARGET)).isEqualTo(LiveHead.UNKNOWN);
    }

    @Test
    void shouldNeverFallBackToPublicDiscussionsAfterARejectedDiffPosition() {
        provider.rejectPositions(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(provider.bulkPublications()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
    }

    /** The package of a later review on the same merge request. */
    private static OnWorkPackage otherPublication() {
        return new OnWorkPackage(
                new SummaryContent(
                        ScmFeedbackFixtures.copyId("other-summary"),
                        "A later review summary.",
                        "other-summary",
                        "<!-- hephaestus-feedback-copy:other-summary -->"),
                List.of(
                        note("other-line", new FeedbackAnchor("new.java", null, 21, CitationSide.NEW)),
                        note("other-file", new FeedbackAnchor("new.java", null, null, CitationSide.NEW))));
    }

    private static OnWorkPackage publication() {
        return new OnWorkPackage(
                new SummaryContent(ScmFeedbackFixtures.copyId("summary"), "Review summary.", "summary", MARKER),
                List.of(
                        note("line", new FeedbackAnchor("new.java", null, 21, CitationSide.NEW)),
                        note("file", new FeedbackAnchor("new.java", null, null, CitationSide.NEW))));
    }

    private static LineNote note(String key, FeedbackAnchor anchor) {
        return note(key, anchor, "Feedback for @everyone.");
    }

    private static LineNote note(String key, FeedbackAnchor anchor, String body) {
        return ScmFeedbackFixtures.line(anchor, body, "<!-- hephaestus-feedback-copy:" + key + " -->", key);
    }

    private static String delivered(String text, String key) {
        return text + "\n\n" + ScmFeedbackFormatter.DISCLOSURE + "\n"
                + "<sub>AI-generated feedback. Answer or disagree in [Hephaestus](https://hephaestus.example/w/team/feedback/"
                + TARGET.item().itemId() + ").</sub>\n"
                + "<sub>[Why you see this and how to stop it](https://hephaestus.example/settings#practice-feedback)</sub>\n\n"
                + "<!-- hephaestus-feedback-copy:" + key + " -->\n<!-- hephaestus-diff-note-ck=" + key + " -->";
    }
}
