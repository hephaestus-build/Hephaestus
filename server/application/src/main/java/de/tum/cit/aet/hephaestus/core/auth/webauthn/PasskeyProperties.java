package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Deployment policy. Workspace and account choices cannot weaken these requirements.
 *
 * @param instanceAdminRequired Requires a verified passkey sign-in from every instance admin. Workspace and account
 *     settings cannot turn this off.
 * @param workspaceAdminRequired Requires a verified passkey sign-in for workspace administration in every workspace. A
 *     workspace cannot turn this off.
 * @param rpId WebAuthn relying party ID, the domain that passkeys are bound to. When it is empty, the host of the auth
 *     issuer is used. Passkeys registered under one RP ID do not work under another.
 * @param allowedOrigins Browser origins that may register and use passkeys, as a comma-separated list. Each must be an
 *     HTTPS origin on the RP ID or one of its subdomains. When it is empty, the origin of the auth issuer is used.
 */
@ConfigurationProperties("hephaestus.auth.passkeys")
public record PasskeyProperties(
        @DefaultValue("false") boolean instanceAdminRequired,
        @DefaultValue("false") boolean workspaceAdminRequired,
        @DefaultValue("") String rpId,
        @DefaultValue List<String> allowedOrigins) {}
