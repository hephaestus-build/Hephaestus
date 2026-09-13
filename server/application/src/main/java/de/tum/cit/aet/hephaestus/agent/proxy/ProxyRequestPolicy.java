package de.tum.cit.aet.hephaestus.agent.proxy;

import de.tum.cit.aet.hephaestus.agent.catalog.EgressPolicy;
import de.tum.cit.aet.hephaestus.agent.catalog.LlmModelResolver;
import de.tum.cit.aet.hephaestus.agent.config.MemberAiRoutingAdapter;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Recheck the live choice before each proxy request; queued snapshots are not permission. */
@Service
@RequiredArgsConstructor
class ProxyRequestPolicy {
    private final EgressPolicy egress;
    private final AgentJobRepository jobs;
    private final ChatMessageRepository messages;
    private final ReviewMemberAiPolicy reviews;
    private final MemberAiRoutingAdapter routing;

    void validateTarget(String baseUrl) {
        egress.validate(baseUrl);
    }

    @Transactional(readOnly = true)
    public boolean allows(ProxyRouting request) {
        Long workspaceId = request.workspaceId();
        var attempt = request.attempt();
        if (workspaceId == null || attempt == null) return false;
        var model = new LlmModelResolver.ConnectionRef(
                request.connectionScope(), request.connectionId(), request.modelId(), workspaceId);
        return switch (attempt.sourceType()) {
            case AGENT_JOB ->
                jobs.findByIdAndWorkspaceId(attempt.sourceId(), workspaceId)
                        .filter(job -> reviews.allows(job, model))
                        .isPresent();
            case MENTOR_TURN ->
                messages.findDeveloperIdByIdAndWorkspaceId(attempt.sourceId(), workspaceId)
                        .filter(developer -> routing.allows(workspaceId, developer, model))
                        .isPresent();
        };
    }
}
