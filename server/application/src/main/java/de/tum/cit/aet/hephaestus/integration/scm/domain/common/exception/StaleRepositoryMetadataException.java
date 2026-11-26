package de.tum.cit.aet.hephaestus.integration.scm.domain.common.exception;

import java.io.Serial;

/** The monitor changed while the provider read was in flight. */
public class StaleRepositoryMetadataException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
}
