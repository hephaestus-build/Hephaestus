package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;
import org.jspecify.annotations.Nullable;

/**
 * Instance-wide LLM settings: the egress allowlist, the workspace-provider-connection flag and the two monthly caps of
 * the personal purse, which pays for personal conversations of accounts with no workspace.
 * {@code id} is fixed to 1 (a DB CHECK enforces the singleton) and never generated — the changelog
 * seeds the row.
 */
@Entity
@Table(name = "instance_llm_settings")
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class InstanceLlmSettings {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false)
    private Short id;

    /** Comma/newline-delimited; empty or null allows any public host. */
    @Nullable
    @Column(name = "allowed_egress_hosts", columnDefinition = "TEXT")
    private String allowedEgressHosts;

    @ColumnDefault("true")
    @Column(name = "allow_workspace_connections", nullable = false)
    private boolean allowWorkspaceConnections = true;

    /**
     * What all personal conversations together may spend in one UTC month, in USD. {@code null} leaves personal AI
     * unconfigured, so it is off; {@code 0} pauses it.
     */
    @Nullable
    @Column(name = "personal_monthly_budget_usd", precision = 10, scale = 2)
    private BigDecimal personalMonthlyBudgetUsd;

    /** What one account's personal conversations may spend of that purse in one UTC month, in USD. Same rules. */
    @Nullable
    @Column(name = "personal_account_monthly_budget_usd", precision = 10, scale = 2)
    private BigDecimal personalAccountMonthlyBudgetUsd;

    @Nullable
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Nullable
    @Column(name = "updated_by_account_id")
    private Long updatedByAccountId;
}
