package de.tum.cit.aet.hephaestus.integration.core.connection.repair;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Objects;
import java.util.UUID;

/** Scoped authorization reference. Deleting it must never delete the unresolved provider identity. */
@Entity
@Table(
        name = "connection_provider_repair",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_connection_provider_repair",
                        columnNames = {"workspace_id", "connection_id", "repair_id"}))
public class ConnectionProviderRepair {
    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false, updatable = false)
    private Connection connection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repair_id", nullable = false, updatable = false)
    private ProviderResourceRepair repair;

    @Column(name = "credentials_generation", nullable = false, updatable = false)
    private long credentialsGeneration;

    @Column(name = "lifecycle_generation", nullable = false, updatable = false)
    private long lifecycleGeneration;

    @Column(name = "connection_version", nullable = false, updatable = false)
    private long connectionVersion;

    protected ConnectionProviderRepair() {}

    public ConnectionProviderRepair(Connection connection, ProviderResourceRepair repair) {
        this.id = UUID.randomUUID();
        this.workspaceId = connection.getWorkspace().getId();
        this.connection = connection;
        this.repair = repair;
        this.credentialsGeneration = connection.getCredentialsGeneration();
        this.lifecycleGeneration = connection.getLifecycleGeneration();
        this.connectionVersion = Objects.requireNonNull(connection.getVersion());
    }

    public UUID getId() {
        return id;
    }

    public long getWorkspaceId() {
        return workspaceId;
    }

    public Connection getConnection() {
        return connection;
    }

    public ProviderResourceRepair getRepair() {
        return repair;
    }

    public long getConnectionVersion() {
        return connectionVersion;
    }

    public long getLifecycleGeneration() {
        return lifecycleGeneration;
    }

    public long getCredentialsGeneration() {
        return credentialsGeneration;
    }
}
