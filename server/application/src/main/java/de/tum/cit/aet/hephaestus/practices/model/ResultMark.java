package de.tum.cit.aet.hephaestus.practices.model;

import de.tum.cit.aet.hephaestus.practices.spi.RawObservationAccess;
import de.tum.cit.aet.hephaestus.practices.spi.ResultMarkKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

@Entity
@RawObservationAccess
@Table(
        name = "result_mark",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_result_mark_workspace",
                    columnNames = {"id", "workspace_id"})
        })
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResultMark {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private long workspaceId;

    @Column(name = "result_id", nullable = false)
    private UUID resultId;

    @Default
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'INCORRECT'")
    @Column(name = "kind", nullable = false, length = 16)
    private ResultMarkKind kind = ResultMarkKind.INCORRECT;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "created_by_id")
    private @Nullable Long createdById;

    @ColumnDefault("now()")
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "removed_by_id")
    private @Nullable Long removedById;

    @Column(name = "removed_at")
    private @Nullable Instant removedAt;

    @Column(name = "removal_reason", length = 500)
    private @Nullable String removalReason;

    public void remove(long actorId, String reason, Instant at) {
        if (removedAt != null) {
            return;
        }
        if (reason.isBlank() || reason.length() > 500 || at.isBefore(createdAt)) {
            throw new IllegalArgumentException("Invalid mark removal");
        }
        removedById = actorId;
        removalReason = reason.strip();
        removedAt = at;
    }
}
