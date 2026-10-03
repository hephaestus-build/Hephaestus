package de.tum.cit.aet.hephaestus.integration.scm.domain.common;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * Base class for all git service entities synced from external providers.
 * <p>
 * Provides:
 * <ul>
 *   <li>{@link #id} — Synthetic auto-generated primary key</li>
 *   <li>{@link #nativeId} — The provider's original numeric ID (always positive)</li>
 *   <li>{@link #provider} — FK to {@link IdentityProvider} identifying the provider instance</li>
 *   <li>{@link #createdAt} / {@link #updatedAt} — Audit timestamps from the provider</li>
 * </ul>
 * <p>
 * The combination of {@code (provider_id, native_id)} is unique per entity table,
 * scoping native IDs to prevent cross-provider collisions.
 */
@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor
@ToString
public abstract class BaseGitServiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    protected Long id;

    /**
     * The provider's original numeric ID (always stored as a positive value).
     * <p>
     * Combined with {@link #provider}, this uniquely identifies the entity
     * across all provider instances.
     */
    @Column(name = "native_id", nullable = false)
    protected Long nativeId;

    protected @Nullable Instant createdAt;

    protected @Nullable Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "provider_id", nullable = false)
    @ToString.Exclude
    protected IdentityProvider provider;

    /**
     * Same row: ids are only unique per table, so two entities are equal when they share an id and the
     * entity that owns the table. A lazy reference is a Hibernate proxy, so neither side's own class is
     * compared and the other side's id is read through its getter, which a proxy answers without loading.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BaseGitServiceEntity that)) return false;
        return id != null && id.equals(that.getId()) && tableEntity(this) == tableEntity(that);
    }

    @Override
    public int hashCode() {
        return tableEntity(this).hashCode();
    }

    /**
     * The entity whose table holds this row: the direct subclass of this one. A single-table subclass such as
     * {@code PullRequest} shares that table, and its ids, with its parent {@code Issue}. A proxy's class extends
     * the entity it stands for, so the walk never loads it, where {@code Hibernate.getClassLazy} loads a proxy
     * whose entity has subclasses to learn which one it is.
     */
    private static Class<?> tableEntity(BaseGitServiceEntity entity) {
        Class<?> type = entity.getClass();
        while (type.getSuperclass() != BaseGitServiceEntity.class) {
            type = type.getSuperclass();
        }
        return type;
    }
}
