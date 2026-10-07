package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Deployment policy. Workspace and account choices cannot weaken these requirements. */
@ConfigurationProperties("hephaestus.auth.passkeys")
public record PasskeyProperties(
        @DefaultValue("false") boolean instanceAdminRequired,
        @DefaultValue("false") boolean workspaceAdminRequired,
        @DefaultValue("") String rpId,
        @DefaultValue List<String> allowedOrigins) {}
