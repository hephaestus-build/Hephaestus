package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextPaths;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.scm.context.IssueDetailSource;
import de.tum.cit.aet.hephaestus.integration.scm.context.MergeReadinessContentSource;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The context paths a Heph turn reads: the fixed documents, and the item paths that their owning sources parse and a
 * {@code read} of the context folder stages on demand.
 */
@Tag("unit")
class MentorContextKeysTest {

    @Test
    @DisplayName("a result detail key is one canonical lowercase id")
    void practiceResultDetailKeyIsCanonical() {
        String id = "0b7e1c9a-3f5d-4a8e-9c21-6d4f8e2a1b3c";
        assertThat(ResultHistoryContentSource.resultIdOf("inputs/context/results_history/" + id + ".json"))
                .contains(UUID.fromString(id));
        assertThat(ResultHistoryContentSource.resultIdOf(
                        "inputs/context/results_history/" + id.toUpperCase(Locale.ROOT) + ".json"))
                .isEmpty();
        assertThat(ResultHistoryContentSource.resultIdOf("inputs/context/results_history/../user.json"))
                .isEmpty();
        assertThat(ResultHistoryContentSource.resultIdOf("inputs/context/results_history/42.json"))
                .isEmpty();
        assertThat(ResultHistoryContentSource.resultIdOf(
                        "inputs/context/results_history/0b7e1c9a3f5d-4a8e-9c21-6d4f-8e2a1b3c.json"))
                .as("the id's dashes sit where a canonical id has them")
                .isEmpty();
        assertThat(ResultHistoryContentSource.resourceOf(UUID.fromString(id)))
                .isEqualTo("inputs/context/results_history/" + id + ".json");
    }

    @Test
    @DisplayName("a review-attempts key names one pull request or issue by its source id")
    void reviewAttemptsKeyIsCanonical() {
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/issue/21.json"))
                .contains(new ReviewAttemptsContentSource.ItemSelection(new ItemKind("scm.issue"), 21L));
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/pull_request/42.json"))
                .contains(new ReviewAttemptsContentSource.ItemSelection(new ItemKind("scm.pull_request"), 42L));
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/21.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/conversation/21.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/issue/../user.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.selectionOf("inputs/context/review_attempts/issue/0.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.selectionOf(
                        "inputs/context/review_attempts/issue/12345678901234567890.json"))
                .isEmpty();
        assertThat(ReviewAttemptsContentSource.resourceOf(new ItemKind("scm.pull_request"), 42L))
                .isEqualTo("inputs/context/review_attempts/pull_request/42.json");
    }

    @Test
    @DisplayName("an issue detail key names one issue by its source id")
    void issueDetailKeyIsCanonical() {
        assertThat(IssueDetailSource.issueIdOf("inputs/context/issue/88.json")).contains(88L);
        assertThat(IssueDetailSource.issueIdOf("inputs/context/issue/../user.json"))
                .isEmpty();
        assertThat(IssueDetailSource.issueIdOf("inputs/context/issue/12345678901234567890.json"))
                .isEmpty();
        assertThat(IssueDetailSource.resourceOf(88L)).isEqualTo("inputs/context/issue/88.json");
        assertThat(MentorContextPaths.issue(88L)).isEqualTo(IssueDetailSource.resourceOf(88L));
    }

    @Test
    @DisplayName("a merge readiness key names one authored pull request by its source id")
    void mergeReadinessKeyIsCanonical() {
        assertThat(MergeReadinessContentSource.pullRequestIdOf("inputs/context/merge_readiness/42.json"))
                .contains(42L);
        assertThat(MergeReadinessContentSource.pullRequestIdOf("inputs/context/merge_readiness/../user.json"))
                .isEmpty();
        assertThat(MentorContextPaths.mergeReadiness(42L)).isEqualTo(MergeReadinessContentSource.resourceOf(42L));
    }

    @Test
    @DisplayName("no item path is also a fixed document")
    void itemPathsAreNotFixedDocuments() {
        assertThat(MentorContextKeys.ALLOWED_OUTPUT_KEYS)
                .hasSize(14)
                .noneMatch(key ->
                        key.substring(ContentSource.OUTPUT_PREFIX.length()).contains("/"));
    }
}
