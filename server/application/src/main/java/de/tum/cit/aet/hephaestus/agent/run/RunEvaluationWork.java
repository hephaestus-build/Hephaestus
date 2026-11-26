package de.tum.cit.aet.hephaestus.agent.run;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationObservations;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnEvaluation
class RunEvaluationWork implements EvaluationWork {

    private final AgentRuns runs;
    private final AgentRunRepository repository;
    private final AgentRunNotifications notifications;
    private final EvaluationObservations observer;
    private final AgentRunTiming timing;
    private final AtomicBoolean subscribed = new AtomicBoolean();

    RunEvaluationWork(
            AgentRuns runs,
            AgentRunRepository repository,
            AgentRunNotifications notifications,
            EvaluationObservations observer,
            AgentRunTiming timing) {
        this.runs = runs;
        this.repository = repository;
        this.notifications = notifications;
        this.observer = observer;
        this.timing = timing;
    }

    @Override
    public List<Item> items(List<Long> workspaceIds) {
        if (workspaceIds.isEmpty()) return List.of();
        if (subscribed.compareAndSet(false, true)) notifications.subscribe(kind -> observer.changed());
        var items = new ArrayList<Item>();
        var claimable = new HashSet<String>();
        for (var due : runs.due(EnumSet.allOf(AgentRunKind.class), 0, Integer.MAX_VALUE)) {
            String id = due.run().id().toString();
            claimable.add(id);
            items.add(new Item("agent_run", id, "CLAIMABLE", null));
        }
        for (Row row : repository.findEvaluationWork(workspaceIds, timing.read(false))) {
            if (claimable.contains(row.getId())) continue;
            var due = row.getFutureDueAt();
            String state = "QUEUED".equals(row.getState()) && due == null ? "QUEUED_NOT_CLAIMABLE" : row.getState();
            items.add(new Item("agent_run", row.getId(), state, due));
        }
        return items;
    }
}
