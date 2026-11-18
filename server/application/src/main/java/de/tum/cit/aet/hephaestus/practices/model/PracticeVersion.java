package de.tum.cit.aet.hephaestus.practices.model;

import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
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
import java.util.Objects;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GeneratedColumn;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

@Entity
@Immutable
@Table(
        name = "practice_version",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_practice_version_workspace_id",
                    columnNames = {"id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_practice_version_owner",
                    columnNames = {"id", "practice_id", "workspace_id"}),
            @UniqueConstraint(
                    name = "uk_practice_version_number",
                    columnNames = {"practice_id", "number"})
        })
@Getter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PracticeVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private @Nullable Long id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private long workspaceId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "practice_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    @ToString.Exclude
    private Practice practice;

    @Column(nullable = false, updatable = false)
    private int number;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb", updatable = false)
    @ToString.Exclude
    @Getter(AccessLevel.NONE)
    private JsonNode definition;

    @GeneratedColumn("(definition ->> 'name')::varchar(128)")
    @Column(length = 128, insertable = false, updatable = false, options = "STORED")
    private @Nullable String name;

    @GeneratedColumn("practice_review_digest(definition)")
    @Column(
            name = "review_digest",
            length = 96,
            nullable = false,
            insertable = false,
            updatable = false,
            options = "STORED")
    @Getter(AccessLevel.NONE)
    private @Nullable String reviewDigest;

    @Column(name = "created_by_account_id", updatable = false)
    private @Nullable Long createdByAccountId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Getter(AccessLevel.NONE)
    private @Nullable Instant createdAt;

    public Instant getCreatedAt() {
        return Objects.requireNonNull(createdAt, "Persist the practice version before reading its creation time.");
    }

    /**
     * Whether this version was saved to be tried, beside the practice's current version. It never becomes current, and
     * history views leave it out (ADR 0118 decision 7).
     */
    @ColumnDefault("false")
    @Column(name = "tried", nullable = false, updatable = false)
    private boolean tried;

    /** The commit of the practice repository that the definition was read at; null for a version edited in Hephaestus. */
    @Column(name = "source_commit", length = 64, updatable = false)
    private @Nullable String sourceCommit;

    public String getReviewDigest() {
        return Objects.requireNonNull(
                reviewDigest, "Persist the practice version before reading its generated digest.");
    }

    public JsonNode getDefinition() {
        return definition.deepCopy();
    }

    public PracticeVersion(Practice practice, int number, JsonNode definition, @Nullable Long createdByAccountId) {
        this(practice, number, definition, createdByAccountId, false);
    }

    public PracticeVersion(
            Practice practice, int number, JsonNode definition, @Nullable Long createdByAccountId, boolean tried) {
        this(practice, number, definition, createdByAccountId, tried, null);
    }

    public PracticeVersion(
            Practice practice,
            int number,
            JsonNode definition,
            @Nullable Long createdByAccountId,
            boolean tried,
            @Nullable String sourceCommit) {
        this.tried = tried;
        this.sourceCommit = sourceCommit;
        this.practice = Objects.requireNonNull(practice);
        this.workspaceId = Objects.requireNonNull(practice.getWorkspace().getId());
        if (number < 1 || !definition.isObject()) {
            throw new IllegalArgumentException("A practice version needs a positive number and a definition.");
        }
        this.number = number;
        this.definition = definition.deepCopy();
        this.createdByAccountId = createdByAccountId;
    }
}
