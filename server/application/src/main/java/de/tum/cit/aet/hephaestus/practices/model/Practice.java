package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.practices.catalog.CatalogPracticeDocument;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "practice",
        indexes = {
            @Index(name = "idx_practice_group_order", columnList = "workspace_id, group_id, position, id"),
            @Index(name = "idx_practice_kind", columnList = "workspace_id, kind")
        },
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_workspace_id",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_practice_workspace_slug",
                    columnNames = {"workspace_id", "slug"})
        })
// Every session reads active practices only. Code that must see drafts loads them by id, or turns the filter off for
// its own session (PracticeDrafts), so a draft never reaches selection, a member or a review (ADR 0118 decision 7).
@FilterDef(name = Practice.ACTIVE, defaultCondition = "state = 'ACTIVE'", autoEnabled = true)
@Filter(name = Practice.ACTIVE)
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Practice {
    /** The name of the filter that hides draft practices from every session that does not turn it off. */
    public static final String ACTIVE = "activePractices";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, foreignKey = @ForeignKey(name = "fk_practice_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @Column(nullable = false, length = 64, updatable = false)
    private String slug;

    @Column(nullable = false, length = 64, updatable = false)
    private ItemKind kind;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @ToString.Exclude
    private @Nullable PracticeGroup group;

    @Column(nullable = false)
    @ColumnDefault("0")
    private int position;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "current_version_id", nullable = false, foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @ToString.Exclude
    private PracticeVersion currentVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_mode", length = 16)
    private @Nullable FeedbackMode feedbackMode;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'ACTIVE'")
    @Column(name = "state", nullable = false, length = 8)
    @Setter(AccessLevel.NONE)
    private PracticeState state = PracticeState.ACTIVE;

    @Column(name = "catalog_slug", length = 64)
    private @Nullable String catalogSlug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "catalog_base", columnDefinition = "jsonb")
    @ToString.Exclude
    private @Nullable CatalogPracticeDocument catalogBase;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant createdAt;

    @CurrentTimestamp(event = EventType.UPDATE, source = SourceType.VM)
    @Column(name = "updated_at")
    private @Nullable Instant updatedAt;

    /** Puts a draft practice in use. A practice in use never becomes a draft again (ADR 0118 decision 7). */
    public void activate() {
        if (!state.canTransitionTo(PracticeState.ACTIVE))
            throw new IllegalStateException("Only a draft practice can be put in use.");
        state = PracticeState.ACTIVE;
    }
}
