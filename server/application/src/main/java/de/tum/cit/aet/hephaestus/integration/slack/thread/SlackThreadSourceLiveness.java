package de.tum.cit.aet.hephaestus.integration.slack.thread;

import de.tum.cit.aet.hephaestus.integration.core.spi.ThreadSourceLiveness;
import de.tum.cit.aet.hephaestus.integration.slack.domain.SlackThreadRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class SlackThreadSourceLiveness implements ThreadSourceLiveness {

    private final SlackThreadRepository threadRepository;

    public SlackThreadSourceLiveness(SlackThreadRepository threadRepository) {
        this.threadRepository = threadRepository;
    }

    @Override
    public Set<Long> activeThreadIds(long workspaceId, Collection<Long> threadIds) {
        if (threadIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(threadRepository.findActiveThreadIds(workspaceId, threadIds));
    }
}
