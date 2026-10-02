package de.tum.cit.aet.hephaestus.core.exception;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class EntityNotFoundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entityName, Long entityId) {
        super(entityName + " with id: \"" + entityId + "\" does not exist");
    }

    public EntityNotFoundException(String entityName, String entityIdentifier) {
        super(entityName + " with identifier: \"" + entityIdentifier + "\" does not exist");
    }

    /** For a lookup whose input must not be echoed back, such as a caller-supplied address. */
    public EntityNotFoundException(String message) {
        super(message);
    }
}
