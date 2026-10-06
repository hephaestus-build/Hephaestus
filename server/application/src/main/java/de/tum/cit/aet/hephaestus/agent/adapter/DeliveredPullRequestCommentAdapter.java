package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredPullRequestCommentLookup;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DeliveredPullRequestCommentAdapter implements DeliveredPullRequestCommentLookup {
    private final AgentJobRepository jobs;

    public DeliveredPullRequestCommentAdapter(AgentJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public CommentIds findForPullRequest(long workspaceId, long pullRequestId) {
        Set<Long> general = new HashSet<>();
        Set<Long> inline = new HashSet<>();
        for (var row : jobs.findDeliveredPullRequestComments(workspaceId, pullRequestId)) {
            (row.getInline() ? inline : general).add(row.getNativeId());
        }
        return new CommentIds(Set.copyOf(general), Set.copyOf(inline));
    }
}
