package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.GroupDefinition;
import de.tum.cit.aet.hephaestus.practices.spi.FeedbackMode;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "practice_group",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_group_workspace_id",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_practice_group_workspace_slug",
                    columnNames = {"workspace_id", "slug"})
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PracticeGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false, foreignKey = @ForeignKey(name = "fk_practice_group_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @Column(nullable = false, length = 64, updatable = false)
    private String slug;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "text")
    private @Nullable String description;

    @Column(length = 64)
    private @Nullable String icon;

    @Column(length = 32)
    private @Nullable String color;

    @Column(nullable = false)
    @ColumnDefault("0")
    private int position;

    @Column(name = "shown_to_people", nullable = false)
    @ColumnDefault("true")
    private boolean shownToPeople = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "feedback_mode", length = 16)
    private @Nullable FeedbackMode feedbackMode;

    @Column(name = "catalog_slug", length = 64)
    private @Nullable String catalogSlug;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "catalog_base", columnDefinition = "jsonb")
    private @Nullable GroupDefinition catalogBase;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    private Instant createdAt;

    @CurrentTimestamp(event = EventType.UPDATE, source = SourceType.VM)
    @Column(name = "updated_at")
    private @Nullable Instant updatedAt;
}
