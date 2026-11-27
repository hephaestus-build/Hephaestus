package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.consumer.ProviderEventStore.Arrival;
import org.jspecify.annotations.Nullable;

/** Stores one verified arrival under the receiver's active connection lock. All work joins the receive transaction. */
public interface VerifiedWebhookHandoff {
    IntegrationKind kind();

    int store(LockedBinding binding, Arrival arrival);

    record LockedBinding(long id, @Nullable String instanceKey, ConnectionConfig config) {}
}
