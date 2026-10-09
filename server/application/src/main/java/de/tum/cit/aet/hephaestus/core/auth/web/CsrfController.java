package de.tum.cit.aet.hephaestus.core.auth.web;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.NonNull;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnServerRole
@Tag(name = "Auth", description = "Session lifecycle")
public class CsrfController {
    public record CsrfTokenDTO(
            @NonNull String token, @NonNull String headerName) {}

    @GetMapping("/auth/csrf")
    @PreAuthorize("permitAll()")
    @Operation(
            operationId = "getCsrfToken",
            summary = "Get the raw CSRF token for the current browser",
            description =
                    "Fetch with credentials after sign-in and sign-out. Send the token in the returned header on unsafe requests.")
    public ResponseEntity<CsrfTokenDTO> token(HttpServletRequest request) {
        // The ordinary CsrfToken request attribute is BREACH-masked. The filter retains the raw repository token here.
        if (!(request.getAttribute(DeferredCsrfToken.class.getName()) instanceof DeferredCsrfToken deferred)) {
            throw new IllegalStateException("CSRF protection must be active for token discovery.");
        }
        var token = deferred.get();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new CsrfTokenDTO(token.getToken(), token.getHeaderName()));
    }
}
