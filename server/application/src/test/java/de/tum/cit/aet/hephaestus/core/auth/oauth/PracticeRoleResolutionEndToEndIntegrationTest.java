package de.tum.cit.aet.hephaestus.core.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeature;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountFeatureRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProvider;
import de.tum.cit.aet.hephaestus.core.auth.provider.LoginProviderRepository;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import de.tum.cit.aet.hephaestus.testconfig.TestUserFactory;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

class PracticeRoleResolutionEndToEndIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private SystemSecrets systemSecrets;

    private static final String ROLE = "run_practice_review";
    // Explicit-port base URL so origin canonicalization is genuinely exercised (a state production
    // can
    // hold), not a no-op. LoginProvider.setBaseUrl already strips trailing slashes; a path would be
    // invalid.
    private static final String LOGIN_BASE_URL = "https://gitlab.example.com:8443";
    private static final String REGISTRATION_ID = "gitlab-e2e";
    private static final long NATIVE_ID = 583231L; // numeric provider user id, shared by login + sync

    @Autowired
    private AccountProvisioningService service;

    @Autowired
    private ClientRegistrationRepository clientRegistrationRepository;

    @Autowired
    private AccountFeatureRepository accountFeatureRepository;

    @Autowired
    private IdentityRepository identityRepository;

    @Autowired
    private LoginProviderRepository loginProviderRepository;

    @Autowired
    private IdentityProviderRepository gitProviderRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void practiceRoleResolvesEndToEndThroughLoginAndSyncForTheSamePerson() {
        seedLoginProvider(REGISTRATION_ID, LoginProvider.ProviderType.GITLAB, LOGIN_BASE_URL);

        // Pin the production seam that makes subject numeric: the real ClientRegistration's name
        // attr.
        ClientRegistration registration =
                Objects.requireNonNull(clientRegistrationRepository.findByRegistrationId(REGISTRATION_ID));
        String nameAttr = Objects.requireNonNull(
                registration.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
        assertThat(nameAttr)
                .as("login must key the principal name on the provider's numeric id")
                .isEqualTo("id");

        // Principal name keyed by the PRODUCTION attr (asserted == "id" above); subject is derived
        // the
        // way HephaestusAuthSuccessHandler does — principal.getName() — and stored unchanged.
        OAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("id", String.valueOf(NATIVE_ID), "login", "octocat", "name", "Octo Cat"),
                nameAttr);
        String subject = principal.getName();

        Account account = service.resolveOrProvision(
                        REGISTRATION_ID, subject, principal, AuthIntentCookie.Intent.login(null, null))
                .account();

        Identity link = identityRepository
                .findActiveByAccountId(persistedId(account.getId()))
                .get(0);
        // (a) the stored subject IS the numeric id — exactly what the gate stringifies from
        // User.nativeId.
        assertThat(link.getProviderUserKey()).isEqualTo(String.valueOf(NATIVE_ID));
        long loginResolvedProviderId = link.getProviderId();

        // (b) the SCM side independently resolves the SAME git_provider row at the canonical
        // origin.
        String origin = originOf(LOGIN_BASE_URL);
        IdentityProvider scmProvider = gitProviderRepository
                .findByTypeAndServerUrl(IdentityProviderType.GITLAB, origin)
                .orElseThrow(() -> new AssertionError("login created no git_provider at canonical origin "
                        + origin
                        + " — login/SCM canonicalization"
                        + " diverged"));
        assertThat(persistedId(scmProvider.getId())).isEqualTo(loginResolvedProviderId);

        // Synced SCM User for the same person under that same provider row.
        User user = userRepository.save(TestUserFactory.createUser(NATIVE_ID, "octocat", scmProvider));
        accountFeatureRepository.save(new AccountFeature(persistedId(account.getId()), ROLE));

        // THE PROOF: the (provider.id, valueOf(nativeId)) tuple the gate passes resolves the
        // granted flag.
        assertThat(accountFeatureRepository.existsActiveFeatureForProviderSubject(
                        persistedId(user.getProvider().getId()), String.valueOf(user.getNativeId()), ROLE))
                .isTrue();
    }

    // Cross-provider isolation (same numeric subject on a different instance must NOT inherit the
    // flag)
    // is owned by ProductionSchemaContractIntegrationTest at the query layer — not duplicated here.

    private void seedLoginProvider(String registrationId, LoginProvider.ProviderType type, String baseUrl) {
        LoginProvider provider = new LoginProvider();
        provider.setRegistrationId(registrationId);
        provider.setType(type);
        provider.setDisplayName(registrationId);
        provider.setBaseUrl(baseUrl);
        provider.setClientId("test-client-id");
        provider.replaceClientSecret("test-client-secret", systemSecrets);
        provider.setScopes("read_user");
        loginProviderRepository.save(provider);
    }

    private static String originOf(String baseUrl) {
        URI uri = URI.create(baseUrl);
        String origin = uri.getScheme() + "://" + uri.getHost();
        return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
    }

    private static long persistedId(@Nullable Long id) {
        assertNotNull(id);
        return id;
    }
}
