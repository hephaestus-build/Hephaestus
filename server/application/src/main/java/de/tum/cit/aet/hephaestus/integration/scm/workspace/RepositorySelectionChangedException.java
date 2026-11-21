package de.tum.cit.aet.hephaestus.integration.scm.workspace;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The connection, the project selection or the monitored projects changed while a request checked them. */
public final class RepositorySelectionChangedException extends ResponseStatusException {
    @Serial
    private static final long serialVersionUID = 1L;

    public RepositorySelectionChangedException() {
        super(
                HttpStatus.CONFLICT,
                "The connection, project selection or monitored projects changed while we checked them. Try again.");
    }
}
