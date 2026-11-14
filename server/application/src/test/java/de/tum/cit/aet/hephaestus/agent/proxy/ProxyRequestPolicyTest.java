package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJob;
import de.tum.cit.aet.hephaestus.agent.job.ReviewJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.mentor.weeklydigest.DigestAdmission;
import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.UsageSourceType;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorTurnAccess;
import de.tum.cit.aet.hephaestus.productfeedback.drafting.ProductFeedbackDraftCalls;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
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
    private MentorTurnAccess turns;

    @Mock
    private ReviewMemberAiPolicy reviews;

    @Mock
    private MemberAiRoutingAdapter routing;

    @Mock
    private ProductFeedbackDraftCalls drafts;

    @Mock
    private DigestAdmission digests;

    private ProxyRequestPolicy policy;
    private final UUID id = UUID.fromString("5f6696f2-1557-4ea1-b3d2-bfc338ae41bc");
    private final LlmModelResolver.ConnectionRef model =
            new LlmModelResolver.ConnectionRef(UsageFundingSource.INSTANCE, 7L, 8L, 1L);

    @BeforeEach
    void setUp() {
        policy = new ProxyRequestPolicy(
                drafts, mock(LlmModelResolver.class), egress, digests, jobs, turns, reviews, routing);
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
                new ProxyRouting.BilledAttempt(source, id, 0, LEASE),
                source == UsageSourceType.MENTOR_TURN ? null : Instant.now().plusSeconds(60),
                null);
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
    void shouldRefuseHistoricalScriptSourcesWhenTheyAreUsedForANewCall(UsageSourceType source) {
        assertThat(policy.allows(request(source))).isFalse();
        verifyNoInteractions(jobs, turns, reviews, routing);
    }

    @Test
    void shouldUseTheDurableMentorTurnAccountNotASuppliedUserId() {
        when(turns.findAccountId(1L, id)).thenReturn(Optional.of(20L));
        when(routing.allows(1L, 20L, model)).thenReturn(false);
        assertThat(policy.allows(request(UsageSourceType.MENTOR_TURN))).isFalse();
        verify(routing).allows(1L, 20L, model);
        verifyNoInteractions(jobs);
    }

    @Test
    void shouldPermitOnlyTheCurrentDisclosedInstanceDraftGeneration() {
        var request = new ProxyRouting(
                "draft",
                "openai-completions",
                "https://model.example",
                UsageFundingSource.INSTANCE,
                7L,
                8L,
                null,
                new ProxyRouting.BilledAttempt(UsageSourceType.PRODUCT_FEEDBACK_DRAFT, id, 3, null),
                Instant.now().plusSeconds(30),
                100L);
        when(drafts.waiting(id, 3))
                .thenReturn(Optional.of(new ProductFeedbackDraftCalls.DraftCall(8L, "system", "report", 100)));
        assertThat(policy.allows(request)).isTrue();
        when(drafts.waiting(id, 3))
                .thenReturn(Optional.of(new ProductFeedbackDraftCalls.DraftCall(9L, "system", "report", 100)));
        assertThat(policy.allows(request)).isFalse();
        when(drafts.waiting(id, 3)).thenReturn(Optional.empty());
        assertThat(policy.allows(request)).isFalse();
        verifyNoInteractions(jobs, turns, routing, reviews);
    }

    @Test
    void shouldRefuseTheNextConversationCallWhenItsCapturedDigestFactsLoseEligibility() {
        when(turns.findAccountId(1L, id)).thenReturn(Optional.of(21L));
        when(routing.allows(1L, 21L, model)).thenReturn(true);
        when(digests.refuses(1L, id)).thenReturn(false, true);
        var request = request(UsageSourceType.MENTOR_TURN);
        assertThat(policy.allows(request)).isTrue();
        assertThat(policy.allows(request)).isFalse();
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
                new ProxyRouting.BilledAttempt(UsageSourceType.MENTOR_TURN, id, 0, LEASE),
                null,
                null);
        assertThat(policy.allows(request)).isFalse();
        verifyNoInteractions(jobs, turns, routing, reviews);
    }
}
