package de.tum.cit.aet.hephaestus.integration.outline.collection;

import de.tum.cit.aet.hephaestus.core.LoggingUtils;
import de.tum.cit.aet.hephaestus.core.event.AgentContextChangedEvent;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.SecurityUtils;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionService;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.BearerToken;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.outline.client.OutlineContentClient;
import de.tum.cit.aet.hephaestus.integration.outline.client.model.OutlineCollectionModel;
import de.tum.cit.aet.hephaestus.integration.outline.connect.OutlineConnectionResolver;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.MirrorState;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollection.SyncStatus;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineCollectionRepository;
import de.tum.cit.aet.hephaestus.integration.outline.domain.OutlineDocumentRepository;
import de.tum.cit.aet.hephaestus.integration.outline.sync.OutlineDocumentSyncScheduler;
import de.tum.cit.aet.hephaestus.integration.outline.sync.OutlineSyncDispatch;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Admin control plane for the mirrored-collection registry. Owns every repository touch for the
 * collection admin surface (the controller stays thin per the {@code controllersDoNotAccessRepositories}
 * arch rule) and resolves the workspace's ACTIVE Outline connection up front so every operation 404s
 * cleanly when the integration is not connected.
 *
 * <p>Deliberately not transactional around live Outline calls so no pooled connection is held across
 * the wire.
 */
