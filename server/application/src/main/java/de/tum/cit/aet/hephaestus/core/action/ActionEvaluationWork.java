package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnEvaluation
@RequiredArgsConstructor
class ActionEvaluationWork implements EvaluationWork {

    private final AgentActionRepository repository;

    @Override
    public List<Item> items(List<Long> workspaceIds) {
        // Instance actions still run on a server that has no workspace roots.
        var scope = workspaceIds.isEmpty() ? List.of(0L) : workspaceIds;
        return repository.findEvaluationWork(scope).stream()
                .map(row -> new Item("agent_action", row.getId(), row.getState(), row.getFutureDueAt()))
                .toList();
    }
}
