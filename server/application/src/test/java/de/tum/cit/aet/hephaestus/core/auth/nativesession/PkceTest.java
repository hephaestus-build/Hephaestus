package de.tum.cit.aet.hephaestus.core.auth.nativesession;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import org.junit.jupiter.api.Test;

class PkceTest extends BaseUnitTest {

    // RFC 7636 Appendix B.
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Test
    void shouldVerifyWhenTheVerifierHashesToTheChallenge() {
        assertThat(Pkce.verifies(VERIFIER, CHALLENGE)).isTrue();
        assertThat(Pkce.isChallenge(CHALLENGE)).isTrue();
    }

    @Test
    void shouldRefuseWhenTheVerifierDiffersOrIsMalformed() {
        assertThat(Pkce.verifies(VERIFIER.replace('d', 'e'), CHALLENGE)).isFalse();
        assertThat(Pkce.verifies("short", CHALLENGE)).isFalse();
        assertThat(Pkce.verifies(VERIFIER + "!", CHALLENGE)).isFalse();
    }

    @Test
    void shouldMintDistinctSecretsThatAreValidVerifiers() {
        String first = Pkce.newSecret();

        assertThat(first).hasSize(43).isNotEqualTo(Pkce.newSecret());
        assertThat(Pkce.hash(first)).hasSize(64).isEqualTo(Pkce.hash(first));
    }
}
