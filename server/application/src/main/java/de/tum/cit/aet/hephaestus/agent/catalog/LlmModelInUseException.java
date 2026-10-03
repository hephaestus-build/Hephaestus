package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LlmModelInUseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmModelInUseException(Long modelId) {
        super("Cannot delete LLM model " + modelId + ". One or more agent configurations still use it.");
    }
}
