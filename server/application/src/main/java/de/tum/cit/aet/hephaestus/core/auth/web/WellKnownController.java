package de.tum.cit.aet.hephaestus.core.auth.web;

import com.nimbusds.jose.jwk.JWKSet;
import de.tum.cit.aet.hephaestus.core.auth.jwt.JwtSigningKeyService;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the Hephaestus signing keys at {@code /.well-known/jwks.json} so a verifier can
 * validate an ES256 session JWT against the rotating public key set. (A full
 * {@code /.well-known/openid-configuration} discovery document will be added if/when a relying
 * party — Spring Authorization Server, a worker/CLI verifier — is actually mounted.)
 */
@ConditionalOnServerRole
@RestController
@Tag(name = "Auth discovery")
public class WellKnownController {

    private final JwtSigningKeyService keyService;

    public WellKnownController(JwtSigningKeyService keyService) {
        this.keyService = keyService;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @Operation(summary = "JWK set (public keys only)", operationId = "getJwks")
    public Map<String, Object> jwks() {
        JWKSet publicSet = keyService.publicJwkSet();
        return publicSet.toJSONObject();
    }
}
