package de.tum.cit.aet.hephaestus.integration.core.consumer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores one event for a workspace and claims until the consumer has handled it, as the claimers would. */
public final class StoredEventHandling {

    private StoredEventHandling() {}

    /** Stores one event for a workspace, or an installation event, and returns its id. */
    public static long stored(ProviderEventStore store, JdbcTemplate jdbc, @Nullable Long workspaceId, String subject) {
        String delivery = UUID.randomUUID().toString();
        String provider = subject.substring(0, subject.indexOf('.'));
        store.store(List.of(
                new ProviderEventStore.Arrival(workspaceId, provider, subject, delivery, Map.of(), new byte[0])));
        Long id = jdbc.queryForObject("SELECT id FROM provider_event WHERE delivery_key = ?", Long.class, delivery);
        if (id == null) throw new IllegalStateException("The event was not stored");
        return id;
    }

    /** A synthetic lease for tests that invoke the dispatch boundary directly. */
    public static long leased(ProviderEventStore store, JdbcTemplate jdbc, @Nullable Long workspaceId, String subject) {
        long id = stored(store, jdbc, workspaceId, subject);
        jdbc.update(
                "UPDATE provider_event SET state='LEASED', lease_epoch=1, leased_until=clock_timestamp()+interval '5 minutes' WHERE id=?",
                id);
        return id;
    }

    /** @return the state the event ended in: DONE, or QUEUED for a retry, or DEAD */
    public static String handle(
            ProviderEventStore store,
            ProviderEventConsumer consumer,
            JdbcTemplate jdbc,
            @Nullable Long workspaceId,
            String subject,
            byte[] payload) {
        String delivery = UUID.randomUUID().toString();
        String provider = subject.substring(0, subject.indexOf('.'));
        store.store(
                List.of(new ProviderEventStore.Arrival(workspaceId, provider, subject, delivery, Map.of(), payload)));
        for (int attempt = 0; attempt < 100; attempt++) {
            String state = jdbc.queryForObject(
                    "SELECT state FROM provider_event WHERE delivery_key = ?", String.class, delivery);
            if (!"QUEUED".equals(state) || !consumer.claimAndHandle()) {
                return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT state FROM provider_event WHERE delivery_key = ?", String.class, delivery));
            }
        }
        throw new IllegalStateException("The event was not handled");
    }

    /** Starts two claimer threads, as a server runs them, for a test that receives over HTTP. The caller stops them. */
    public static ProviderEventClaimers runningClaimers(
            ProviderEventConsumer consumer, ProviderEventNotifications notifications) {
        var claimers =
                new ProviderEventClaimers(consumer, notifications, ProviderEventConsumerPropertiesFixture.defaults());
        claimers.start();
        return claimers;
    }
}
