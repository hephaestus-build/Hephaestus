package de.tum.cit.aet.hephaestus.core.exception;

import java.io.Serial;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * A request that must start work at once arrived while a database migration runs, and the maintenance barrier refused
 * it ({@code core.MaintenanceBarrier}). The caller did nothing wrong and can send the same request again later.
 */
public final class MaintenanceInProgressException extends ErrorResponseException {
    @Serial
    private static final long serialVersionUID = 1L;

    /** A migration usually ends within minutes. */
    static final String RETRY_AFTER_SECONDS = "60";

    public MaintenanceInProgressException() {
        super(
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Hephaestus is updating its database. Nothing was done. Try again in a few minutes."),
                null);
        getBody().setTitle("Hephaestus is updating");
        getHeaders().set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    }
}
