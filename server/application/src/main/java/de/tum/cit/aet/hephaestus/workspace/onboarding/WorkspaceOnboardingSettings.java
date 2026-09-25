package de.tum.cit.aet.hephaestus.workspace.onboarding;

import de.tum.cit.aet.hephaestus.workspace.Workspace;
import jakarta.persistence.*;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Optional first-visit setup; it never creates or revokes membership. */
@Entity
@Table(name = "workspace_onboarding_settings")
@Getter
@Setter
class WorkspaceOnboardingSettings {
    @Id
    @Column(name = "workspace_id")
    private Long workspaceId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_onboarding_settings_workspace"))
    private Workspace workspace;

    @ColumnDefault("false")
    @Column(nullable = false)
    private boolean enabled = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_connection_ids", nullable = false, columnDefinition = "jsonb")
    private List<Long> requiredConnectionIds = List.of();
    /** Once enabled, hiding the setup page never silently restores unrestricted AI processing. */
    @ColumnDefault("false")
    @Column(name = "ai_choice_required", nullable = false)
    private boolean aiChoiceRequired = false;

    @Version
    @ColumnDefault("0")
    @Column(nullable = false)
    private long revision = 0;
}
