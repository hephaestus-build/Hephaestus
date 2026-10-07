package de.tum.cit.aet.hephaestus.core.auth.webauthn;

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
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN,
                "This action requires passkey verification. Open your passkey settings to confirm access.");
        detail.setTitle("Confirm access with a passkey");
        detail.setProperty("code", "passkey_required");
        return detail;
    }
}
