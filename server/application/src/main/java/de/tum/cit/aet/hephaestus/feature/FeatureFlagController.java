package de.tum.cit.aet.hephaestus.feature;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for retrieving the current user's feature flags.
 * <p>
 * Returns a server-authoritative evaluation of all {@link FeatureFlag} values,
 * combining role checks (from the JWT) and Spring Boot config flags
 * into a single response. The endpoint is extremely cheap: it reads JWT
 * authorities (already parsed) and in-memory config properties — no DB or
 * external calls.
 *
 * @see FeatureFlagService
 * @see FeatureFlagsDTO
 */
@RestController
@RequestMapping("/user")
@Tag(name = "Account")
@PreAuthorize("isAuthenticated()")
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    public FeatureFlagController(FeatureFlagService featureFlagService) {
        this.featureFlagService = featureFlagService;
    }

    @GetMapping("/features")
    @Operation(
            summary = "Get feature flags for the current user",
            description = "Returns all feature flags evaluated for the authenticated user. "
                    + "Combines role checks and server-side config toggles.")
    @ApiResponse(responseCode = "200", description = "Feature flags evaluated successfully")
    public ResponseEntity<FeatureFlagsDTO> getUserFeatures() {
        return ResponseEntity.ok(FeatureFlagsDTO.from(featureFlagService));
    }
}
