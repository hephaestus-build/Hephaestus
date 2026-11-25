package de.tum.cit.aet.hephaestus.integration.core.itemevent;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationClockStep;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import de.tum.cit.aet.hephaestus.core.runtime.RuntimeRole;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnEvaluation
@RequiredArgsConstructor
class ItemEventEvaluationWork implements EvaluationWork, EvaluationClockStep {

    private final ItemEventRepository repository;
    private final PendingItemEventReaper reaper;
    private final Clock clock;
    private final ItemEventLedgerProperties properties;
    private final Environment environment;

    @Override
    public List<Item> items(List<Long> workspaceIds) {
        if (workspaceIds.isEmpty()) return List.of();
        Instant now = clock.instant();
        boolean reviewsEnabled = environment.getProperty(RuntimeRole.AGENT_ENABLED_PROPERTY, Boolean.class, false);
        return repository
                .findEvaluationWork(workspaceIds, properties.pendingLapseAfter().toSeconds(), reviewsEnabled)
                .stream()
                .map(row -> item(row, now))
                .toList();
    }

    @Override
    public void advance(Instant instant) {
        reaper.advance(instant);
    }

    private static Item item(Row row, Instant now) {
        var due = row.getFutureDueAt();
        return new Item("item_event", row.getId(), row.getState(), due != null && !due.isBefore(now) ? due : null);
    }
}
