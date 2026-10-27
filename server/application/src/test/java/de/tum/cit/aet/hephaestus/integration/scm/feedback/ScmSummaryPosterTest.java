package de.tum.cit.aet.hephaestus.integration.scm.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ScmSummaryPosterTest extends BaseUnitTest {
    @Nested
    class SpeaksApproval {

        @Test
        void shouldDetectApprovalWhenALineIsOnlyApproval() {
            assertThat(ScmFeedbackFormatter.speaksApproval("LGTM")).isTrue();
            assertThat(ScmFeedbackFormatter.speaksApproval("Approved.")).isTrue();
            assertThat(ScmFeedbackFormatter.speaksApproval("ready to merge")).isTrue();
            assertThat(ScmFeedbackFormatter.speaksApproval("Add a test for the empty input.\n\nLGTM"))
                    .isTrue();
        }

        @Test
        void shouldNotDetectApprovalWhenThePhraseIsPartOfASentence() {
            assertThat(ScmFeedbackFormatter.speaksApproval("Before this is ready to merge, add a test."))
                    .isFalse();
        }
    }
}
