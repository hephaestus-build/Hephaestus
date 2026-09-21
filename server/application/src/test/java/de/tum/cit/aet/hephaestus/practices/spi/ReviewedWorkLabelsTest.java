package de.tum.cit.aet.hephaestus.practices.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunTargetLookup.Target;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ReviewedWorkLabelsTest {

    @Test
    @DisplayName("a piece of work is named the way its provider names it")
    void labelsWorkLikeItsProvider() {
        assertThat(ReviewedWorkLabels.ref(
                        ArtifactKinds.PULL_REQUEST,
                        22L,
                        new Target(
                                ArtifactKinds.PULL_REQUEST,
                                22L,
                                IntegrationKind.GITHUB,
                                22,
                                "Cache user lookups",
                                "acme/api",
                                null,
                                "https://github.com/acme/api/pull/22")))
                .isEqualTo(new ReviewedWorkRefDTO(
                        "22",
                        "scm.pull_request",
                        IntegrationKind.GITHUB,
                        "#22",
                        "Cache user lookups",
                        "https://github.com/acme/api/pull/22",
                        "acme/api"));
        assertThat(ReviewedWorkLabels.ref(
                        ArtifactKinds.PULL_REQUEST,
                        425L,
                        new Target(
                                ArtifactKinds.PULL_REQUEST,
                                425L,
                                IntegrationKind.GITLAB,
                                425,
                                "Retry uploads",
                                "acme/api",
                                null,
                                null)))
                .isEqualTo(new ReviewedWorkRefDTO(
                        "425", "scm.pull_request", IntegrationKind.GITLAB, "!425", "Retry uploads", null, "acme/api"));
        assertThat(ReviewedWorkLabels.ref(
                                ArtifactKinds.ISSUE,
                                13L,
                                new Target(
                                        ArtifactKinds.ISSUE,
                                        13L,
                                        IntegrationKind.GITLAB,
                                        13,
                                        "Bug",
                                        "acme/api",
                                        null,
                                        null))
                        .label())
                .isEqualTo("#13");
        assertThat(ReviewedWorkLabels.ref(
                        ArtifactKinds.CONVERSATION_THREAD,
                        7L,
                        new Target(
                                ArtifactKinds.CONVERSATION_THREAD,
                                7L,
                                IntegrationKind.SLACK,
                                null,
                                "Conversation",
                                null,
                                "backend-review",
                                null)))
                .isEqualTo(new ReviewedWorkRefDTO(
                        "7", "chat.conversation_thread", IntegrationKind.SLACK, "#backend-review", null, null, null));
        assertThat(ReviewedWorkLabels.ref(
                        ArtifactKinds.DOCUMENT,
                        3L,
                        new Target(
                                ArtifactKinds.DOCUMENT,
                                3L,
                                IntegrationKind.OUTLINE,
                                null,
                                "Queue retry policy",
                                null,
                                "Engineering",
                                null)))
                .isEqualTo(new ReviewedWorkRefDTO(
                        "3",
                        "docs.document",
                        IntegrationKind.OUTLINE,
                        "Queue retry policy",
                        "Queue retry policy",
                        null,
                        null));
    }

    @Test
    @DisplayName("work whose run is gone, or whose run names other work, is named by its kind and nothing invented")
    void fallsBackToTheKindWithoutAMatchingRun() {
        assertThat(ReviewedWorkLabels.ref(ArtifactKinds.PULL_REQUEST, 22L, null))
                .isEqualTo(new ReviewedWorkRefDTO("22", "scm.pull_request", null, "Pull request", null, null, null));
        assertThat(ReviewedWorkLabels.ref(
                                ArtifactKinds.ISSUE,
                                13L,
                                new Target(
                                        ArtifactKinds.ISSUE,
                                        14L,
                                        IntegrationKind.GITHUB,
                                        14,
                                        "Other",
                                        null,
                                        null,
                                        null))
                        .label())
                .isEqualTo("Issue");
        assertThat(ReviewedWorkLabels.ref(ArtifactKinds.CONVERSATION_THREAD, 7L, null)
                        .label())
                .isEqualTo("Conversation");
        // A run that knows the provider but not the number still says the provider's noun.
        assertThat(ReviewedWorkLabels.ref(
                                ArtifactKinds.PULL_REQUEST,
                                425L,
                                new Target(
                                        ArtifactKinds.PULL_REQUEST,
                                        425L,
                                        IntegrationKind.GITLAB,
                                        null,
                                        "Retry uploads",
                                        "acme/api",
                                        null,
                                        null))
                        .label())
                .isEqualTo("Merge request");
        assertThat(ReviewedWorkLabels.ref(ArtifactKinds.DOCUMENT, 3L, null).label())
                .isEqualTo("Document");
    }
}
