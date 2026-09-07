package de.tum.cit.aet.hephaestus.achievement;

import de.tum.cit.aet.hephaestus.achievement.progress.AchievementProgress;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(
        name = "user_achievement",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_user_achievement_user_achievement",
                    columnNames = {"user_id", "achievement_id"}),
        },
        indexes = {
            @Index(name = "idx_user_achievement_user", columnList = "user_id"),
            @Index(name = "idx_user_achievement_achievement", columnList = "achievement_id"),
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAchievement {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @NotNull
    @Column(name = "achievement_id", nullable = false, length = 64)
    private String achievementId;

    /** Null for progress that never unlocked the achievement. */
    @Nullable
    @Column(name = "unlocked_at")
    private Instant unlockedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "progress_data", columnDefinition = "jsonb", nullable = false)
    private AchievementProgress progressData;

    @Version
    private Long version;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
