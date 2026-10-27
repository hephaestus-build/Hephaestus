package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = IntroducedBasis.class, name = "introduced"),
    @JsonSubTypes.Type(value = WorsenedBasis.class, name = "worsened"),
    @JsonSubTypes.Type(value = DutyBasis.class, name = "duty"),
    @JsonSubTypes.Type(value = StateBasis.class, name = "state")
})
public sealed interface ClaimBasis permits IntroducedBasis, WorsenedBasis, DutyBasis, StateBasis {
    /** Why the problem belongs to this work, in the reviewer's words. */
    String explanation();
}
