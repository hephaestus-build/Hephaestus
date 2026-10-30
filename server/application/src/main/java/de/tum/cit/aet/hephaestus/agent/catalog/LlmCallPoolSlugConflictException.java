package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LlmCallPoolSlugConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmCallPoolSlugConflictException(String slug, @Nullable Throwable cause) {
        super("A pool with slug “" + slug + "” already exists.", cause);
    }
}
