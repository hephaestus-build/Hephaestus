package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.agent.catalog.ModelBindingSource;
import de.tum.cit.aet.hephaestus.agent.catalog.WorkspaceLlmModel;
import de.tum.cit.aet.hephaestus.agent.usage.FundingSource;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * What model, and with what execution limits, a workspace runs a given {@link AgentPurpose} on.
 *
 * <p>Routing and credentials live in the selected catalog model. Exactly one of
 * {@link #instanceModel} / {@link #workspaceModel} is set ({@code ck_workspace_agent_binding_single_model}
 * enforces it); no row for a purpose and tier means that slot is unconfigured (off).
 */
@Entity
@Table(
        name = "workspace_agent_binding",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_workspace_agent_binding_purpose_tier",
                        columnNames = {"workspace_id", "purpose", "data_handling_tier"}))
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class WorkspaceAgentBinding implements ModelBindingSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "workspace_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_workspace_agent_binding_workspace"))
    @ToString.Exclude
    private Workspace workspace;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private AgentPurpose purpose;

    /**
     * The slot this row fills: the bound model's derived tier must equal it, except that the
     * {@code UNDECLARED} slot accepts any model. {@code UNDECLARED} is the legacy slot for members with
     * no choice, never a fallback for a member who chose.
     */
    @Enumerated(EnumType.STRING)
    @ColumnDefault("'UNDECLARED'")
    @Column(name = "data_handling_tier", nullable = false, length = 24)
    @Getter(onMethod_ = @Override)
    private DataHandlingTier dataHandlingTier = DataHandlingTier.UNDECLARED;

    @ColumnDefault("true")
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @ColumnDefault("10800")
    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds = AgentBindingLimits.MAX_TIMEOUT_SECONDS;

    @ColumnDefault("3")
    @Column(name = "max_concurrent_jobs", nullable = false)
    private int maxConcurrentJobs = 3;

    @ColumnDefault("false")
    @Column(name = "allow_internet", nullable = false)
    private boolean allowInternet = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "instance_model_id",
            foreignKey = @ForeignKey(name = "fk_workspace_agent_binding_instance_model"))
    @ToString.Exclude
    private @Nullable LlmModel instanceModel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
            name = "workspace_model_id",
            foreignKey = @ForeignKey(name = "fk_workspace_agent_binding_workspace_model"))
    @ToString.Exclude
    private @Nullable WorkspaceLlmModel workspaceModel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    /**
     * Who pays for work run on this binding. Safe on a detached binding: null-testing a lazy
     * {@code @ManyToOne} reads the foreign key Hibernate already holds and never touches the session.
     */
    public FundingSource getFundingSource() {
        return instanceModel != null ? FundingSource.INSTANCE : FundingSource.WORKSPACE;
    }

    @PrePersist
    public void prePersist() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
