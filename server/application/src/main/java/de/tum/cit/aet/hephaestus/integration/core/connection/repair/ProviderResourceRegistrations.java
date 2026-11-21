package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import static de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs.overlaps;
import static de.tum.cit.aet.hephaestus.integration.core.connection.repair.ProviderResourceRepairs.ref;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records the provider resources that a connection leaves to repair: removals captured when a connection disconnects,
 * is suspended or completes new credentials, and the registration, ownership and release of its hooks. Identity locks
 * and adoption checks come from {@link ProviderResourceRepairs}.
 */
@Service
public class ProviderResourceRegistrations {
    private final ProviderResourceRepairRepository repairs;
    private final ConnectionProviderRepairRepository references;
    private final ConnectionRepository connections;
    private final ProviderResourceRepairs coordination;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public ProviderResourceRegistrations(
            ProviderResourceRepairRepository repairs,
            ConnectionProviderRepairRepository references,
            ConnectionRepository connections,
            ProviderResourceRepairs coordination,
            ApplicationEventPublisher events,
            Clock clock) {
        this.repairs = repairs;
        this.references = references;
        this.connections = connections;
        this.coordination = coordination;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Connection prepareCredentialCompletion(
            Connection expected, ConnectionConfig config, @Nullable CredentialBundle credential) {
        return prepareCompletion(expected, config, credential, null);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Connection prepareCredentialCompletion(
            Connection expected,
            ConnectionConfig config,
            @Nullable CredentialBundle credential,
            @Nullable UUID exchangeAttempt) {
        return prepareCompletion(expected, config, credential, exchangeAttempt);
    }

    private Connection prepareCompletion(
            Connection expected,
            ConnectionConfig config,
            @Nullable CredentialBundle credential,
            @Nullable UUID exchangeAttempt) {
        long expectedGeneration = expected.getLifecycleGeneration();
        Long expectedVersion = expected.getVersion();
        Connection current = connections
                .findByIdAndWorkspaceId(
                        expected.getId(), expected.getWorkspace().getId())
                .orElseThrow();
        coordination.lockAdoptableChange(current, config, credential, exchangeAttempt);
        connections.acquireLifecycleLock(current.getId(), current.getWorkspace().getId());
        Objects.requireNonNull(entityManager).refresh(current);
        if (current.getLifecycleGeneration() != expectedGeneration
                || !Objects.equals(current.getVersion(), expectedVersion)) {
            throw new IllegalStateException("This connection request was superseded. Start a new connection.");
        }
        boolean pendingHook = current.getState() == IntegrationState.SUSPENDED
                && (current.getKind() == IntegrationKind.GITLAB || current.getKind() == IntegrationKind.OUTLINE);
        boolean pendingTeardown =
                current.getState() == IntegrationState.UNINSTALLED && current.getProviderTeardownRequestedAt() != null;
        if ((pendingHook || pendingTeardown) && !current.providerRepairWasCaptured()) {
            captureRemoval(current, pendingHook);
        }
        return current;
    }

    @Transactional(readOnly = true)
    @WorkspaceAgnostic("Finds suspended Outline hooks; capture rereads each connection under its scoped lock")
    public List<ConnectionRepository.WebhookCleanupCandidate> suspendedOutlineHooks() {
        return connections.findSuspendedOutlineWebhookCandidates();
    }

    @Transactional
    public void captureSuspendedHook(long workspaceId, long connectionId) {
        Connection owner =
                connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElse(null);
        if (owner == null) return;
        coordination.lockIdentities(owner);
        if (connections
                .findAndLockLifecycleConnection(connectionId, workspaceId)
                .isEmpty()) return;
        Objects.requireNonNull(entityManager).refresh(owner);
        if (owner.getState() == IntegrationState.SUSPENDED) captureRemoval(owner, true);
    }

    /** Called before state/config/credentials are cleared, in the transaction that disconnects or suspends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requestRemoval(Connection connection, boolean suspension) {
        captureRemoval(connection, suspension);
    }

    private void captureRemoval(Connection connection, boolean suspension) {
        List<Resource> targets = coordination.resources(
                ref(connection), connection.getConfig(), coordination.retainedCredential(connection));
        coordination.lockIdentities(targets);
        boolean earlierCallUntracked = !connection.providerRepairWasCaptured()
                && ((connection.getState() == IntegrationState.UNINSTALLED
                                && connection.getProviderTeardownRequestedAt() != null)
                        || (suspension && connection.getState() == IntegrationState.SUSPENDED));
        for (Resource target : targets) {
            if (suspension && target.kind() != IntegrationKind.GITLAB && target.kind() != IntegrationKind.OUTLINE)
                continue;
            if (heldElsewhere(connection, target)) continue;
            ProviderResourceRepair repair = repairs.findByIdentityHash(target.identity())
                    .orElseGet(() -> repairs.save(
                            earlierCallUntracked
                                    ? ProviderResourceRepair.untrackedRemoval(target, clock.instant())
                                    : new ProviderResourceRepair(target, clock.instant())));
            if (references
                    .findByWorkspaceIdAndConnection_IdAndRepair_Id(
                            connection.getWorkspace().getId(), connection.getId(), repair.getId())
                    .isEmpty()) {
                references.save(new ConnectionProviderRepair(connection, repair));
            }
            events.publishEvent(new ProviderResourceRepairRequested(repair.getId()));
        }
        if (suspension) connection.markProviderRepairCaptured();
    }

    /** The caller must finish this transaction before it sends the external creation request. */
    @Transactional
    public UUID beginRegistration(long workspaceId, long connectionId, Resource registration) {
        if (registration.type() != ProviderResourceAdapter.ResourceType.REGISTRATION) {
            throw new IllegalArgumentException("A registration requires an ownership marker");
        }
        Connection owner =
                connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElseThrow();
        Long expectedVersion = owner.getVersion();
        var locks = new ArrayList<>(
                coordination.resources(ref(owner), owner.getConfig(), coordination.retainedCredential(owner)));
        locks.add(registration);
        coordination.lockIdentities(locks);
        connections.acquireLifecycleLock(connectionId, workspaceId);
        Objects.requireNonNull(entityManager).refresh(owner);
        if (owner.getState() != IntegrationState.ACTIVE || !Objects.equals(expectedVersion, owner.getVersion())) {
            throw new IllegalStateException("The connection changed before registration");
        }
        ProviderResourceRepair repair = repairs.save(new ProviderResourceRepair(registration, clock.instant()));
        references.save(new ConnectionProviderRepair(owner, repair));
        events.publishEvent(new ProviderResourceRepairRequested(repair.getId()));
        return repair.getId();
    }

    /** A returned creation result is adopted only by the lifecycle generation that requested it. */
    @Transactional
    public boolean completeRegistration(
            long workspaceId, long connectionId, UUID repairId, UnaryOperator<ConnectionConfig> update) {
        ConnectionProviderRepair reference = references
                .findByWorkspaceIdAndConnection_IdAndRepair_Id(workspaceId, connectionId, repairId)
                .orElseThrow();
        Connection owner = reference.getConnection();
        ConnectionConfig next = update.apply(owner.getConfig());
        var credential = coordination.retainedCredential(owner);
        var targets = coordination.resources(ref(owner), next, credential);
        var locks = new ArrayList<>(targets);
        locks.add(reference.getRepair().resource());
        locks.addAll(coordination.resources(ref(owner), owner.getConfig(), credential));
        coordination.lockIdentities(locks);
        connections.acquireLifecycleLock(connectionId, workspaceId);
        Objects.requireNonNull(entityManager).refresh(owner);
        boolean adopt = owner.getState() == IntegrationState.ACTIVE
                && Objects.equals(owner.getVersion(), reference.getConnectionVersion())
                && owner.getLifecycleGeneration() == reference.getLifecycleGeneration()
                && owner.getCredentialsGeneration() == reference.getCredentialsGeneration();
        if (adopt) {
            coordination.checkAdoption(ref(owner), next, credential);
            owner.setConfig(update.apply(owner.getConfig()));
        } else {
            // A concurrent registration of the active connection may have recorded the same hook: that one stays.
            var held = owner.getState() != IntegrationState.ACTIVE
                    ? Set.<String>of()
                    : coordination.resources(ref(owner), owner.getConfig(), credential).stream()
                            .map(Resource::identity)
                            .collect(Collectors.toSet());
            for (Resource target : targets) {
                if (!held.contains(target.identity())) requestCapturedRemoval(owner, target);
            }
        }
        references.delete(reference);
        repairs.delete(reference.getRepair());
        return adopt;
    }

    /**
     * Records the ownership of a hook that the active connection already holds, as read back from the provider. It
     * changes no resource identity, so it starts no repair. It refuses a connection that changed since {@code version}.
     *
     * @return whether the ownership was recorded
     */
    @Transactional
    public boolean recordOwnership(
            long workspaceId, long connectionId, @Nullable Long version, UnaryOperator<ConnectionConfig> update) {
        Connection owner =
                connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElseThrow();
        coordination.lockIdentities(owner);
        connections.acquireLifecycleLock(connectionId, workspaceId);
        Objects.requireNonNull(entityManager).refresh(owner);
        if (owner.getState() != IntegrationState.ACTIVE || !Objects.equals(owner.getVersion(), version)) return false;
        ConnectionConfig next = update.apply(owner.getConfig());
        var credential = coordination.retainedCredential(owner);
        if (!identities(coordination.resources(ref(owner), next, credential))
                .equals(identities(coordination.resources(ref(owner), owner.getConfig(), credential)))) {
            throw new IllegalArgumentException(
                    "Recording ownership must not change which resources a connection holds");
        }
        owner.setConfig(next);
        return true;
    }

    private static Set<String> identities(List<Resource> resources) {
        return resources.stream().map(Resource::identity).collect(Collectors.toSet());
    }

    @Transactional
    public void recordDiscovered(UUID registrationId, List<Resource> discovered) {
        ProviderResourceRepair registration = repairs.findById(registrationId).orElse(null);
        if (registration == null) return;
        var locks = new ArrayList<>(discovered);
        locks.add(registration.resource());
        coordination.lockIdentities(locks);
        Objects.requireNonNull(entityManager).clear();
        registration = repairs.findById(registrationId).orElse(null);
        if (registration == null) return;
        for (ConnectionProviderRepair reference : references.findByRepair_Id(registrationId)) {
            connections.acquireLifecycleLock(reference.getConnection().getId(), reference.getWorkspaceId());
            for (Resource resource : discovered) requestCapturedRemoval(reference.getConnection(), resource);
        }
        // An empty listing does not exclude a timed-out creation completing later.
        registration.transition(ProviderResourceRepair.State.AWAITING_CONFIRMATION, clock.instant());
    }

    @Transactional
    public void retireCurrentHook(long workspaceId, long connectionId) {
        Connection connection =
                connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElseThrow();
        coordination.lockIdentities(connection);
        connections.acquireLifecycleLock(connectionId, workspaceId);
        Objects.requireNonNull(entityManager).refresh(connection);
        captureRemoval(connection, true);
    }

    /**
     * Captures the removal of {@code released}, hooks that an active connection no longer needs, and stores
     * {@code update}, which forgets them, in the same transaction. Each hook is matched by its identity; one the
     * connection no longer holds is skipped.
     */
    @Transactional
    public void releaseHooks(
            long workspaceId, long connectionId, List<Resource> released, UnaryOperator<ConnectionConfig> update) {
        Connection owner =
                connections.findByIdAndWorkspaceId(connectionId, workspaceId).orElseThrow();
        var locks = new ArrayList<>(
                coordination.resources(ref(owner), owner.getConfig(), coordination.retainedCredential(owner)));
        locks.addAll(released);
        coordination.lockIdentities(locks);
        connections.acquireLifecycleLock(connectionId, workspaceId);
        Objects.requireNonNull(entityManager).refresh(owner);
        if (owner.getState() != IntegrationState.ACTIVE) return;
        // The held resource carries the ownership proof the adapter derives, such as a legacy hook's.
        var held =
                coordination.resources(ref(owner), owner.getConfig(), coordination.retainedCredential(owner)).stream()
                        .collect(Collectors.toMap(Resource::identity, resource -> resource, (first, second) -> first));
        for (Resource resource : released) {
            Resource current = held.get(resource.identity());
            if (current != null) requestCapturedRemoval(owner, current);
        }
        owner.setConfig(update.apply(owner.getConfig()));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void clearCompletedHook(UUID repairId, Resource resource) {
        for (var reference : references.findByRepair_Id(repairId).stream()
                .sorted(Comparator.comparing(ref -> ref.getConnection().getId()))
                .toList()) {
            Connection owner = reference.getConnection();
            connections.acquireLifecycleLock(owner.getId(), reference.getWorkspaceId());
            Objects.requireNonNull(entityManager).refresh(owner);
            if (owner.getState() != IntegrationState.ACTIVE
                    && (resource.type() == ProviderResourceAdapter.ResourceType.GROUP_HOOK
                            || resource.type() == ProviderResourceAdapter.ResourceType.PROJECT_HOOK
                            || resource.type() == ProviderResourceAdapter.ResourceType.SUBSCRIPTION)
                    && coordination
                            .resources(ref(owner), owner.getConfig(), coordination.retainedCredential(owner))
                            .stream()
                            .anyMatch(current -> current.identity().equals(resource.identity()))) {
                owner.detachProviderWebhook();
            }
        }
    }

    private void requestCapturedRemoval(Connection owner, Resource resource) {
        if (heldElsewhere(owner, resource)) return;
        ProviderResourceRepair repair = repairs.findByIdentityHash(resource.identity())
                .orElseGet(() -> repairs.save(new ProviderResourceRepair(resource, clock.instant())));
        if (references
                .findByWorkspaceIdAndConnection_IdAndRepair_Id(
                        owner.getWorkspace().getId(), owner.getId(), repair.getId())
                .isEmpty()) {
            references.save(new ConnectionProviderRepair(owner, repair));
        }
        events.publishEvent(new ProviderResourceRepairRequested(repair.getId()));
    }

    private boolean heldElsewhere(Connection removing, Resource target) {
        return heldByLiveConnection(removing.getId(), target);
    }

    private boolean heldByLiveConnection(@Nullable Long removingId, Resource target) {
        return connections
                .findAllByKindAndStateIn(target.kind(), List.of(IntegrationState.ACTIVE, IntegrationState.SUSPENDED))
                .stream()
                .filter(owner -> owner.getState() == IntegrationState.ACTIVE
                        || (target.kind() != IntegrationKind.GITLAB && target.kind() != IntegrationKind.OUTLINE))
                .filter(owner -> !Objects.equals(owner.getId(), removingId))
                .flatMap(owner -> coordination.resources(ref(owner), owner.getConfig(), null).stream())
                .anyMatch(owned -> overlaps(owned, target));
    }
}
