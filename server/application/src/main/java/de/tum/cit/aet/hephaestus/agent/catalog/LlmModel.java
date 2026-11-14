package de.tum.cit.aet.hephaestus.agent.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.ColumnDefault;

/**
 * Instance-curated model behind a {@link LlmConnection}: an opaque upstream id plus capability
 * and visibility metadata. GLOBAL (not tenant-scoped).
 */
@Entity
@Table(
        name = "llm_model",
        uniqueConstraints = {
            // Named here so the ddl-auto test schema matches the changelog's: the create path catches this
            // constraint by name.
            @UniqueConstraint(
                    name = "ux_llm_model_connection_upstream",
                    columnNames = {"connection_id", "upstream_model_id"}),
            @UniqueConstraint(
                    name = "ux_llm_model_connection_slug",
                    columnNames = {"connection_id", "slug"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LlmModel extends LlmCatalogModel {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connection_id", nullable = false, foreignKey = @ForeignKey(name = "fk_llm_model_connection"))
    @ToString.Exclude
    private LlmConnection connection;

    @ColumnDefault("'GRANTED'")
    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 16)
    private ModelVisibility visibility = ModelVisibility.GRANTED;
}
