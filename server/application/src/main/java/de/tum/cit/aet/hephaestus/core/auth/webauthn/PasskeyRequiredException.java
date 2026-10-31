package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.exception.ProblemType;
import java.io.Serial;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class PasskeyRequiredException extends ErrorResponseException {
    @Serial
    private static final long serialVersionUID = 1L;

    public PasskeyRequiredException() {
        super(HttpStatus.FORBIDDEN, problem(), null);
    }

    private static ProblemDetail problem() {
        return ProblemType.PASSKEY_REQUIRED.problem(
                HttpStatus.FORBIDDEN,
                "Confirm access with a passkey",
                "This action requires passkey verification. Open your passkey settings to confirm access.");
    }
}
