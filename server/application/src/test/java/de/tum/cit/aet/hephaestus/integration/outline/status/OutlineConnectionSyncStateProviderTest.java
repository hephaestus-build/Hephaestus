package de.tum.cit.aet.hephaestus.integration.outline.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ConnectionSyncDetails;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.RateLimitSnapshot;
import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineRateLimitTracker;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.SyncStatus;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.ObjectProvider;

class OutlineConnectionSyncStateProviderTest extends BaseUnitTest {

    private static final long WORKSPACE = 7L;
    private static final long CONNECTION_ID = 42L;
    private static final IntegrationRef REF = new IntegrationRef(IntegrationKind.OUTLINE, WORKSPACE, "team-1");
    private static final Clock NOON = Clock.fixed(Instant.parse("2026-07-01T12:00:00Z"), ZoneOffset.UTC);

    /** {@code localTime} in the zone the server schedules in. */
    private static Instant scheduled(String localTime) {
        return LocalDateTime.parse(localTime).atZone(ZoneId.systemDefault()).toInstant();
    }

    @Mock
    private ConnectionService connectionRepository;

    @Mock
    private OutlineCollectionRepository collectionRepository;

    @Mock
    private OutlineDocumentRepository documentRepository;

    @Mock
    private Connection connection;

    @Mock
    private ObjectProvider<OutlineRateLimitTracker> rateLimitTrackerProvider;

    @Mock
    private OutlineRateLimitTracker rateLimitTracker;

    private OutlineConnectionSyncStateProvider provider(String cron, Clock clock) {
        return new OutlineConnectionSyncStateProvider(
                connectionRepository, collectionRepository, documentRepository, rateLimitTrackerProvider, clock, cron);
    }

    private OutlineConnectionSyncStateProvider provider(String cron) {
        return provider(cron, NOON);
    }

    private OutlineConnectionSyncStateProvider provider() {
        return provider("0 0 */6 * * *");
    }

    @Test
    void describe_webhookSubscriptionIdPresent_isRegistered() {
        lenient()
                .when(connection.getConfig())
                .thenReturn(new ConnectionConfig.OutlineConfig(
                        "https://outline.example.test", "sub-1", "secret", Set.of()));
        when(connectionRepository.findInWorkspace(REF.workspaceId(), CONNECTION_ID))
                .thenReturn(Optional.of(connection));

        ConnectionSyncDetails details = provider().describe(REF, CONNECTION_ID);

        assertThat(details.webhookRegistered()).isTrue();
        assertThat(details.backfill()).isNull();
        assertThat(details.rateLimit()).isNull();
        assertThat(details.vendorHealthDegraded()).isFalse();
    }

    @Test
    void describe_surfacesTheTrackedRateLimitForTheConnectionHost() {
        String serverUrl = "https://outline.example.test";
        lenient()
                .when(connection.getConfig())
                .thenReturn(new ConnectionConfig.OutlineConfig(serverUrl, "sub-1", "secret", Set.of()));
        when(connectionRepository.findInWorkspace(REF.workspaceId(), CONNECTION_ID))
                .thenReturn(Optional.of(connection));

        RateLimitSnapshot snapshot = new RateLimitSnapshot(
                1000, 987, Instant.parse("2026-07-17T12:00:00Z"), Instant.parse("2026-07-17T11:59:00Z"), null);
        when(rateLimitTrackerProvider.getIfAvailable()).thenReturn(rateLimitTracker);
        // Keyed by the normalized server origin, exactly as the exchange filter records it.
        when(rateLimitTracker.snapshot(OutlineRateLimitTracker.scopeOf(serverUrl)))
                .thenReturn(snapshot);

        assertThat(provider().describe(REF, CONNECTION_ID).rateLimit()).isSameAs(snapshot);
    }

    @Test
    void describe_blankWebhookSubscriptionId_isNotRegistered() {
        lenient()
                .when(connection.getConfig())
                .thenReturn(
                        new ConnectionConfig.OutlineConfig("https://outline.example.test", " ", "secret", Set.of()));
        when(connectionRepository.findInWorkspace(REF.workspaceId(), CONNECTION_ID))
                .thenReturn(Optional.of(connection));

        assertThat(provider().describe(REF, CONNECTION_ID).webhookRegistered()).isFalse();
    }

    @Test
    void describe_connectionMissing_webhookRegisteredIsNullNotFalse() {
        when(connectionRepository.findInWorkspace(REF.workspaceId(), CONNECTION_ID))
                .thenReturn(Optional.empty());

        assertThat(provider().describe(REF, CONNECTION_ID).webhookRegistered()).isNull();
    }

    @Test
    void describe_nextScheduledSyncAt_isTheCronsNextRunAfterTheClock() {
        when(connectionRepository.findInWorkspace(REF.workspaceId(), CONNECTION_ID))
                .thenReturn(Optional.empty());
        Clock clock = Clock.fixed(scheduled("2026-10-25T00:34"), ZoneOffset.UTC);

        Instant next =
                provider("0 0 0 * * *", clock).describe(REF, CONNECTION_ID).nextScheduledSyncAt();

        assertThat(next).isEqualTo(scheduled("2026-10-26T00:00"));
    }

