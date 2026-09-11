package de.tum.cit.aet.hephaestus.productfeedback;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A product survey published by an instance administrator. The questions are frozen at publication
 * so every stored answer keeps its meaning; the title, purpose, schedule and pause flag stay editable
 * because they change who is invited, never what an answer meant.
 */
@Entity
@Table(name = "product_survey")
@Getter
@NoArgsConstructor
public class Survey {
    @Id
    private UUID id = UUID.randomUUID();

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "questions_json", nullable = false, columnDefinition = "jsonb")
    private JsonNode questions;

    @Column(name = "workspace_id")
    private @Nullable Long workspaceId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private @Nullable Instant endsAt;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by_account_id")
    private @Nullable Long createdByAccountId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    public Survey(
            String title,
            String description,
            JsonNode questions,
            @Nullable Long workspaceId,
            Instant startsAt,
            @Nullable Instant endsAt,
            Long createdByAccountId) {
        this.title = title;
        this.description = description;
        this.questions = questions;
        this.workspaceId = workspaceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = true;
        this.createdByAccountId = createdByAccountId;
    }

    public void edit(String title, String description, Instant startsAt, @Nullable Instant endsAt, boolean active) {
        this.title = title;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.active = active;
    }

    /** Whether the survey accepts responses from {@code workspaceId} at {@code now}. */
    public boolean isOpenFor(Long workspaceId, Instant now) {
        return active
                && !startsAt.isAfter(now)
                && (endsAt == null || endsAt.isAfter(now))
                && (this.workspaceId == null || this.workspaceId.equals(workspaceId));
    }
}
