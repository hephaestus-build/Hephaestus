package de.tum.cit.aet.hephaestus.agent.config;

import de.tum.cit.aet.hephaestus.agent.catalog.LlmModel;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * The instance model that personal conversations of one data-handling tier run on: conversations of an account with no
 * workspace, paid from the instance's personal purse. At most one row for each of {@code IN_HOUSE} and {@code CLOUD};
 * no row leaves that tier unconfigured. {@code UNDECLARED} is never a personal tier.
 */
@Entity
@Table(name = "personal_model_assignment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PersonalModelAssignment {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 16, updatable = false)
    private DataHandlingTier tier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "llm_model_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_personal_model_assignment_model"))
    @ToString.Exclude
    private LlmModel model;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    PersonalModelAssignment(DataHandlingTier tier, LlmModel model, Instant now) {
        if (tier == DataHandlingTier.UNDECLARED) {
            throw new IllegalArgumentException("A personal conversation never runs on an undeclared model");
        }
        this.tier = tier;
        this.model = model;
        this.changedAt = now;
    }

    void reassign(LlmModel model, Instant now) {
        this.model = model;
        this.changedAt = now;
    }
}
