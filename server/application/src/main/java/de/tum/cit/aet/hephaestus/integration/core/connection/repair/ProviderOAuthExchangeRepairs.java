package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionRepository;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationRef;
import de.tum.cit.aet.hephaestus.integration.core.spi.OAuthExchangeRepair;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.Resource;
import de.tum.cit.aet.hephaestus.integration.core.spi.ProviderResourceAdapter.ResourceType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProviderOAuthExchangeRepairs implements OAuthExchangeRepair {
    private final ProviderResourceRepairRepository repairs;
    private final ConnectionProviderRepairRepository references;
    private final ConnectionRepository connections;
    private final ProviderResourceRepairs coordination;
    private final Clock clock;

    @PersistenceContext
    private @Nullable EntityManager entityManager;

    public ProviderOAuthExchangeRepairs(
            ProviderResourceRepairRepository repairs,
            ConnectionProviderRepairRepository references,
            ConnectionRepository connections,
            ProviderResourceRepairs coordination,
            Clock clock) {
        this.repairs = repairs;
        this.references = references;
        this.connections = connections;
        this.coordination = coordination;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID begin(IntegrationRef owner) {
        if (owner.kind() != IntegrationKind.SLACK || owner.connectionId() == null)
            throw new IllegalArgumentException("A Slack exchange needs its bound connection");
        var resource = new Resource(
                IntegrationKind.SLACK,
                "https://slack.com",
                ResourceType.OAUTH_EXCHANGE,
                owner.instanceKey() == null ? "" : owner.instanceKey(),
                UUID.randomUUID().toString(),
                null);
        coordination.coordinate(List.of(resource));
        if (connections
                .findAndLockLifecycleConnection(owner.connectionId(), owner.workspaceId())
                .isEmpty()) {
            throw new IllegalStateException("The connection request was superseded");
        }
        var connection = connections
                .findByIdAndWorkspaceId(owner.connectionId(), owner.workspaceId())
                .orElseThrow();
        if (connection.getKind() != IntegrationKind.SLACK) throw new IllegalArgumentException("Not a Slack connection");
        if (!Objects.equals(connection.getInstanceKey(), owner.instanceKey()))
            throw new IllegalStateException("The connection request was superseded");
        var row = repairs.save(new ProviderResourceRepair(resource, clock.instant()));
        references.save(new ConnectionProviderRepair(connection, row));
        return row.getId();
    }

    @Override
    @Transactional
    public void observed(UUID attemptId, String teamId, @Nullable String botUserId, String tokenFingerprint) {
        var row = repairs.findById(attemptId).orElseThrow();
        String bot = botUserId == null ? "*" : botUserId;
        var identities = new ArrayList<Resource>();
        identities.add(row.resource());
        identities.add(
                new Resource(IntegrationKind.SLACK, "https://slack.com", ResourceType.BOT_USER, teamId, bot, null));
        identities.add(new Resource(
                IntegrationKind.SLACK,
                "https://slack.com",
                ResourceType.BOT_TOKEN,
                teamId + "/" + bot,
                tokenFingerprint,
                null));
        coordination.coordinate(identities);
        Objects.requireNonNull(entityManager).refresh(row);
        row.observeOAuth(teamId, botUserId, tokenFingerprint, clock.instant());
    }

    @Override
    @Transactional
    public void uncertain(UUID attemptId) {
        var row = repairs.findById(attemptId).orElse(null);
        if (row == null) return;
        coordination.coordinate(List.of(row.resource()));
        Objects.requireNonNull(entityManager).refresh(row);
        requireExchange(row);
        if (row.getState() != ProviderResourceRepair.State.CONFIRMATION_REQUIRED)
            row.transition(ProviderResourceRepair.State.AWAITING_CONFIRMATION, clock.instant());
    }

    @Override
    @Transactional
    public void discard(UUID attemptId) {
        var row = repairs.findById(attemptId).orElse(null);
        if (row == null) return;
        coordination.coordinate(List.of(row.resource()));
        Objects.requireNonNull(entityManager).refresh(row);
        requireExchange(row);
        references.deleteAll(references.findByRepair_Id(attemptId));
        repairs.delete(row);
    }

    private static void requireExchange(ProviderResourceRepair row) {
        if (row.resource().type() != ResourceType.OAUTH_EXCHANGE)
            throw new IllegalArgumentException("Not an OAuth exchange attempt");
    }
}
