package de.tum.cit.aet.hephaestus.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;

/**
 * One person who can read a monitored repository on the provider, as this workspace's last complete listing of its
 * readers showed. A monitor holds readers only while a listing proves them: the listing writes them in the same
 * transaction, and every answer that ends that proof, a stopped listing among them, removes them. The listing names the
 * readers that the workspace's credential can see, so it can leave a reader out but never add one; a read rechecks a
 * listed reader on the provider. A removal
 * on the provider removes the person's rows through database triggers on the membership mirror tables.
 *
 * <p>The reader is the provider and its native user id, not an account: a check maps an account to its provider
 * identity, so a login that the provider gives to someone else later never names this reader, and the same number on
 * another provider names someone else.
 */
@Entity
@Table(
        name = "resource_reader",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_resource_reader_monitor_reader",
                        // The monitor's key leads, so the index also serves the foreign key to the monitor.
                        columnNames = {"repository_to_monitor_id", "workspace_id", "provider_id", "reader_native_id"}),
        indexes = @Index(name = "idx_resource_reader_provider_reader", columnList = "provider_id, reader_native_id"))
@Getter
public class ResourceReader {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @Column(name = "repository_to_monitor_id", nullable = false, updatable = false)
    private long repositoryToMonitorId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns(
            value = {
                @JoinColumn(
                        name = "workspace_id",
                        referencedColumnName = "workspace_id",
                        insertable = false,
                        updatable = false),
                @JoinColumn(
                        name = "repository_to_monitor_id",
                        referencedColumnName = "id",
                        insertable = false,
                        updatable = false)
            },
            foreignKey = @ForeignKey(name = "fk_resource_reader_repository_to_monitor"))
    @OnDelete(action = OnDeleteAction.CASCADE)
    private @Nullable RepositoryToMonitor repositoryToMonitor;

    /** The provider that the listing read; with the native user id it names one identity. */
    @Column(name = "provider_id", nullable = false, updatable = false)
    private long providerId;

    /** The provider's native user id, such as GitHub's {@code databaseId}. */
    @Column(name = "reader_native_id", nullable = false, updatable = false)
    private long readerNativeId;

    protected ResourceReader() {}
}
