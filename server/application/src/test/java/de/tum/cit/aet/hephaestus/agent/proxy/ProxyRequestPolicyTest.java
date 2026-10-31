package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.mentor.MentorTurnRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;

class ProxyRequestPolicyTest extends BaseUnitTest {
    private static final UUID LEASE = UUID.fromString("508d958c-100e-594f-8b4e-6d5c60e59470");

    @Mock
    private EgressPolicy egress;

    @Mock
    private ReviewJobRepository jobs;

    @Mock
    private MentorTurnRepository turns;

    @Mock
    private ReviewMemberAiPolicy reviews;

    @Mock
    private MemberAiRoutingAdapter routing;

    private ProxyRequestPolicy policy;
    private final UUID id = UUID.fromString("5f6696f2-1557-4ea1-b3d2-bfc338ae41bc");
    private final LlmModelResolver.ConnectionRef model =
            new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 7L, 8L, 1L);

    @BeforeEach
    void setUp() {
        policy = new ProxyRequestPolicy(egress, jobs, turns, reviews, routing);
    }

    private ProxyRouting request(UsageSourceType source) {
        return new ProxyRouting(
                "test",
                "openai-completions",
                "https://example.invalid",
                UsageFundingSource.INSTANCE,
                7L,
                8L,
                1L,
                new ProxyRouting.BilledAttempt(source, id, 0, BigDecimal.ZERO, LEASE),
                null,
                null,
                source == UsageSourceType.MENTOR_TURN ? null : Instant.now().plusSeconds(60));
    }

    @Test
    void shouldRefuseASourceThatDoesNotBelongToTheAuthenticatedWorkspace() {
        assertThat(policy.allows(request(UsageSourceType.REVIEW_JOB))).isFalse();
        verify(jobs).findByIdAndWorkspaceId(id, 1L);
        verifyNoInteractions(reviews, routing);
    }

    @Test
    void shouldRecheckTheCurrentChoiceOnEveryModelRequest() {
        var job = new ReviewJob();
        when(jobs.findByIdAndWorkspaceId(id, 1L)).thenReturn(Optional.of(job));
        when(reviews.allows(job, model)).thenReturn(true, false);
        assertThat(policy.allows(request(UsageSourceType.REVIEW_JOB))).isTrue();
        assertThat(policy.allows(request(UsageSourceType.REVIEW_JOB))).isFalse();
    }

    @ParameterizedTest
    @EnumSource(
            value = UsageSourceType.class,
            names = {"PRACTICE_SCRIPT_DECISION", "PRACTICE_SCRIPT_EMBEDDING", "PRACTICE_SCRIPT_RERANKING"})
    void shouldCheckAPracticeScriptModelAgainstTheReviewedDeveloperWhenItsSlotIsCalled(UsageSourceType source) {
        var job = new ReviewJob();
        when(jobs.findByIdAndWorkspaceId(id, 1L)).thenReturn(Optional.of(job));
        when(reviews.allows(job, model)).thenReturn(true);

        assertThat(policy.allows(request(source))).isTrue();
    }

    @Test
    void shouldUseTheDurableMentorTurnAccountNotASuppliedUserId() {
        when(turns.findAccountIdByIdAndWorkspaceId(id, 1L)).thenReturn(Optional.of(20L));
        when(routing.allows(1L, 20L, model)).thenReturn(false);
        assertThat(policy.allows(request(UsageSourceType.MENTOR_TURN))).isFalse();
        verify(routing).allows(1L, 20L, model);
        verifyNoInteractions(jobs);
    }

    @Test
    void shouldRefuseARequestWhenItCarriesNoWorkspace() {
        var request = new ProxyRouting(
                "test",
                "openai-completions",
                "https://example.invalid",
                UsageFundingSource.INSTANCE,
                7L,
                8L,
                null,
                new ProxyRouting.BilledAttempt(UsageSourceType.MENTOR_TURN, id, 0, BigDecimal.ZERO, LEASE),
                null,
                null,
                null);
        assertThat(policy.allows(request)).isFalse();
        verifyNoInteractions(jobs, turns, routing, reviews);
    }
}
