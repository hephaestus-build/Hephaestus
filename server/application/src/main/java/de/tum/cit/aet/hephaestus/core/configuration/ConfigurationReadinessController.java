package de.tum.cit.aet.hephaestus.core.configuration;

import de.tum.cit.aet.hephaestus.core.RequireInstanceAdmin;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.auth.spi.IdentityProviderCatalog;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@ConditionalOnServerRole
@RestController
@WorkspaceAgnostic("Redacted instance configuration facts, app_admin only")
@RequestMapping("/admin/configuration-readiness")
@Tag(name = "Configuration Readiness", description = "Redacted production configuration facts")
@RequireInstanceAdmin
public class ConfigurationReadinessController {

    private final ConfigurationReadinessEvaluator evaluator;
    private final IdentityProviderCatalog identityProviderCatalog;

    ConfigurationReadinessController(
            ConfigurationReadinessEvaluator evaluator, IdentityProviderCatalog identityProviderCatalog) {
        this.evaluator = evaluator;
        this.identityProviderCatalog = identityProviderCatalog;
    }

    @GetMapping
    @Operation(summary = "Get redacted configuration readiness", operationId = "adminGetConfigurationReadiness")
    public List<ConfigurationFactDTO> get() {
        return evaluator.evaluateReadiness(identityProviderCatalog.hasEnabledPrimarySignInProvider());
    }
}
