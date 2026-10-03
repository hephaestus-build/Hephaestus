package de.tum.cit.aet.hephaestus.integration.scm.github.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.LocalDate;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Represents a field value for an item in a GitHub Projects V2 project.
 * <p>
 * Each item can have values for multiple fields. The actual value is stored
 * in the appropriate column based on the field's data type:
 * - TEXT: textValue
 * - NUMBER: numberValue
 * - DATE: dateValue
 * - SINGLE_SELECT: singleSelectOptionId
 * - ITERATION: iterationId
 * <p>
 * Uses auto-generated ID since GitHub doesn't provide a unique ID for field values.
 */
@Entity
@Table(
        name = "project_field_value",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_project_field_value_item_field",
                    columnNames = {"item_id", "field_id"}),
        })
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ProjectFieldValue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /**
     * The project item this value belongs to.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    @ToString.Exclude
    private @Nullable ProjectItem item;

    /**
     * The field definition this value is for.
     */
    @NonNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "field_id", nullable = false)
    @ToString.Exclude
    private ProjectField field;

    /**
     * Text value for TEXT fields.
     */
    @Column(name = "text_value", columnDefinition = "TEXT")
    private String textValue;

    /**
     * Number value for NUMBER fields.
     */
    @Column(name = "number_value")
    private Double numberValue;

    /**
     * Date value for DATE fields.
     */
    @Column(name = "date_value")
    private LocalDate dateValue;

    /**
     * The selected option ID for SINGLE_SELECT fields.
     * References an option ID in the field's options JSON.
     */
    @Column(name = "single_select_option_id", length = 64)
    private String singleSelectOptionId;

    /**
     * The iteration ID for ITERATION fields.
     * References an iteration ID in the field's options JSON.
     */
    @Column(name = "iteration_id", length = 64)
    private String iterationId;

    /**
     * When this value was last updated.
     */
    private Instant updatedAt;
}
