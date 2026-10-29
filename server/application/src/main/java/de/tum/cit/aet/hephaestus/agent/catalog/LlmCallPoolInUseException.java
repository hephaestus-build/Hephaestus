package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class LlmCallPoolInUseException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmCallPoolInUseException(Long poolId) {
        super("Wait for call pool " + poolId + " to finish its calls, rate window, and cooldown before changing it.");
    }
}
