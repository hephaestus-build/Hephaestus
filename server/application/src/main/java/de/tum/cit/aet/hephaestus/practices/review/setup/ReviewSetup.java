package de.tum.cit.aet.hephaestus.practices.review.setup;

import de.tum.cit.aet.hephaestus.practices.spi.ReviewAudience;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewCadence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A named choice of practices for one kind of item and one audience. It holds configuration and no person. Its audience
 * and kind never change: another audience is another setup, and each review keeps a copy of the setup it used.
 */
@Entity
@Table(
        name = "review_setup",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_review_setup_workspace",
                        columnNames = {"id", "workspace_id"}),
        indexes = @Index(name = "idx_review_setup_workspace", columnList = "workspace_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewSetup {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 1000)
    private @Nullable String description;

    /** The registered item kind that the setup reviews, such as a team board or a repository. */
    @Column(name = "item_kind", nullable = false, length = 64)
    private String itemKind;

    @Enumerated(EnumType.STRING)
    @Column(name = "audience", nullable = false, length = 16)
    private ReviewAudience audience;

    /** Which part of the item a review reads, for a kind that is read in parts. A submitted version is read whole. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scope", columnDefinition = "jsonb")
    private @Nullable JsonNode scope;

    /** The named evidence a review asks for, as {@code [{"key", "label", "required", "role"}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_slots", nullable = false, columnDefinition = "jsonb")
    private JsonNode evidenceSlots;

    /** Absent for a setup that only a request starts. */
    @Enumerated(EnumType.STRING)
    @Column(name = "cadence", length = 16)
    private @Nullable ReviewCadence cadence;

    @Column(name = "budget_usd", precision = 18, scale = 6)
    private @Nullable BigDecimal budgetUsd;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;
}
