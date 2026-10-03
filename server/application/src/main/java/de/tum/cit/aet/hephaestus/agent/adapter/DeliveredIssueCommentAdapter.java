package de.tum.cit.aet.hephaestus.agent.adapter;

import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.DeliveredIssueCommentLookup;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DeliveredIssueCommentAdapter implements DeliveredIssueCommentLookup {
    private final AgentJobRepository jobs;

    public DeliveredIssueCommentAdapter(AgentJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public List<DeliveredComment> findForIssue(long issueId) {
        return jobs.findDeliveredIssueComments(issueId).stream()
                .map(row -> new DeliveredComment(row.getExternalRef(), row.getUrl()))
                .toList();
    }
}
