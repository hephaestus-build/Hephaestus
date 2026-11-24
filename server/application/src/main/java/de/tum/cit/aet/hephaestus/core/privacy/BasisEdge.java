package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.BasisKind;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonOwned;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * A provenance reference: a derived record of one person read one source (ADR 0128). It holds ids, never the words of
 * the source. The {@code BasisInvalidation} port writes and reads it: the row belongs to the owner that the transaction binds.
 */
@Entity
@PersonOwned
@Table(
        name = "basis_edge",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_basis_edge_dependent_source",
                        columnNames = {"dependent_kind", "dependent_id", "source_kind", "source_id"}),
        indexes = @Index(name = "idx_basis_edge_account", columnList = "account_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BasisEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private @Nullable Long id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    /** The owner of the derived record. */
    @Column(name = "account_id", nullable = false)
    private long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dependent_kind", nullable = false, length = 16)
    private BasisKind dependentKind;

    @Column(name = "dependent_id", nullable = false)
    private UUID dependentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_kind", nullable = false, length = 16)
    private BasisKind sourceKind;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("now()")
    private Instant createdAt;
}
