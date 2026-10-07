package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import com.webauthn4j.converter.exception.DataConversionException;
import com.webauthn4j.verifier.exception.VerificationException;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEvent;
import de.tum.cit.aet.hephaestus.core.auth.audit.AuthEventLogger;
import de.tum.cit.aet.hephaestus.core.auth.web.CurrentAccount;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;

/** Invalid authenticator input never exposes credentials or library exception details. */
@RestControllerAdvice(assignableTypes = PasskeyController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnServerRole
@RequiredArgsConstructor
public class PasskeyExceptionHandler {
    private final AuthEventLogger audit;

    @ExceptionHandler({
        DataConversionException.class,
        VerificationException.class,
        JacksonException.class,
        IllegalArgumentException.class
    })
    public ProblemDetail invalidCredential() {
        audit.event(AuthEvent.EventType.PASSKEY_VERIFIED, AuthEvent.Result.FAILURE)
                .account(CurrentAccount.requireId())
                .failureReason("invalid_passkey_response")
                .record();
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The passkey response is not valid. Start again.");
    }
}
