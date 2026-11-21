package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionConfig;
import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialReader;
import de.tum.cit.aet.hephaestus.integration.core.connection.CredentialUnreadableException;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Identity coordination precedes connection locks, including when no fence row exists yet. */
@Service
public class ProviderResourceRepairs {
    private final ProviderResourceRepairRepository repairs;
    private final ConnectionProviderRepairRepository references;
    private final ConnectionRepository connections;
    private final CredentialReader credentials;
    private final ObjectProvider<ProviderResourceAdapter> adapters;
    private final Clock clock;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public ProviderResourceRepairs(
            ProviderResourceRepairRepository repairs,
            ConnectionProviderRepairRepository references,
            ConnectionRepository connections,
            CredentialReader credentials,
            ObjectProvider<ProviderResourceAdapter> adapters,
            Clock clock) {
        this.repairs = repairs;
        this.references = references;
        this.connections = connections;
        this.credentials = credentials;
        this.adapters = adapters;
        this.clock = clock;
    }

    public List<Resource> resources(
            IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        return adapters.stream()
                .flatMap(adapter -> adapter.resources(ref, config, credential).stream())
                .sorted(Comparator.comparing(Resource::identity))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void coordinate(Connection connection) {
        lockIdentities(connection);
    }

    void lockIdentities(Connection connection) {
        lockIdentities(resources(ref(connection), connection.getConfig(), retainedCredential(connection)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void coordinate(List<Resource> resources) {
        lockIdentities(resources);
    }

    void lockIdentities(List<Resource> resources) {
        for (String identity :
                resources.stream().map(Resource::identity).distinct().sorted().toList()) {
            // A hash collision adds serialization; the full identity hash still decides equality in the table.
            repairs.lockIdentity(identity);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAdoptable(IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        checkAdoption(ref, config, credential);
    }

    void checkAdoption(IntegrationRef ref, ConnectionConfig config, @Nullable CredentialBundle credential) {
        checkAdoption(ref, config, credential, null);
    }

    private void checkAdoption(
            IntegrationRef ref,
            ConnectionConfig config,
            @Nullable CredentialBundle credential,
            @Nullable UUID exchangeAttempt) {
        List<Resource> targets = adoptionIdentities(resources(ref, config, credential));
        lockIdentities(targets);
        // A removal that its connection requested before its resources were captured has no repair row yet.
        boolean uncapturedRemoval = Stream.concat(
                        connections
                                .findByStateAndProviderTeardownRequestedAtNotNull(IntegrationState.UNINSTALLED)
                                .stream(),
                        Stream.of(IntegrationKind.GITLAB, IntegrationKind.OUTLINE)
                                .flatMap(kind ->
                                        connections
                                                .findAllByKindAndStateIn(kind, List.of(IntegrationState.SUSPENDED))
                                                .stream()))
                .filter(owner -> !owner.providerRepairWasCaptured())
                .flatMap(owner -> resources(ref(owner), owner.getConfig(), null).stream())
                .anyMatch(old -> targets.stream().anyMatch(candidate -> overlaps(candidate, old)));
        boolean pendingExchange = config instanceof ConnectionConfig.SlackConfig slack
                && slack.teamId() != null
                && !alreadyHoldsSlackTeam(ref, slack)
                && repairs.hasUnresolvedSlackExchange(
                        ProviderResourceAdapter.ResourceType.OAUTH_EXCHANGE,
                        slack.teamId(),
                        slack.botUserId() == null ? "*" : slack.botUserId(),
                        slack.botTokenFingerprint() == null ? "" : slack.botTokenFingerprint(),
                        exchangeAttempt);
        if (uncapturedRemoval
                || pendingExchange
                || targets.stream()
                        .anyMatch(resource ->
                                repairs.findByIdentityHash(resource.identity()).isPresent())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This provider resource is still pending removal. Use a fresh resource or ask an instance admin to review the removal.");
        }
    }

    private boolean alreadyHoldsSlackTeam(IntegrationRef ref, ConnectionConfig.SlackConfig next) {
        if (ref.connectionId() == null) return false;
        // Exchange intent cannot revoke anything. It must not stop the existing team holder from reauthorizing.
        return connections
                .findByIdAndWorkspaceId(ref.connectionId(), ref.workspaceId())
                .filter(owner ->
                        owner.getState() == IntegrationState.ACTIVE || owner.getState() == IntegrationState.SUSPENDED)
                .map(Connection::getConfig)
                .filter(config -> config instanceof ConnectionConfig.SlackConfig)
                .map(config -> (ConnectionConfig.SlackConfig) config)
                .filter(current -> Objects.equals(current.teamId(), next.teamId()))
                .isPresent();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireCoordinated(List<Resource> acquired, List<Resource> current) {
        var coordinated = acquired.stream().map(Resource::identity).collect(Collectors.toSet());
        if (current.stream().anyMatch(resource -> !coordinated.contains(resource.identity())))
            throw new TransientDataAccessResourceException(
                    "The connection's provider identity changed during its update");
    }

    @Transactional
    public List<UUID> retryWithRestoredAuthorization(long workspaceId, long connectionId) {
        var targets = references.findByWorkspaceIdAndConnection_Id(workspaceId, connectionId).stream()
                .map(reference -> reference.getRepair().resource())
                .toList();
        lockIdentities(targets);
        Objects.requireNonNull(entityManager).clear();
        var pending = references.findByWorkspaceIdAndConnection_Id(workspaceId, connectionId).stream()
                .map(ConnectionProviderRepair::getRepair)
                .filter(repair -> switch (repair.resource().type()) {
                    case REGISTRATION, OAUTH_EXCHANGE -> false;
                    default -> true;
                })
                .toList();
        if (pending.stream()
                .anyMatch(repair -> repair.getState() == ProviderResourceRepair.State.CHECKING
                        || repair.getState() == ProviderResourceRepair.State.DISPATCHING)) {
            // Keep the credential publication unfinished until the previous provider outcome is recorded.
            throw new TransientDataAccessResourceException("The provider operation is still in progress");
        }
        return pending.stream()
                .filter(repair -> repair.authorizationAvailable(clock.instant()))
                .map(ProviderResourceRepair::getId)
                .toList();
    }

    private static List<Resource> adoptionIdentities(List<Resource> resources) {
        var identities = new ArrayList<>(resources);
        for (Resource resource : resources) {
            if (resource.kind() == IntegrationKind.GITHUB
                    && resource.type() == ProviderResourceAdapter.ResourceType.INSTALLATION
                    && !"*".equals(resource.parentId())) {
                identities.add(new Resource(
                        resource.kind(), resource.origin(), resource.type(), "*", resource.resourceId(), null));
            }
            if (resource.kind() != IntegrationKind.SLACK) continue;
            if (resource.type() == ProviderResourceAdapter.ResourceType.BOT_USER
                    && !"*".equals(resource.resourceId())) {
                identities.add(new Resource(
                        resource.kind(), resource.origin(), resource.type(), resource.parentId(), "*", null));
            } else if (resource.type() == ProviderResourceAdapter.ResourceType.BOT_TOKEN
                    && resource.parentId().contains("/")) {
                identities.add(new Resource(
                        resource.kind(),
                        resource.origin(),
                        resource.type(),
                        resource.parentId().substring(0, resource.parentId().indexOf('/')) + "/*",
                        resource.resourceId(),
                        null));
            }
        }
        return identities;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAdoptableChange(Connection owner, ConnectionConfig next, @Nullable CredentialBundle credential) {
        lockAdoptableChange(owner, next, credential);
    }

    private void lockAdoptableChange(Connection owner, ConnectionConfig next, @Nullable CredentialBundle credential) {
        lockAdoptableChange(owner, next, credential, null);
    }

    void lockAdoptableChange(
            Connection owner,
            ConnectionConfig next,
            @Nullable CredentialBundle credential,
            @Nullable UUID exchangeAttempt) {
        var targets = new ArrayList<>(resources(ref(owner), owner.getConfig(), retainedCredential(owner)));
        targets.addAll(resources(ref(owner), next, credential));
        if (exchangeAttempt != null) {
            var attempt = references
                    .findByWorkspaceIdAndConnection_IdAndRepair_Id(
                            owner.getWorkspace().getId(), owner.getId(), exchangeAttempt)
                    .orElseThrow();
            if (!(next instanceof ConnectionConfig.SlackConfig slack)
                    || !attempt.getRepair().matchesOAuthIdentity(slack)) {
                throw new IllegalStateException("The authorization result does not match its exchange attempt");
            }
            targets.add(attempt.getRepair().resource());
        }
        lockIdentities(adoptionIdentities(targets));
        checkAdoption(ref(owner), next, credential, exchangeAttempt);
    }

    static boolean overlaps(Resource owned, Resource target) {
        if (owned.identity().equals(target.identity())) return true;
        if (owned.kind() == IntegrationKind.GITHUB
                && target.kind() == owned.kind()
                && owned.type() == ProviderResourceAdapter.ResourceType.INSTALLATION
                && target.type() == owned.type()
                && owned.origin().equals(target.origin())
                && owned.resourceId().equals(target.resourceId())
                && ("*".equals(owned.parentId()) || "*".equals(target.parentId()))) return true;
        if (owned.kind() != IntegrationKind.SLACK
                || target.kind() != IntegrationKind.SLACK
                || !owned.origin().equals(target.origin())) return false;
        if (owned.type() == ProviderResourceAdapter.ResourceType.BOT_USER
                && target.type() == ProviderResourceAdapter.ResourceType.BOT_TOKEN
                && target.parentId().startsWith(owned.parentId() + "/")
                && ("*".equals(owned.resourceId())
                        || target.parentId().endsWith("/*")
                        || target.parentId().equals(owned.parentId() + "/" + owned.resourceId()))) return true;
        if (owned.type() == ProviderResourceAdapter.ResourceType.BOT_USER && target.type() == owned.type()) {
            return owned.parentId().equals(target.parentId())
                    && ("*".equals(owned.resourceId()) || "*".equals(target.resourceId()));
        }
        return owned.type() == ProviderResourceAdapter.ResourceType.BOT_TOKEN
                && target.type() == owned.type()
                && owned.resourceId().equals(target.resourceId());
    }

    public boolean replacementInUse(ConnectionProviderRepairRepository.OwnerRow owner, Resource old) {
        if (owner.state() != IntegrationState.ACTIVE) return false;
        List<Resource> current = resources(
                new IntegrationRef(old.kind(), owner.workspaceId(), owner.instanceKey(), owner.connectionId()),
                owner.config(),
                null);
        if (old.type() == ProviderResourceAdapter.ResourceType.REGISTRATION) {
            return old.ownershipHash() != null
                    && current.stream()
                            .anyMatch(resource -> resource.ownershipHash() != null
                                    && !resource.ownershipHash().equals(old.ownershipHash()));
        }
        // A project hook is replaced only by a hook on the same project.
        return current.stream()
                        .anyMatch(resource -> resource.type() == old.type()
                                && (old.type() != ProviderResourceAdapter.ResourceType.PROJECT_HOOK
                                        || resource.parentId().equals(old.parentId())))
                && current.stream().noneMatch(resource -> overlaps(resource, old));
    }

    public @Nullable CredentialBundle retainedCredential(Connection connection) {
        try {
            return credentials.retainedForTeardown(connection).orElse(null);
        } catch (CredentialUnreadableException | MissingCredentialKeyException unreadable) {
            return null;
        }
    }

    public static IntegrationRef ref(Connection connection) {
        return new IntegrationRef(
                connection.getKind(),
                connection.getWorkspace().getId(),
                connection.getInstanceKey(),
                connection.getId());
    }

    public Optional<ProviderResourceAdapter> adapter(IntegrationKind kind) {
        return adapters.stream().filter(adapter -> adapter.kind() == kind).findFirst();
    }

    @Transactional(readOnly = true)
    public List<ProviderResourceRepair> forConnection(long workspaceId, long connectionId) {
        return references.findByWorkspaceIdAndConnection_Id(workspaceId, connectionId).stream()
                .map(ConnectionProviderRepair::getRepair)
                .toList();
    }
}
