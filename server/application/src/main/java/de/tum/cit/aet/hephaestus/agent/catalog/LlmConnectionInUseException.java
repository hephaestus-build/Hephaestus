package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LlmConnectionInUseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmConnectionInUseException(Long connectionId) {
        super("You cannot delete LLM connection " + connectionId + " because one or more models still use it.");
    }
}
