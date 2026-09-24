package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * The operator an admin declares for a model, plus an admin-only note. This does not assert
 * provider training or retention terms. {@link #tier()} maps the declaration to a routing tier.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class DataHandlingFacts {

    @Nullable
    @Enumerated(EnumType.STRING)
    @Column(name = "operated_by", length = 24)
    private LlmDataOperator operatedBy;

    @Nullable
    @Column(name = "data_handling_note", length = 200)
    private String note;

    /** The admin's declaration as sent over the wire: the fact, or nothing for an undeclared model. */
    public static DataHandlingFacts of(@Nullable LlmDataOperator operatedBy, @Nullable String note) {
        DataHandlingFacts facts = new DataHandlingFacts();
        facts.setOperatedBy(operatedBy);
        facts.setNote(note != null && note.isBlank() ? null : note);
        return facts;
    }

    public DataHandlingTier tier() {
        if (operatedBy == null) {
            return DataHandlingTier.UNDECLARED;
        }
        return operatedBy == LlmDataOperator.OWN_ORGANISATION ? DataHandlingTier.IN_HOUSE : DataHandlingTier.CLOUD;
    }
}
