package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.DiffNote;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.config.ApplicationProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackAnchor;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineFeedback;
import de.tum.cit.aet.hephaestus.integration.core.spi.InlineFeedbackChannel.InlineResult;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel.FeedbackTarget;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DiffNotePosterTest extends BaseUnitTest {

    private static List<InlineFeedback> posted(RecordingChannel channel) {
        assertThat(channel.posted).isNotNull();
        return channel.posted;
    }

    private final PullRequestCommentPoster commentPoster = mock(PullRequestCommentPoster.class);
    private final PracticeFeedbackCommentFormatter commentFormatter = new PracticeFeedbackCommentFormatter(
            new ApplicationProperties(null, new ApplicationProperties.Webapp("https://hephaestus.example")));

    private AgentJob gitlabJob() {
        AgentJob job = TestEntities.agentJob();
        Workspace ws = new Workspace();
        ws.setId(1L);
        job.setWorkspace(ws);
        job.setIntegrationKind(IntegrationKind.GITLAB);
        return job;
    }

    private FeedbackTarget target() {
        return new FeedbackTarget(new IntegrationRef(IntegrationKind.GITLAB, 1L, null), "group/project!42", null);
    }

    private static final class RecordingChannel implements InlineFeedbackChannel {

        @Nullable
        List<InlineFeedback> posted;

        boolean cleared;

        boolean immutable;

        @Nullable
        RuntimeException clearThrows;

        @Override
        public IntegrationKind kind() {
            return IntegrationKind.GITLAB;
        }

        @Override
        public InlineResult postInlineFeedback(FeedbackTarget target, List<InlineFeedback> observations) {
            this.posted = observations;
            return new InlineResult(observations.size(), 0, List.of());
        }

        @Override
        public InlineResult postImmutablePackage(FeedbackTarget target, List<InlineFeedback> observations) {
            this.immutable = true;
            return postInlineFeedback(target, observations);
        }

        @Override
        public void clearStaleFeedback(FeedbackTarget target, String marker) {
            this.cleared = true;
            if (clearThrows != null) {
                throw clearThrows;
            }
        }
    }

    private DiffNotePoster poster(RecordingChannel channel) {
        when(commentPoster.buildTarget(any(), eq(IntegrationKind.GITLAB), eq(1L)))
                .thenReturn(target());
        return new DiffNotePoster(commentPoster, commentFormatter, List.of(channel));
    }

    @Test
    void multiLineNote_swapsToEndLineAnchorWithRangeStart_andCarriesRecurrenceKey() {
        RecordingChannel channel = new RecordingChannel();
        DiffNotePoster poster = poster(channel);
        DiffNote multi = new DiffNote("src/A.java", 10, 14, "Fix this range", "ck-multi");

        poster.reconcileInlineNotes(gitlabJob(), List.of(multi));

        assertThat(channel.posted).hasSize(1);
        InlineFeedback f = channel.posted.get(0);
        FeedbackAnchor.DiffAnchor anchor = (FeedbackAnchor.DiffAnchor) f.anchor();
        assertThat(anchor.filePath()).isEqualTo("src/A.java");
        assertThat(anchor.newLineNumber()).isEqualTo(14);
        assertThat(f.body())
                .contains("<sub>AI-generated &middot; React with 👍 or 👎, or reply, to give feedback.</sub>")
                .doesNotContain("Why you're seeing this");
        assertThat(anchor.startLine()).isEqualTo(10);
        assertThat(f.deliveryKey()).isEqualTo("ck-multi");
    }

    @Test
    void singleLineNote_hasNoRangeStart() {
        RecordingChannel channel = new RecordingChannel();
        DiffNotePoster poster = poster(channel);
        DiffNote single = new DiffNote("src/A.java", 10, null, "Fix this line", "ck-single");

        poster.reconcileInlineNotes(gitlabJob(), List.of(single));

        FeedbackAnchor.DiffAnchor anchor =
                (FeedbackAnchor.DiffAnchor) posted(channel).get(0).anchor();
        assertThat(anchor.newLineNumber()).isEqualTo(10);
        assertThat(anchor.startLine()).isNull();
    }

    @Test
    void approvedPackageUsesItsOwnImmutableProviderIdentity() {
        RecordingChannel channel = new RecordingChannel();
        UUID feedbackId = UUID.randomUUID();

        poster(channel)
                .reconcileApprovedInlineNotes(
                        gitlabJob(),
                        feedbackId,
                        List.of(new DiffNote("src/A.java", 10, null, "Exact body", "cross-review-key")));

        InlineFeedback delivered = posted(channel).get(0);
        assertThat(channel.immutable).isTrue();
        assertThat(delivered.body()).isEqualTo("Exact body");
        assertThat(delivered.marker()).isEqualTo("<!-- hephaestus-approved-package:" + feedbackId + " -->");
        assertThat(delivered.deliveryKey()).isEqualTo("approved:" + feedbackId + ":0");
    }

    @Test
    void blankBodyNote_isSkipped_andClearsStaleWhenAllBlank() {
        RecordingChannel channel = new RecordingChannel();
        DiffNotePoster poster = poster(channel);
        DiffNote blank = new DiffNote("src/A.java", 10, null, "   ", "ck-blank");

        DiffNotePoster.DiffNoteResult result = poster.reconcileInlineNotes(gitlabJob(), List.of(blank));

        assertThat(result.posted()).isZero();
        assertThat(channel.posted).isNull();
        assertThat(channel.cleared).isTrue();
    }

    @Test
    void shouldFailThePackageWhenEmptyReconciliationCannotClearStaleFeedback() {
        RecordingChannel channel = new RecordingChannel();
        channel.clearThrows = new RuntimeException("gitlab down");
        DiffNotePoster poster = poster(channel);

        assertThatThrownBy(() -> poster.reconcileInlineNotes(gitlabJob(), List.of()))
                .isInstanceOf(JobDeliveryException.class);
        assertThat(channel.cleared).isTrue();
    }

    @Test
    void duplicateChannelKind_inConstructor_throws() {
        InlineFeedbackChannel a = mock(InlineFeedbackChannel.class);
        InlineFeedbackChannel b = mock(InlineFeedbackChannel.class);
        when(a.kind()).thenReturn(IntegrationKind.GITLAB);
        when(b.kind()).thenReturn(IntegrationKind.GITLAB);

        assertThatThrownBy(() -> new DiffNotePoster(commentPoster, commentFormatter, List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate InlineFeedbackChannel for kind");
    }

    @Test
    void repoRelativeAnchorPath_flowsThroughUnchanged() {
        RecordingChannel channel = new RecordingChannel();
        DiffNotePoster poster = poster(channel);
        DiffNote note = new DiffNote("src/components/Button.tsx", 1, null, "Remove unused import", "ck-1");

        poster.reconcileInlineNotes(gitlabJob(), List.of(note));

        FeedbackAnchor.DiffAnchor anchor =
                (FeedbackAnchor.DiffAnchor) posted(channel).get(0).anchor();
        assertThat(anchor.filePath()).isEqualTo("src/components/Button.tsx");
    }

    @Test
    void postedFindings_reportCounts() {
        RecordingChannel channel = new RecordingChannel();
        DiffNotePoster poster = poster(channel);
        var captor = ArgumentCaptor.forClass(IntegrationKind.class);

        DiffNotePoster.DiffNoteResult result = poster.reconcileInlineNotes(
                gitlabJob(), List.of(new DiffNote("src/A.java", 10, null, "real body", "ck-1")));

        assertThat(result.posted()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(commentPoster).buildTarget(any(), captor.capture(), eq(1L));
        assertThat(captor.getValue()).isEqualTo(IntegrationKind.GITLAB);
    }
}
