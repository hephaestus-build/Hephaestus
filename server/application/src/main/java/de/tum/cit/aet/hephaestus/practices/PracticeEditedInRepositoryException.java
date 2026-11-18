package de.tum.cit.aet.hephaestus.practices;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A change to a practice whose definition and group its folder in the practice repository owns (ADR 0118). */
public final class PracticeEditedInRepositoryException extends ResponseStatusException {
    @Serial
    private static final long serialVersionUID = 1L;

    PracticeEditedInRepositoryException() {
        super(
                HttpStatus.CONFLICT,
                "This practice is edited in the practice repository. Change its folder there, or choose Edit in"
                        + " Hephaestus first.");
    }
}
