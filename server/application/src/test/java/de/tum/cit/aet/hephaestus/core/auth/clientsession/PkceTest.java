package de.tum.cit.aet.hephaestus.core.auth.clientsession;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class PkceTest extends BaseUnitTest {

    /** RFC 7636 Appendix B. */
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void shouldVerifyTheRfcExampleWhenTheVerifierMatchesItsChallenge() {
        assertThat(Pkce.isChallenge(CHALLENGE)).isTrue();
        assertThat(Pkce.verifies(VERIFIER, CHALLENGE)).isTrue();
    }

    @Test
    void shouldRejectWhenTheVerifierIsWrongOrMalformed() {
        assertThat(Pkce.verifies(VERIFIER.replace('d', 'e'), CHALLENGE)).isFalse();
        assertThat(Pkce.verifies("too-short", CHALLENGE)).isFalse();
        assertThat(Pkce.verifies("a".repeat(129), CHALLENGE)).isFalse();
        assertThat(Pkce.verifies(VERIFIER + "!", CHALLENGE)).isFalse();
        assertThat(Pkce.isChallenge(CHALLENGE + "A")).isFalse();
        assertThat(Pkce.isChallenge(CHALLENGE.replace('-', '+'))).isFalse();
    }

    @Test
    void shouldMintDistinctSecretsInTheBoundedShapeWhenAskedForSecrets() {
        String first = Pkce.newSecret();
        String second = Pkce.newSecret();

        assertThat(first).matches(Pkce.SECRET_PATTERN).isNotEqualTo(second);
        assertThat(Pkce.hash(first)).hasSize(64).isEqualTo(Pkce.hash(first)).isNotEqualTo(Pkce.hash(second));
    }
}
