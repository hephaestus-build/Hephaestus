package de.tum.cit.aet.hephaestus.practices.spi;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@RawObservationAccess
public record ResultExample(EvidenceCitation citation, String note) {}
