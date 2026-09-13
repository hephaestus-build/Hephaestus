package de.tum.cit.aet.hephaestus.agent.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.agent.usage.LlmUsageSourceType;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

class ProxyRequestPolicyTest extends BaseUnitTest {
    @Mock
    private EgressPolicy egress;

    @Mock
    private AgentJobRepository jobs;

    @Mock
    private ChatMessageRepository messages;

    @Mock
    private ReviewMemberAiPolicy reviews;

    @Mock
    private MemberAiRoutingAdapter routing;

    private ProxyRequestPolicy policy;
    private final UUID id = UUID.fromString("5f6696f2-1557-4ea1-b3d2-bfc338ae41bc");
    private final LlmModelResolver.ConnectionRef model =
            new LlmModelResolver.ConnectionRef(FundingSource.INSTANCE, 7L, 8L, 1L);

    @BeforeEach
    void setUp() {
        policy = new ProxyRequestPolicy(egress, jobs, messages, reviews, routing);
    }

    private ProxyRouting request(LlmUsageSourceType source) {
        return new ProxyRouting(
                "test",
                "openai-completions",
                "https://example.invalid",
                FundingSource.INSTANCE,
                7L,
                8L,
                1L,
                new ProxyRouting.BilledAttempt(source, id, 0, BigDecimal.ZERO));
    }

    @Test
    void shouldRefuseASourceThatDoesNotBelongToTheAuthenticatedWorkspace() {
        assertThat(policy.allows(request(LlmUsageSourceType.AGENT_JOB))).isFalse();
        verify(jobs).findByIdAndWorkspaceId(id, 1L);
        verifyNoInteractions(reviews, routing);
    }

    @Test
    void shouldRecheckTheCurrentChoiceOnEveryModelRequest() {
        var job = new AgentJob();
        when(jobs.findByIdAndWorkspaceId(id, 1L)).thenReturn(Optional.of(job));
        when(reviews.allows(job, model)).thenReturn(true, false);
        assertThat(policy.allows(request(LlmUsageSourceType.AGENT_JOB))).isTrue();
        assertThat(policy.allows(request(LlmUsageSourceType.AGENT_JOB))).isFalse();
    }

    @Test
    void shouldUseTheDurableMentorTurnDeveloperNotASuppliedUserId() {
        when(messages.findDeveloperIdByIdAndWorkspaceId(id, 1L)).thenReturn(Optional.of(20L));
        when(routing.allows(1L, 20L, model)).thenReturn(false);
        assertThat(policy.allows(request(LlmUsageSourceType.MENTOR_TURN))).isFalse();
        verify(routing).allows(1L, 20L, model);
        verifyNoInteractions(jobs);
    }

    @Test
    void shouldRequireABillableExecution() {
        var request = new ProxyRouting(
                "test", "openai-completions", "https://example.invalid", FundingSource.INSTANCE, 7L, 8L, 1L, null);
        assertThat(policy.allows(request)).isFalse();
        verifyNoInteractions(jobs, messages, routing, reviews);
    }
}
