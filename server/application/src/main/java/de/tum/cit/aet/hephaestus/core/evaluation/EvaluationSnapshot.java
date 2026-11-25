package de.tum.cit.aet.hephaestus.core.evaluation;

import de.tum.cit.aet.hephaestus.core.ConditionalOnEvaluation;
import de.tum.cit.aet.hephaestus.core.EvaluationWork;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnEvaluation
@RequiredArgsConstructor
class EvaluationSnapshot {

    private final List<EvaluationWork> queues;
    private final EvaluationWork.Workspaces workspaces;

    // A provider event can finish in one transaction and leave a run behind. One snapshot must see both queues.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<EvaluationWork.Item> read() {
        var workspaceIds = workspaces.ids();
        return queues.stream()
                .flatMap(queue -> queue.items(workspaceIds).stream())
                .toList();
    }
}
