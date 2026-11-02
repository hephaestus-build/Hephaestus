package de.tum.cit.aet.hephaestus.integration.scm.gitlab.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.scm.context.WorkspaceScmProjection;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackIds;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Comments already posted on GitHub and GitLab keep these markers forever, and readback and capture read them. A new
 * format takes a new name beside the old one. docs/admin/compatibility-policy.mdx promises them.
 */
class PostedMarkersTest extends BaseUnitTest {

    @Test
    void theMarkersKeepTheirExactText() {
        UUID copy = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(FeedbackIds.writeMarker(copy))
                .isEqualTo("<!-- hephaestus-feedback-copy:00000000-0000-0000-0000-000000000001 -->");
        assertThat(ScmFeedbackFormatter.deliveryTag("key:1_a-b"))
                .isEqualTo("<!-- hephaestus-diff-note-ck=key:1_a-b -->");
        assertThat(GitLabLineNoteWriter.RANGE_PLACEMENT).isEqualTo("<!-- hephaestus-gitlab-placement=line-range -->");
    }

    @Test
    void capturedCommentsStayRecognizedByThePrefixOfEveryMarker() {
        assertThat(WorkspaceScmProjection.HEPHAESTUS_MARKER).isEqualTo("<!-- hephaestus");
        assertThat(FeedbackIds.writeMarker(UUID.randomUUID())).startsWith(WorkspaceScmProjection.HEPHAESTUS_MARKER);
        assertThat(ScmFeedbackFormatter.deliveryTag("key")).startsWith(WorkspaceScmProjection.HEPHAESTUS_MARKER);
        assertThat(GitLabLineNoteWriter.RANGE_PLACEMENT).startsWith(WorkspaceScmProjection.HEPHAESTUS_MARKER);
    }

    @Test
    void anOlderPostedDeliveryTagStillReadsBack() {
        String posted = "A note.\n\n<!-- hephaestus-feedback-copy:00000000-0000-0000-0000-000000000001 -->\n"
                + "<!-- hephaestus-diff-note-ck=first -->\n<!-- hephaestus-diff-note-ck=last:1_a-b -->";

        assertThat(ScmFeedbackFormatter.lastDeliveryKey(posted)).isEqualTo("last:1_a-b");
    }
}
