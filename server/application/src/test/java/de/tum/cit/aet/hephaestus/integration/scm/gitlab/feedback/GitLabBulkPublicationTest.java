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
import de.tum.cit.aet.hephaestus.integration.core.spi.OnWorkPackage;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryContent;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFixtures;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabTokenService;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
                provider.rest(),
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
    void shouldRetainPartialDraftsAndAdoptThemOnRetryWithoutPostingAPartialReview() {
        provider.failDraft(2);
        var first = writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(first.receipts()).noneMatch(CopyReceipt::acknowledged);
        assertThat(provider.pendingDrafts()).isEqualTo(1);
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
        assertThat(provider.bulkPublications()).isZero();
        provider.failDraft(0);
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
    void shouldPublishNothingWhenTheFenceIsRevokedAfterCreatingPrivateDrafts() {
        writer.postPackage(TARGET, publication(), (ids, done) -> provider.pendingDrafts() < 2);
        assertThat(provider.pendingDrafts()).isEqualTo(2);
        assertThat(provider.bulkPublications()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
    }

    @Test
    void shouldRecoverAfterA429WithoutDuplicatingDraftsOrNotifications() {
        provider.failDraft(2);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        when(provider.provider().isRateLimitCritical(1L)).thenReturn(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(provider.pendingDrafts()).isEqualTo(1);
        assertThat(provider.bulkPublications()).isZero();
        when(provider.provider().isRateLimitCritical(1L)).thenReturn(false);
        provider.failDraft(0);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(provider.bulkPublications()).isEqualTo(1);
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).hasSize(3);
    }

    @Test
    void shouldNeverFallBackToPublicDiscussionsAfterARejectedDiffPosition() {
        provider.rejectPositions(true);
        writer.postPackage(TARGET, publication(), (ids, done) -> true);
        assertThat(provider.bulkPublications()).isZero();
        assertThat(provider.threads().on(ScriptedGitLabNotes.mergeRequest(42))).isEmpty();
    }

    private static OnWorkPackage publication() {
        return new OnWorkPackage(
                new SummaryContent(ScmFeedbackFixtures.copyId("summary"), "Review summary.", "summary", MARKER),
                List.of(
                        note("line", new FeedbackAnchor("new.java", null, 21, CitationSide.NEW)),
                        note("file", new FeedbackAnchor("new.java", null, null, CitationSide.NEW))));
    }

    private static LineNote note(String key, FeedbackAnchor anchor) {
        return ScmFeedbackFixtures.line(
                anchor, "Feedback for @everyone.", "<!-- hephaestus-feedback-copy:" + key + " -->", key);
    }
}
