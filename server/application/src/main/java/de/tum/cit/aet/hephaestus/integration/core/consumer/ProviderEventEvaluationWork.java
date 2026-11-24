package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationObservations;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnEvaluation
class ProviderEventEvaluationWork implements EvaluationWork {

    private final ProviderEventRepository repository;

    ProviderEventEvaluationWork(
            ProviderEventRepository repository,
            ProviderEventNotifications notifications,
            EvaluationObservations observer) {
        this.repository = repository;
        notifications.subscribe(observer::changed);
    }

    @Override
    public List<Item> items(List<Long> workspaceIds) {
        // Installation events have no workspace. Use a nonmatching ID when there are no workspace roots,
        // so the bound workspace predicate stays valid and the inbox still reports installation events.
        var scope = workspaceIds.isEmpty() ? List.of(0L) : workspaceIds;
        return repository.findEvaluationWork(scope).stream()
                .map(row -> new Item("provider_event", row.getId(), row.getState(), null))
                .toList();
    }
}
