package de.tum.cit.aet.hephaestus.core.action;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enqueues effects on the outside world. The executor carries them out after the caller's transaction commits. */
@Service
@RequiredArgsConstructor
public class AgentActions {
    private final ActionStore store;

    /**
     * Enqueues the effect in the caller's transaction, if there is one, so it exists exactly when the caller's change
     * commits. The same
     * idempotency key returns the action already enqueued and changes nothing.
     */
    @Transactional
    public UUID enqueue(ActionRequest request) {
        return store.enqueue(request);
    }
}
