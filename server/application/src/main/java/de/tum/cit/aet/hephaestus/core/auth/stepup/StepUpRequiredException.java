package de.tum.cit.aet.hephaestus.core.auth.stepup;

import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Asks the caller to confirm access by signing in again.
 *
 * <p>Deliberately not the RFC 9470 shape (401 plus {@code WWW-Authenticate: Bearer
 * error="insufficient_user_authentication", max_age=…}): the caller is a same-origin SPA holding a
 * cookie session, not a bearer client that could act on the challenge header, and a 401 would send the
 * SPA's interceptor down the sign-out path instead of the confirmation dialog. The machine-readable
 * {@code code} and {@code maxAgeSeconds} carry what the header would have.
 */
public class StepUpRequiredException extends ErrorResponseException {
    public static final String CODE = "step_up_required";

    public StepUpRequiredException(Duration maxAge) {
        super(HttpStatus.FORBIDDEN, problem(maxAge), null);
    }

    private static ProblemDetail problem(Duration maxAge) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "This action requires a recent sign-in. Confirm access by signing in again.");
        problem.setTitle("Confirm access");
        problem.setProperty("code", CODE);
        problem.setProperty("maxAgeSeconds", maxAge.toSeconds());
        return problem;
    }
}
