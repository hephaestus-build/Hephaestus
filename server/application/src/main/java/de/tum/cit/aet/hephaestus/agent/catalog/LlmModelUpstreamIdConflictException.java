package de.tum.cit.aet.hephaestus.agent.catalog;

import java.io.Serial;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Two catalog entries pointing at the same upstream id would be matched nondeterministically when
 * usage is priced, letting a NO_CHARGE sibling silently shadow a PRICED one for billing.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class LlmModelUpstreamIdConflictException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public LlmModelUpstreamIdConflictException(Long connectionId, String upstreamModelId) {
        this(connectionId, upstreamModelId, null);
    }

    public LlmModelUpstreamIdConflictException(Long connectionId, String upstreamModelId, @Nullable Throwable cause) {
        super(
                "A model with upstream id “" + upstreamModelId + "” already exists on connection " + connectionId + ".",
                cause);
    }
}
