package de.tum.cit.aet.hephaestus.integration.outline.status;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.framework.CronSchedules;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncStateProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceCount;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only Outline sync state built from persisted connection, collection, and document data. */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineConnectionSyncStateProvider implements ConnectionSyncStateProvider {

    private final ConnectionService connectionRepository;
    private final OutlineCollectionRepository collectionRepository;
    private final OutlineDocumentRepository documentRepository;
    private final ObjectProvider<OutlineRateLimitTracker> rateLimitTrackerProvider;
    private final String syncCron;
    private final Clock clock;

    public OutlineConnectionSyncStateProvider(
            ConnectionService connectionRepository,
            OutlineCollectionRepository collectionRepository,
            OutlineDocumentRepository documentRepository,
            ObjectProvider<OutlineRateLimitTracker> rateLimitTrackerProvider,
            Clock clock,
            @Value("${hephaestus.integration.outline.sync.cron:0 0 */6 * * *}") String syncCron) {
        this.connectionRepository = connectionRepository;
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.rateLimitTrackerProvider = rateLimitTrackerProvider;
        this.syncCron = syncCron;
        this.clock = clock;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public ConnectionSyncDetails describe(IntegrationRef ref, long connectionId) {
        Optional<ConnectionConfig.OutlineConfig> config = connectionRepository
                .findInWorkspace(ref.workspaceId(), connectionId)
                .map(Connection::getConfig)
                .filter(ConnectionConfig.OutlineConfig.class::isInstance)
                .map(ConnectionConfig.OutlineConfig.class::cast);

        Boolean webhookRegistered = config.map(c -> c.webhookSubscriptionId() != null
                        && !c.webhookSubscriptionId().isBlank())
                .orElse(null);

        // Rate-limit budget observed on the connection's Outline host, or null until the first API call
        // since restart (the UI renders that as "–"). Keyed by server origin — see OutlineRateLimitTracker.
        OutlineRateLimitTracker tracker = rateLimitTrackerProvider.getIfAvailable();
        RateLimitSnapshot rateLimit = tracker == null
                ? null
                : config.map(c -> tracker.snapshot(OutlineRateLimitTracker.scopeOf(c.serverUrl())))
                        .orElse(null);

        return new ConnectionSyncDetails(
                webhookRegistered,
                CronSchedules.nextRun(syncCron, clock),
                CronSchedules.interval(syncCron, clock),
                rateLimit,
                null,
                false);
    }

    @Override
    public List<SyncResourceState> resources(IntegrationRef ref, long connectionId) {
        long workspaceId = ref.workspaceId();
        Map<String, Long> itemCountByCollectionId =
                documentRepository.countLiveByCollection(workspaceId, connectionId).stream()
                        .collect(Collectors.toMap(row -> (String) row[0], row -> (Long) row[1]));

        return collectionRepository.findByWorkspaceIdAndConnectionId(workspaceId, connectionId).stream()
                .map(collection -> toResourceState(collection, itemCountByCollectionId))
                .toList();
    }

    private SyncResourceState toResourceState(OutlineCollection collection, Map<String, Long> itemCountByCollectionId) {
        long itemCount = itemCountByCollectionId.getOrDefault(collection.getCollectionId(), 0L);
        String name = collection.getName() != null && !collection.getName().isBlank()
                ? collection.getName()
                : collection.getCollectionId();
        return new SyncResourceState(
                collection.getId(),
                collection.getCollectionId(),
                name,
                SyncResourceState.Type.COLLECTION,
                resourceState(collection),
                collection.getDocumentsSyncedAt(),
                itemCount,
                // One count class per collection; its watermark is the documents' own last-synced time.
                List.of(new SyncResourceCount(
                        SyncResourceCount.KEY_DOCUMENTS, "Documents", itemCount, collection.getDocumentsSyncedAt())),
                collection.getDocumentsUpstream() == null
                        ? null
                        : collection.getDocumentsUpstream().longValue(),
                collection.getLastSyncError(),
                null,
                null);
    }

    /** {@code PAUSED} takes priority — a frozen collection's stale sync status would otherwise be misleading. */
    private static String resourceState(OutlineCollection collection) {
        if (collection.getState() == MirrorState.PAUSED) {
            return "PAUSED";
        }
        return collection.getSyncStatus().name();
    }
}
