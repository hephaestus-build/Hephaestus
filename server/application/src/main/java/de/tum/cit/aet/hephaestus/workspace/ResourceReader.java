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
 * readers showed. A monitor holds readers only while its visibility class is {@code RESTRICTED}: the listing that
 * proves the class writes them in the same transaction, and every answer that ends that proof removes them. A removal
 * on the provider removes the person's rows through database triggers on the membership mirror tables.
 *
 * <p>The reader is the provider's native user id, not an account: a check maps an account to its provider identity, so a
 * login that the provider gives to someone else later never names this reader.
 */
@Entity
@Table(
        name = "resource_reader",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_resource_reader_monitor_reader",
                        columnNames = {"repository_to_monitor_id", "reader_native_id"}),
        indexes = @Index(name = "idx_resource_reader_reader_native_id", columnList = "reader_native_id"))
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

    /** The provider's native user id, such as GitHub's {@code databaseId}. */
    @Column(name = "reader_native_id", nullable = false, updatable = false)
    private long readerNativeId;

    protected ResourceReader() {}
}
