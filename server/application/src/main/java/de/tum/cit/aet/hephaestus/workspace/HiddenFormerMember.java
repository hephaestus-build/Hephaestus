package de.tum.cit.aet.hephaestus.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * A member who was hidden from the leaderboard when the provider's roster stopped granting them the workspace.
 * Hiding is a preference, not access, so the membership goes; this row keeps the preference until a membership
 * for the same actor is created again, which takes it back.
 */
@Entity
@Table(name = "workspace_hidden_former_member")
@IdClass(HiddenFormerMember.Key.class)
@Getter
@NoArgsConstructor
public class HiddenFormerMember {

    @Id
    @Column(name = "workspace_id", nullable = false)
    private Long workspaceId;

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    public HiddenFormerMember(Long workspaceId, Long userId) {
        this.workspaceId = workspaceId;
        this.userId = userId;
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Nullable
        private Long workspaceId;

        @Nullable
        private Long userId;
    }
}
