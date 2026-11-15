package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.handler.RuntimeReviewTestData;
import de.tum.cit.aet.hephaestus.core.auth.domain.AiChoice;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonProcessingSuppression;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ReviewMemberAiPolicyTest extends BaseUnitTest {
    @Test
    void shouldUseTheFrozenDeveloperInsteadOfTheRequester() {
        var assignments = mock(ReviewJobAssignments.class);
        var preferences = mock(MemberAiPreferences.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var job = RuntimeReviewTestData.job();
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(5L, null)));
        when(preferences.forDeveloper(1, 5L))
                .thenReturn(new MemberAiPreferences.Decision(true, AiChoice.IN_HOUSE_ONLY));
        var policy = new ReviewMemberAiPolicy(
                mock(MemberAiRoutingAdapter.class), new JsonMapper(), preferences, suppression, assignments);
        assertThat(policy.permitsReview(job)).isTrue();
        when(suppression.isAccountSuppressed(5)).thenReturn(true);
        assertThat(policy.permitsReview(job)).isFalse();
    }

    @Test
    void shouldApplySourceSuppressionEvenWhenNobodyIsTheDeveloper() {
        var assignments = mock(ReviewJobAssignments.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var job = RuntimeReviewTestData.job();
        when(assignments.load(job)).thenReturn(List.of(RuntimeReviewTestData.assignment(null, null)));
        when(suppression.isSourceSuppressed(1, "docs.document", 20)).thenReturn(true);
        var policy = new ReviewMemberAiPolicy(
                mock(MemberAiRoutingAdapter.class),
                new JsonMapper(),
                mock(MemberAiPreferences.class),
                suppression,
                assignments);
        assertThat(policy.permitsReview(job)).isFalse();
    }

    @Test
    void shouldSuppressAJobUnderErasureBeforeItReadsItsAssignments() {
        var assignments = mock(ReviewJobAssignments.class);
        var suppression = mock(PersonProcessingSuppression.class);
        var job = RuntimeReviewTestData.job();
        when(suppression.isReviewJobSuppressed(job.getId())).thenReturn(true);
        var policy = new ReviewMemberAiPolicy(
                mock(MemberAiRoutingAdapter.class),
                new JsonMapper(),
                mock(MemberAiPreferences.class),
                suppression,
                assignments);

        assertThat(policy.isProcessingSuppressed(job)).isTrue();
        assertThat(policy.permitsReview(job)).isFalse();
        assertThat(policy.allows(RuntimeJobTestData.call(job), LlmModelResolver.ConnectionRef.NONE))
                .isFalse();
        // Erasure can remove the job's review before the job itself, and then its assignments cannot be read.
        verifyNoInteractions(assignments);
    }
}
