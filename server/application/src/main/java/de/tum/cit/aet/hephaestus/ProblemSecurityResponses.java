package de.tum.cit.aet.hephaestus;

import de.tum.cit.aet.hephaestus.core.web.ProblemResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * The resource server's refusals before a controller runs. The bearer handlers set the status and the RFC 6750
 * {@code WWW-Authenticate} challenge but write no body. Every error answer of the API is a problem, so this adds one.
 */
final class ProblemSecurityResponses implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final AuthenticationEntryPoint challenge = new BearerTokenAuthenticationEntryPoint();
    private final AccessDeniedHandler denial = new BearerTokenAccessDeniedHandler();

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException, ServletException {
        challenge.commence(request, response, exception);
        write(response);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException, ServletException {
        denial.handle(request, response, exception);
        write(response);
    }

    /** The bearer handler chose the status from the token error: 401 for a missing or expired token, else 400 or 403. */
    private void write(HttpServletResponse response) throws IOException {
        HttpStatus status = HttpStatus.valueOf(response.getStatus());
        String detail = switch (status) {
            case UNAUTHORIZED -> "Sign in, then try again.";
            case FORBIDDEN -> "Your account does not have access to this.";
            default -> "The request's credentials are not valid. Sign in again.";
        };
        ProblemResponse.write(response, ProblemDetail.forStatusAndDetail(status, detail));
    }
}
