package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LlmConnectionSlugConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmConnectionSlugConflictException(String slug) {
        this(slug, null);
    }

    public LlmConnectionSlugConflictException(String slug, @Nullable Throwable cause) {
        super("An LLM connection with slug “" + slug + "” already exists.", cause);
    }
}
