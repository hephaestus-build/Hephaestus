package de.tum.cit.aet.hephaestus.core.auth.webauthn;

import de.tum.cit.aet.hephaestus.core.auth.AuthProperties;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.web.webauthn.api.AuthenticatorSelectionCriteria;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.api.ResidentKeyRequirement;
import org.springframework.security.web.webauthn.api.UserVerificationRequirement;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

@ConditionalOnServerRole
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PasskeyProperties.class)
public class PasskeyConfiguration {
    @Bean
    PasskeyJson passkeyJson() {
        return new PasskeyJson();
    }

    @Bean
    WebAuthnRelyingPartyOperations passkeyOperations(
            AccountRepository accounts,
            PasskeyCredentialRepository credentials,
            PasskeyJson json,
            PasskeyProperties properties,
            AuthProperties auth,
            Environment environment) {
        String rpId = properties.rpId().isBlank() ? auth.issuer().getHost() : properties.rpId();
        if (rpId == null || rpId.isBlank() || rpId.contains(":") || rpId.contains("/")) {
            throw new IllegalStateException("Passkeys require a valid RP ID");
        }
        Set<String> origins = properties.allowedOrigins().isEmpty()
                ? Set.of(origin(
                        URI.create(auth.issuer().getScheme() + "://"
                                + auth.issuer().getRawAuthority()),
                        rpId,
                        environment.matchesProfiles("prod")))
                : properties.allowedOrigins().stream()
                        .map(s -> origin(URI.create(s), rpId, environment.matchesProfiles("prod")))
                        .collect(Collectors.toUnmodifiableSet());
        AccountPasskeyCredentialRepository store = new AccountPasskeyCredentialRepository(accounts, credentials, json);
        Webauthn4JRelyingPartyOperations operations = new Webauthn4JRelyingPartyOperations(
                new AccountPasskeyUserRepository(accounts),
                store,
                PublicKeyCredentialRpEntity.builder()
                        .id(rpId)
                        .name("Hephaestus")
                        .build(),
                origins);
        operations.setCustomizeCreationOptions(options -> options.timeout(Duration.ofMinutes(5))
                .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                        .residentKey(ResidentKeyRequirement.REQUIRED)
                        .userVerification(UserVerificationRequirement.REQUIRED)
                        .build()));
        operations.setCustomizeRequestOptions(options ->
                options.userVerification(UserVerificationRequirement.REQUIRED).timeout(Duration.ofMinutes(5)));
        return operations;
    }

    static String origin(URI uri, String rpId, boolean secure) {
        String host = uri.getHost();
        if (host == null
                || !(host.equals(rpId) || host.endsWith("." + rpId))
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPath() != null
                        && !uri.getPath().isEmpty()
                        && !uri.getPath().equals("/"))
                || !("https".equals(uri.getScheme())
                        || (!secure && "http".equals(uri.getScheme()) && "localhost".equals(host)))) {
            throw new IllegalStateException("Passkey origins must be trusted HTTPS origins within the RP ID");
        }
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }
}
