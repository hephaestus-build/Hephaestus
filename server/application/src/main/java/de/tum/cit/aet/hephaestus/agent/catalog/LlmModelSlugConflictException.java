package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LlmModelSlugConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmModelSlugConflictException(Long connectionId, String slug) {
        this(connectionId, slug, null);
    }

    public LlmModelSlugConflictException(Long connectionId, String slug, @Nullable Throwable cause) {
        super("A model with slug “" + slug + "” already exists on connection " + connectionId + ".", cause);
    }
}
