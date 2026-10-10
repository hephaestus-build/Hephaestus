package de.tum.cit.aet.hephaestus.activity;

import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serial;
import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jspecify.annotations.Nullable;

/** A workspace's classification of a machine user; it grants no workspace access. */
@Entity
@Table(name = "activity_automation")
@Getter
@Setter
@NoArgsConstructor
public class ActivityAutomation {
    @EmbeddedId
    private Id id = new Id();

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("workspaceId")
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_activity_automation_workspace"))
    private Workspace workspace;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", nullable = false, foreignKey = @ForeignKey(name = "fk_activity_automation_user"))
    private User user;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private @Nullable Long workspaceId;
        private @Nullable Long userId;
    }
}
