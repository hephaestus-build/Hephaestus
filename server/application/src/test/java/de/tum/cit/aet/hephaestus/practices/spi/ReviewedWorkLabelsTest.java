package de.tum.cit.aet.hephaestus.practices.spi;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactIdentity;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("unit")
class ReviewedWorkLabelsTest {

    static Stream<Arguments> workNamedByItsRun() {
        return Stream.of(
                Arguments.of(
                        "GitHub pull request",
                        new Target(
                                ArtifactKinds.PULL_REQUEST,
                                22L,
                                IntegrationKind.GITHUB,
                                22,
                                "Cache user lookups",
                                "acme/api",
                                null,
                                "https://github.com/acme/api/pull/22"),
                        new ReviewedWorkRefDTO(
                                "22",
                                ArtifactKinds.PULL_REQUEST,
                                IntegrationKind.GITHUB,
                                "#22",
                                "Cache user lookups",
                                "https://github.com/acme/api/pull/22",
                                "acme/api")),
                Arguments.of(
                        "GitLab merge request",
                        new Target(
                                ArtifactKinds.PULL_REQUEST,
                                425L,
                                IntegrationKind.GITLAB,
                                425,
                                "Retry uploads",
                                "acme/api",
                                null,
                                null),
                        new ReviewedWorkRefDTO(
                                "425",
                                ArtifactKinds.PULL_REQUEST,
                                IntegrationKind.GITLAB,
                                "!425",
                                "Retry uploads",
                                null,
                                "acme/api")),
                Arguments.of(
                        "GitLab issue",
                        new Target(ArtifactKinds.ISSUE, 13L, IntegrationKind.GITLAB, 13, "Bug", "acme/api", null, null),
                        new ReviewedWorkRefDTO(
                                "13", ArtifactKinds.ISSUE, IntegrationKind.GITLAB, "#13", "Bug", null, "acme/api")),
                Arguments.of(
                        "Slack conversation",
                        new Target(
                                ArtifactKinds.CONVERSATION_THREAD,
                                7L,
                                IntegrationKind.SLACK,
                                null,
                                "Conversation",
                                null,
                                "backend-review",
                                null),
                        new ReviewedWorkRefDTO(
                                "7",
                                ArtifactKinds.CONVERSATION_THREAD,
                                IntegrationKind.SLACK,
                                "#backend-review",
                                null,
                                null,
                                null)),
                Arguments.of(
                        "Outline document",
                        new Target(
                                ArtifactKinds.DOCUMENT,
                                3L,
                                IntegrationKind.OUTLINE,
                                null,
                                "Queue retry policy",
                                null,
                                "Engineering",
                                null),
                        // The title is the label, so it is not carried a second time.
                        new ReviewedWorkRefDTO(
                                "3",
                                ArtifactKinds.DOCUMENT,
                                IntegrationKind.OUTLINE,
                                "Queue retry policy",
                                null,
                                null,
                                null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("workNamedByItsRun")
    @DisplayName("a piece of work is named the way its provider names it")
    void shouldNameWorkTheWayItsProviderDoesWhenTheRunNamesIt(
            String provider, Target target, ReviewedWorkRefDTO expected) {
        assertThat(ReviewedWorkLabels.ref(target.type(), Objects.requireNonNull(target.id()), target))
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("work whose run is gone, or whose run names other work, is named by its kind and nothing invented")
    void shouldNameOnlyTheKindWhenNoRunNamesTheWork() {
        assertThat(ReviewedWorkLabels.ref(ArtifactKinds.PULL_REQUEST, 22L, null))
                .isEqualTo(new ReviewedWorkRefDTO(
                        "22", ArtifactKinds.PULL_REQUEST, null, "Pull or merge request", null, null, null));
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

    @Test
    @DisplayName("work that is not anchored to a kind and an id is not named at all")
    void shouldNameNothingWhenTheWorkIsNotAnchored() {
        assertThat(ReviewedWorkLabels.refOrNull(null, 22L, null)).isNull();
        assertThat(ReviewedWorkLabels.refOrNull(ArtifactKinds.PULL_REQUEST, null, null))
                .isNull();
    }

    @Test
    @DisplayName("work the mirror names is labelled the way its provider writes it")
    void shouldLabelMirroredWorkTheWayItsProviderWritesIt() {
        var mergeRequest = ReviewedWorkLabels.ref(new ArtifactIdentity(
                ArtifactKinds.PULL_REQUEST,
                7L,
                IntegrationKind.GITLAB,
                1423,
                "Retry uploads",
                "acme/api",
                "https://gitlab.example/acme/api/-/merge_requests/1423"));
        var pullRequest = ReviewedWorkLabels.ref(new ArtifactIdentity(
                ArtifactKinds.PULL_REQUEST, 8L, IntegrationKind.GITHUB, 1423, "Cache lookups", "acme/api", null));

        assertThat(mergeRequest)
                .isEqualTo(new ReviewedWorkRefDTO(
                        "7",
                        ArtifactKinds.PULL_REQUEST,
                        IntegrationKind.GITLAB,
                        "!1423",
                        "Retry uploads",
                        "https://gitlab.example/acme/api/-/merge_requests/1423",
                        "acme/api"));
        assertThat(pullRequest.label()).isEqualTo("#1423");
        assertThat(pullRequest.url())
                .as("deleted work keeps its name and loses its link")
                .isNull();
    }

    @Test
    @DisplayName("a document is labelled by its title and sits in its collection, by name")
    void shouldCarryADocumentsCollectionWhereItSits() {
        var document = ReviewedWorkLabels.ref(new ArtifactIdentity(
                ArtifactKinds.DOCUMENT, 5L, IntegrationKind.OUTLINE, null, "Deployment runbook", "Engineering", null));

        assertThat(document)
                .isEqualTo(new ReviewedWorkRefDTO(
                        "5",
                        ArtifactKinds.DOCUMENT,
                        IntegrationKind.OUTLINE,
                        "Deployment runbook",
                        null,
                        null,
                        "Engineering"));
    }

    @Test
    @DisplayName("a kind this build has no noun for is other work, never its identifier")
    void shouldCallAKindThisBuildDoesNotKnowOtherWork() {
        var ref = ReviewedWorkLabels.ref(ArtifactKind.of("tracker.ticket"), 4L, null);

        assertThat(ref.label()).isEqualTo("Other work");
    }

    @Test
    void shouldSayTheKindAloneForWorkNoResolverCouldName() {
        var ref = ReviewedWorkLabels.ref(ArtifactIdentity.unresolved(ArtifactKinds.ISSUE, 9L, "Issue"));

        assertThat(ref).isEqualTo(new ReviewedWorkRefDTO("9", ArtifactKinds.ISSUE, null, "Issue", null, null, null));
    }
}
