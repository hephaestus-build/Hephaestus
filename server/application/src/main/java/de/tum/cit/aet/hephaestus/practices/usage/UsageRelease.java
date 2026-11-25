package de.tum.cit.aet.hephaestus.practices.usage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One released half-year of the public usage page (ADR 0153 decision 16). Insert-only: {@link UsageReleaseRepository}
 * writes a row once, and the database refuses to change or delete it. It holds only figures that passed every
 * disclosure rule, never a raw count. Global, listed in {@code WorkspaceScopedTables.GLOBAL_TABLES}: it names no
 * workspace.
 */
@Entity
@Immutable
@Table(name = "usage_release")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UsageRelease {

    /** The first day of the half-year, 1 April or 1 October. */
    @Id
    @Column(name = "period_start", nullable = false, updatable = false)
    private LocalDate periodStart;

    /** The first day after the half-year. */
    @Column(name = "period_end", nullable = false, updatable = false)
    private LocalDate periodEnd;

    /** When the server computed and released the period. */
    @Column(name = "computed_at", nullable = false, updatable = false)
    private Instant computedAt;

    /** The version of {@link #figures}; a reader that does not know it publishes nothing. */
    @Column(name = "schema_version", nullable = false, updatable = false)
    private short schemaVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "figures", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String figures;
}
