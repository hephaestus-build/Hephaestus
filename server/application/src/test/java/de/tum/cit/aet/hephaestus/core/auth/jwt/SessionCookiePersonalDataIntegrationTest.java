package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jwt.SignedJWT;
import de.tum.cit.aet.hephaestus.core.auth.domain.Account;
import de.tum.cit.aet.hephaestus.core.auth.domain.AccountRepository;
import de.tum.cit.aet.hephaestus.testconfig.RealAuthIntegrationTest;
import java.text.ParseException;
import java.time.Instant;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** NIST SP 800-63B-4 § 5.1.1: a session cookie carries no cleartext personal information. */
class SessionCookiePersonalDataIntegrationTest extends RealAuthIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private HephaestusJwtIssuer jwtIssuer;

    @Test
    void shouldCarryNoNameOrLoginWhenTheSessionTokenIsIssued() throws ParseException {
        Account account = new Account("Grace Hopper");
        account.setPrimaryEmail("grace@example.test");
        account = accountRepository.save(account);

        String token = jwtIssuer
                .issue(Objects.requireNonNull(account.getId()), TokenConstraints.session(null, Instant.now()), null)
                .value();
        String payload = SignedJWT.parse(token).getPayload().toString();

        assertThat(payload)
                .contains("\"sub\":\"" + account.getId() + "\"")
                .doesNotContain("preferred_username", "given_name", "Grace", "grace@example.test");
    }
}