    @Test
    void describe_invalidCron_yieldsNullNextScheduledSyncAt() {
        when(connectionRepository.findInWorkspace(REF.workspaceId(), CONNECTION_ID))
                .thenReturn(Optional.empty());

        assertThat(provider("not a cron").describe(REF, CONNECTION_ID).nextScheduledSyncAt())
                .isNull();
    }

    @Test
    void resources_mapsOneRowPerCollection() {
        OutlineCollection collection = new OutlineCollection();
        collection.setId(101L);
        collection.setWorkspaceId(WORKSPACE);
        collection.setConnectionId(CONNECTION_ID);
        collection.setCollectionId("col-1");
        collection.setName("Design");
        collection.setState(MirrorState.ENABLED);
        collection.setSyncStatus(SyncStatus.COMPLETE);
        Instant syncedAt = Instant.parse("2026-07-01T00:00:00Z");
        collection.setDocumentsSyncedAt(syncedAt);
        collection.setDocumentsUpstream(12);
        collection.setLastSyncError(null);

        when(collectionRepository.findByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION_ID))
                .thenReturn(List.of(collection));
        when(documentRepository.countLiveByCollection(WORKSPACE, CONNECTION_ID))
                .thenReturn(List.<Object[]>of(new Object[] {"col-1", 9L}));

        List<SyncResourceState> resources = provider().resources(REF, CONNECTION_ID);

        assertThat(resources).hasSize(1);
        SyncResourceState resource = resources.get(0);
        assertThat(resource.id()).isEqualTo(101L);
        assertThat(resource.externalId()).isEqualTo("col-1");
        assertThat(resource.name()).isEqualTo("Design");
        assertThat(resource.type()).isEqualTo(SyncResourceState.Type.COLLECTION);
        assertThat(resource.state()).isEqualTo("COMPLETE");
        assertThat(resource.lastSyncedAt()).isEqualTo(syncedAt);
        assertThat(resource.itemCount()).isEqualTo(9L);
        assertThat(resource.upstreamCount()).isEqualTo(12L);
        assertThat(resource.lastError()).isNull();
        assertThat(resource.backfillCompletedThrough()).isNull();
        assertThat(resource.backfillPercent()).isNull();
    }

    @Test
    void resources_pausedCollection_reportsPausedRegardlessOfSyncStatus() {
        OutlineCollection paused = new OutlineCollection();
        paused.setId(102L);
        paused.setWorkspaceId(WORKSPACE);
        paused.setConnectionId(CONNECTION_ID);
        paused.setCollectionId("col-2");
        paused.setName("Archive");
        paused.setState(MirrorState.PAUSED);
        paused.setSyncStatus(SyncStatus.PENDING);

        when(collectionRepository.findByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION_ID))
                .thenReturn(List.of(paused));
        when(documentRepository.countLiveByCollection(WORKSPACE, CONNECTION_ID)).thenReturn(List.of());

        assertThat(provider().resources(REF, CONNECTION_ID).get(0).state()).isEqualTo("PAUSED");
    }

    @Test
    void resources_missingName_fallsBackToTheCollectionId() {
        OutlineCollection unnamed = new OutlineCollection();
        unnamed.setId(103L);
        unnamed.setWorkspaceId(WORKSPACE);
        unnamed.setConnectionId(CONNECTION_ID);
        unnamed.setCollectionId("col-3");
        unnamed.setState(MirrorState.ENABLED);
        unnamed.setSyncStatus(SyncStatus.PENDING);

        when(collectionRepository.findByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION_ID))
                .thenReturn(List.of(unnamed));
        when(documentRepository.countLiveByCollection(WORKSPACE, CONNECTION_ID)).thenReturn(List.of());

        assertThat(provider().resources(REF, CONNECTION_ID).get(0).name()).isEqualTo("col-3");
    }

    @Test
    void resources_carriesTheLastSyncErrorThrough() {
        OutlineCollection errored = new OutlineCollection();
        errored.setId(104L);
        errored.setWorkspaceId(WORKSPACE);
        errored.setConnectionId(CONNECTION_ID);
        errored.setCollectionId("col-4");
        errored.setName("Broken");
        errored.setState(MirrorState.ENABLED);
        errored.setSyncStatus(SyncStatus.PENDING);
        errored.setLastSyncError("Outline /api/documents.export failed (HTTP 500)");

        when(collectionRepository.findByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION_ID))
                .thenReturn(List.of(errored));
        when(documentRepository.countLiveByCollection(WORKSPACE, CONNECTION_ID)).thenReturn(List.of());

        assertThat(provider().resources(REF, CONNECTION_ID).get(0).lastError())
                .isEqualTo("Outline /api/documents.export failed (HTTP 500)");
    }

    @Test
    void resources_noCollections_isEmpty() {
        when(collectionRepository.findByWorkspaceIdAndConnectionId(WORKSPACE, CONNECTION_ID))
                .thenReturn(List.of());
        when(documentRepository.countLiveByCollection(WORKSPACE, CONNECTION_ID)).thenReturn(List.of());

        assertThat(provider().resources(REF, CONNECTION_ID)).isEmpty();
    }
}
