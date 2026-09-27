package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewedWorkRefDTO;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("unit")
class DeliveredWorkFeedbackServiceTest {
    private final ReviewedWorkRefDTO work = new ReviewedWorkRefDTO(
            "1",
            ArtifactKinds.PULL_REQUEST,
            IntegrationKind.GITHUB,
            "#5",
            "Pull request",
            "https://github.com/team/project/pull/5",
            "team/project");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://github.com/team/project/pull/5#issuecomment-123",
                "https://github.com/team/project/pull/5/files#r123",
                "https://github.com/team/project/pull/5#discussion_r123"
            })
    void shouldAcceptRecordedProviderLinksWhenTheyNameTheExactWork(String url) {
        assertThat(DeliveredWorkFeedbackService.verifiedLink(work, url)).contains(url);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "javascript:alert(1)",
                "http://github.com/team/project/pull/5#issuecomment-123",
                "https://evil.test/team/project/pull/5#issuecomment-123",
                "https://github.com/team/other/pull/5#issuecomment-123",
                "https://github.com/team/project/pull/6#issuecomment-123",
                "https://github.com/team/project/issues/5#issuecomment-123",
                "https://user:password@github.com/team/project/pull/5#issuecomment-123",
                "https://github.com/team/project/pull/5/../../6#issuecomment-123",
                "https://github.com/team/project/pull/5%2Ffiles#r123",
                "https://github.com/team/project/pull/5",
                "https://github.com/team/project/pull/5#"
            })
    void shouldOmitARecordedLinkWhenItCannotBeVerifiedAsACommentOnTheExactWork(String url) {
        assertThat(DeliveredWorkFeedbackService.verifiedLink(work, url)).isEmpty();
    }

    @Test
    void shouldOmitLinksWhenHistoricalPlacementsHaveNoUrl() {
        assertThat(DeliveredWorkFeedbackService.verifiedLink(work, null)).isEmpty();
    }
}
