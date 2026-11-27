package de.tum.cit.aet.hephaestus.integration.core.consumer;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore.Arrival;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Provider-specific admissions join the verified receiver's transaction. */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class ProviderEventIntake {
    private final ProviderEventRepository events;
    private final ObjectMapper mapper;

    /** A delivery the caller already handles inside its receive transaction. The legacy queue never claims it. */
    public record Handled(long id, Instant receivedAt) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Handled> storeHandled(long workspaceId, Arrival arrival) {
        if (!Long.valueOf(workspaceId).equals(arrival.workspaceId()))
            throw new IllegalArgumentException("Arrival must match the verified workspace");
        return events.storeHandled(ProviderEventStore.row(arrival, mapper)).map(receipt -> new Handled(receipt.getId(), receipt.getReceivedAt()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hasDelivery(long workspaceId, String provider, String deliveryKey) {
        return events.existsByWorkspaceIdAndProviderAndDeliveryKey(workspaceId, provider, deliveryKey);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void boundLocks(Duration timeout) {
        events.boundLocks(timeout.toMillis() + "ms");
    }

}
