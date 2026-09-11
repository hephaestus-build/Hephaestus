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
 * The two facts an admin declares about a model's data handling, plus an admin-only note. Declaring
 * both is also the admin's assertion that the model's terms rule out training on what it receives.
 * {@link #tier()} is the only place the facts turn into a {@link DataHandlingTier}.
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
    @Enumerated(EnumType.STRING)
    @Column(name = "kept_after_reply", length = 24)
    private LlmDataRetention keptAfterReply;

    @Nullable
    @Column(name = "data_handling_note", length = 200)
    private String note;

    /** The admin's declaration as sent over the wire: both facts, or neither for an undeclared model. */
    public static DataHandlingFacts of(
            @Nullable LlmDataOperator operatedBy, @Nullable LlmDataRetention keptAfterReply, @Nullable String note) {
        if ((operatedBy == null) != (keptAfterReply == null)) {
            throw new IllegalArgumentException("Declare both facts or neither");
        }
        DataHandlingFacts facts = new DataHandlingFacts();
        facts.setOperatedBy(operatedBy);
        facts.setKeptAfterReply(keptAfterReply);
        facts.setNote(note != null && note.isBlank() ? null : note);
        return facts;
    }

    public DataHandlingTier tier() {
        if (operatedBy == null || keptAfterReply == null) {
            return DataHandlingTier.UNDECLARED;
        }
        if (operatedBy == LlmDataOperator.OWN_ORGANISATION) {
            return DataHandlingTier.IN_HOUSE;
        }
        return keptAfterReply == LlmDataRetention.NONE
                ? DataHandlingTier.PROVIDER_NOT_KEPT
                : DataHandlingTier.PROVIDER_KEPT;
    }
}