@Service
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineCollectionAdminService {

    private static final Logger log = LoggerFactory.getLogger(OutlineCollectionAdminService.class);

    /** The candidates proxy answers a picker on the request thread, so it stays bounded (5 × 100 collections). */
    private static final int CANDIDATES_MAX_PAGES = 5;

    /** Matches {@code outline_collection.description}'s column width. */
    private static final int MAX_DESCRIPTION_LENGTH = 2048;

    private final ConnectionService connectionService;
    private final OutlineCollectionRepository collectionRepository;
    private final OutlineDocumentRepository documentRepository;
    private final OutlineContentClient outlineApiClient;
    private final OutlineDocumentSyncScheduler syncScheduler;
    private final AsyncTaskExecutor taskExecutor;
    private final ApplicationEventPublisher eventPublisher;

    public OutlineCollectionAdminService(
            ConnectionService connectionService,
            OutlineCollectionRepository collectionRepository,
            OutlineDocumentRepository documentRepository,
            OutlineContentClient outlineApiClient,
            OutlineDocumentSyncScheduler syncScheduler,
            @Qualifier("applicationTaskExecutor") AsyncTaskExecutor taskExecutor,
            ApplicationEventPublisher eventPublisher) {
        this.connectionService = connectionService;
        this.collectionRepository = collectionRepository;
        this.documentRepository = documentRepository;
        this.outlineApiClient = outlineApiClient;
        this.syncScheduler = syncScheduler;
        this.taskExecutor = taskExecutor;
        this.eventPublisher = eventPublisher;
    }

    /** Whether a registration created the row (201) or hit an existing one (idempotent 200). */
    public record RegistrationOutcome(boolean created, OutlineCollectionDTO collection) {}

    /** Consumed only after {@link #updateState} commits, so the sync it kicks can observe the ENABLED row. */
    record OutlineCollectionResumedEvent(long workspaceId, String collectionId) {}

    /** The workspace's ACTIVE Outline install, resolved once per operation. */
    private record OutlineInstall(long workspaceId, long connectionId, String serverUrl) {}

    /** The registered collections of the workspace's install, ordered by registration time. */
    public List<OutlineCollectionDTO> listCollections(long workspaceId) {
        OutlineInstall install = requireInstall(workspaceId);
        Map<String, Long> liveCounts = liveCountsByCollection(install);
        return registeredRows(install).stream()
                .map(row -> OutlineCollectionDTO.from(row, liveCounts.getOrDefault(row.getCollectionId(), 0L)))
                .toList();
    }

    /**
     * Live proxy to {@code collections.list}: every collection the token can see, flagged with whether it is
     * already mirrored. Doubles as the connectivity probe — unreachable surfaces as 502, throttled as 503.
     */
    public List<OutlineCollectionCandidateDTO> listCandidates(long workspaceId) {
        OutlineInstall install = requireInstall(workspaceId);
        Set<String> mirrored = registeredRows(install).stream()
                .map(OutlineCollection::getCollectionId)
                .collect(Collectors.toSet());
        return outlineApiClient
                .listCollections(install.serverUrl(), requireToken(workspaceId), CANDIDATES_MAX_PAGES)
                .stream()
                .filter(c -> c.getId() != null && !c.getId().isBlank())
                .map(c -> new OutlineCollectionCandidateDTO(
                        c.getId(),
                        c.getName(),
                        c.getUrlId(),
                        c.getColor(),
                        c.getIcon(),
                        c.getDescription(),
                        mirrored.contains(c.getId())))
                .sorted(Comparator.comparing(
                        OutlineCollectionCandidateDTO::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
    }

    /**
     * Registers a collection for mirroring. Idempotent on the natural key: an existing row is
     * returned as-is. A new id is verified against the live {@code collections.list} (unknown →
     * {@link UnknownOutlineCollectionException}, 422), its catalog fields are captured server-side,
     * the row lands {@code ENABLED + PENDING}, and a targeted recency-first sync is kicked off the
     * request thread.
     */
    public RegistrationOutcome register(long workspaceId, String collectionId) {
        OutlineInstall install = requireInstall(workspaceId);
        Optional<OutlineCollection> existing = collectionRepository.findByWorkspaceIdAndConnectionIdAndCollectionId(
                workspaceId, install.connectionId(), collectionId);
        if (existing.isPresent()) {
            return new RegistrationOutcome(false, toDto(install, existing.get()));
        }

        OutlineCollectionModel live =
                outlineApiClient.listCollections(install.serverUrl(), requireToken(workspaceId)).stream()
                        .filter(c -> collectionId.equals(c.getId()))
                        .findFirst()
                        .orElseThrow(() -> new UnknownOutlineCollectionException(collectionId));

        OutlineCollection row = new OutlineCollection();
        row.setWorkspaceId(workspaceId);
        row.setConnectionId(install.connectionId());
        row.setCollectionId(collectionId);
        row.setName(live.getName());
        row.setUrlId(live.getUrlId());
        row.setColor(live.getColor());
        row.setIcon(live.getIcon());
        row.setDescription(truncateDescription(live.getDescription()));
        row.setState(MirrorState.ENABLED);
        row.setSyncStatus(SyncStatus.PENDING);
        OutlineCollection saved = collectionRepository.save(row);

        kickCollectionSync(workspaceId, collectionId);
        return new RegistrationOutcome(true, OutlineCollectionDTO.from(saved, 0L));
    }

    /**
     * Moves a mirrored collection to the target state ({@code ENABLED ⇄ PAUSED}). Requesting the
     * current state is an idempotent no-op. Resuming resets the sync status to {@code PENDING} —
     * a frozen collection drifted while paused, so the catch-up tick must reconverge it — and kicks
     * a targeted sync off the request thread.
     *
     * <p>The kick is <em>published</em>, not called: this method is transactional, and the sync it triggers
     * runs asynchronously in its own transaction. Calling it inline let the sync read the row before the
     * ENABLED write committed — it would see PAUSED, no-op, and the collection would sit frozen until the
     * catch-up tick happened to notice. {@link #onCollectionResumed} runs the kick after commit instead.
     */
    @Transactional
    public OutlineCollectionDTO updateState(long workspaceId, String collectionId, MirrorState targetState) {
        OutlineInstall install = requireInstall(workspaceId);
        OutlineCollection row = requireRegistered(install, collectionId);
        if (row.getState() != targetState) {
            row.setState(targetState);
            if (targetState == MirrorState.ENABLED) {
                row.setSyncStatus(SyncStatus.PENDING);
                eventPublisher.publishEvent(new OutlineCollectionResumedEvent(workspaceId, collectionId));
            } else {
                // A running Heph turn may hold the collection's documents in its context folder.
                eventPublisher.publishEvent(new AgentContextChangedEvent(workspaceId));
            }
            row = collectionRepository.save(row);
        }
        return toDto(install, row);
    }

    /**
     * Kicks the resumed collection's targeted sync once {@link #updateState}'s transaction has committed,
     * so the async pass is guaranteed to read the ENABLED + PENDING row it is meant to converge.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onCollectionResumed(OutlineCollectionResumedEvent event) {
        kickCollectionSync(event.workspaceId(), event.collectionId());
    }

    /**
     * Removes a collection from the mirror: deletes the registry row AND hard-deletes that
     * collection's {@code outline_document} rows. Erase is the point — the mirrored bodies leave
     * the database; nothing is tombstoned or retained.
     */
    @Transactional
    public void delete(long workspaceId, String collectionId) {
        OutlineInstall install = requireInstall(workspaceId);
        OutlineCollection row = requireRegistered(install, collectionId);
        long erased = documentRepository.deleteByWorkspaceIdAndConnectionIdAndCollectionId(
                workspaceId, install.connectionId(), collectionId);
        collectionRepository.delete(row);
        eventPublisher.publishEvent(new AgentContextChangedEvent(workspaceId));
        log.info(
                "outline.audit: collection erase — actor={} removed collectionId={} from the mirror for workspaceId={} ({} mirrored document(s) erased)",
                LoggingUtils.sanitizeForLog(SecurityUtils.getCurrentUserLogin().orElse("system")),
                LoggingUtils.sanitizeForLog(collectionId),
                workspaceId,
                erased);
    }

    private List<OutlineCollection> registeredRows(OutlineInstall install) {
        return collectionRepository.findByWorkspaceIdOrderByCreatedAtAsc(install.workspaceId()).stream()
                .filter(row -> row.getConnectionId() == install.connectionId())
                .toList();
    }

    private OutlineCollection requireRegistered(OutlineInstall install, String collectionId) {
        return collectionRepository
                .findByWorkspaceIdAndConnectionIdAndCollectionId(
                        install.workspaceId(), install.connectionId(), collectionId)
                .orElseThrow(() -> new EntityNotFoundException("Outline collection", collectionId));
    }

    private OutlineCollectionDTO toDto(OutlineInstall install, OutlineCollection row) {
        long liveCount = documentRepository.countByWorkspaceIdAndConnectionIdAndCollectionIdAndDeletedAtIsNull(
                install.workspaceId(), install.connectionId(), row.getCollectionId());
        return OutlineCollectionDTO.from(row, liveCount);
    }

    private Map<String, Long> liveCountsByCollection(OutlineInstall install) {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] rowCount :
                documentRepository.countLiveByCollection(install.workspaceId(), install.connectionId())) {
            counts.put((String) rowCount[0], (Long) rowCount[1]);
        }
        return counts;
    }

    /**
     * Fires the targeted single-collection sync off the request thread through the shared
     * {@link OutlineSyncDispatch}. Routed through the {@link OutlineDocumentSyncScheduler} pass-through
     * so the executor thread (which carries no request tenancy scope) crosses the
     * {@code @WorkspaceAgnostic} bypass hop. Deliberately unguarded, unlike
     * {@code OutlineConnectionAdminService.syncNow}: both call sites (a fresh registration, a
     * PAUSED→ENABLED resume) are themselves idempotent/state-gated, so there is no human-repeatable
     * double-click path that would pile up concurrent kicks for the same collection.
     *
     * <p>Both call sites reach here only once the row they created/resumed is durably committed —
     * {@link #register} is non-transactional (its {@code save} commits on its own), and the resume goes
     * through {@link #onCollectionResumed}'s after-commit hop.
     */
    private void kickCollectionSync(long workspaceId, String collectionId) {
        OutlineSyncDispatch.fireAndForget(
                taskExecutor,
                () -> syncScheduler.syncCollectionNow(workspaceId, collectionId),
                e ->
                        // The registration/resume already succeeded; the catch-up tick retries a failed kick.
                        log.warn(
                                "outline.admin: targeted sync kick failed for workspaceId={}, collectionId={}: {}",
                                workspaceId,
                                collectionId,
                                e.toString()));
    }

    /** The workspace's ACTIVE Outline install, or a 404 when not connected — see {@link OutlineConnectionResolver}. */
    private OutlineInstall requireInstall(long workspaceId) {
        Connection connection = OutlineConnectionResolver.requireActiveConnection(connectionService, workspaceId);
        ConnectionConfig.OutlineConfig config = (ConnectionConfig.OutlineConfig) connection.getConfig();
        return new OutlineInstall(
                workspaceId,
                connection.getId(),
                Objects.requireNonNull(config.serverUrl(), "Outline connection must have a server URL"));
    }

    /** The install's stored API token; missing credentials read as "not usably connected" (404). */
    private String requireToken(long workspaceId) {
        return connectionService
                .findActiveBearerToken(workspaceId, IntegrationKind.OUTLINE)
                .map(BearerToken::token)
                .orElseThrow(() -> new EntityNotFoundException("Outline connection", Long.toString(workspaceId)));
    }

    private static @Nullable String truncateDescription(@Nullable String description) {
        if (description == null) {
            return null;
        }
        return description.length() <= MAX_DESCRIPTION_LENGTH
                ? description
                : description.substring(0, MAX_DESCRIPTION_LENGTH);
    }
}
