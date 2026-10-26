package de.tum.cit.aet.hephaestus.account;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.core.auth.domain.Identity;
import de.tum.cit.aet.hephaestus.core.auth.domain.IdentityRepository;
import de.tum.cit.aet.hephaestus.core.auth.jwt.HephaestusJwtIssuer;
import de.tum.cit.aet.hephaestus.core.auth.jwt.TokenConstraints;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountDirectory;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderRepository;
import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

class AccountControllerIntegrationTest extends RealAuthIntegrationTest {
    @Autowired
    private WebTestClient client;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private IdentityRepository identities;

    @Autowired
    private IdentityProviderRepository providers;

    @Autowired
    private HephaestusJwtIssuer issuer;

    @Autowired
    private AccountDirectory accountDirectory;

    @Test
    void shouldReadAccountSettingsWhenNoProviderMirrorHasBeenSynced() {
        var account = accounts.save(new Account("Settings reader"));
        client.get()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(token(account)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.practiceFeedbackDeliveryEnabled")
                .isEqualTo(true);
    }

    @Test
    void shouldSaveOneDeliveryChoiceWhenAnAccountHasTwoProviderIdentities() {
        var account = accounts.save(new Account("Settings developer"));
        var other = accounts.save(new Account("Other developer"));
        link(account, IdentityProviderType.GITHUB, "https://settings-github.example.com", "11");
        link(account, IdentityProviderType.GITLAB, "https://settings-gitlab.example.com", "22");
        client.post()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(token(account)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateUserSettingsDTO(false, "Europe/Berlin"))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.practiceFeedbackDeliveryEnabled")
                .isEqualTo(false)
                .jsonPath("$.timeZone")
                .isEqualTo("Europe/Berlin");
        assertThat(accountDirectory.timeZone(Objects.requireNonNull(account.getId())))
                .as("the time zone is the account's, for every workspace")
                .contains(ZoneId.of("Europe/Berlin"));
        assertThat(accounts.findById(Objects.requireNonNull(account.getId()))
                        .orElseThrow()
                        .isFeedbackDeliveryEnabled())
                .isFalse();
        assertThat(accounts.findById(Objects.requireNonNull(other.getId()))
                        .orElseThrow()
                        .isFeedbackDeliveryEnabled())
                .isTrue();
    }

    @Test
    void shouldRejectAnUnknownTimeZoneWithoutChangingTheSavedOne() {
        var account = accounts.save(new Account("Time zone developer"));
        long accountId = Objects.requireNonNull(account.getId());
        accountDirectory.setTimeZone(accountId, "Europe/Berlin");
        client.post()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(token(account)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UpdateUserSettingsDTO(false, "Mars/Olympus"))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody(Void.class);
        assertThat(accountDirectory.timeZone(accountId)).contains(ZoneId.of("Europe/Berlin"));
    }

    @Test
    void shouldKeepTheSavedTimeZoneWhenTheRequestHasNone() {
        var account = accounts.save(new Account("Earlier tab developer"));
        long accountId = Objects.requireNonNull(account.getId());
        accountDirectory.setTimeZone(accountId, "Europe/Berlin");
        client.post()
                .uri("/user/settings")
                .headers(headers -> headers.setBearerAuth(token(account)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"practiceFeedbackDeliveryEnabled\":false}")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.timeZone")
                .isEqualTo("Europe/Berlin");
        var saved = accounts.findById(accountId).orElseThrow();
        assertThat(saved.getTimeZone()).isEqualTo("Europe/Berlin");
        assertThat(saved.isFeedbackDeliveryEnabled()).isFalse();
    }

    private void link(Account account, IdentityProviderType type, String url, String key) {
        var provider = providers
                .findByTypeAndServerUrl(type, url)
                .orElseGet(() -> providers.save(new IdentityProvider(type, url)));
        var identity = new Identity();
        identity.setAccount(account);
        identity.setProviderId(Objects.requireNonNull(provider.getId()));
        identity.setProviderUserKey(key);
        identities.save(identity);
    }

    private String token(Account account) {
        return issuer.issue(
                        Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
    }
}
