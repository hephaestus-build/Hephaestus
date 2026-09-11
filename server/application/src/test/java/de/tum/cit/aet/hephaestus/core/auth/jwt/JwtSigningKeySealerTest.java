package de.tum.cit.aet.hephaestus.core.auth.jwt;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemEncryptionKeys.systemKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pins the at-rest sealing contract for {@link JwtSigningKeySealer}. Each test fails if a
 * control is dropped: round-trip fidelity, tamper detection, AAD binding, length guards, and
 * the enable/fail-fast policy. No Spring context — the canonical (key, profile) constructor is
 * the seam.
 */
class JwtSigningKeySealerTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // exactly 32 chars

    private JwtSigningKeySealer enabledSealer() {
        return new JwtSigningKeySealer(systemKey(KEY, "dev"));
    }

    @Test
    void sealThenUnseal_returnsOriginalBytes() {
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] plaintext = "fake-pkcs8-der-bytes-\0\1\2\3".getBytes(StandardCharsets.UTF_8);

        byte[] sealed = sealer.seal(plaintext);
        byte[] unsealed = sealer.unseal(sealed);

        assertThat(unsealed).isEqualTo(plaintext);
        assertThat(sealed).isNotEqualTo(plaintext); // actually encrypted, not passthrough
    }

    @Test
    void sealedBlob_hasVersionByteAndNonce() {
        byte[] sealed = enabledSealer().seal(new byte[] {1, 2, 3, 4});

        assertThat(sealed[0]).isEqualTo(JwtSigningKeySealer.FORMAT_VERSION_V1);
        // version(1) + nonce(12) + ciphertext(4) + tag(16) = 33
        assertThat(sealed).hasSize(1 + 12 + 4 + 16);
    }

    @Test
    void seal_usesFreshNoncePerCall() {
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] plaintext = new byte[] {9, 9, 9, 9};

        byte[] a = sealer.seal(plaintext);
        byte[] b = sealer.seal(plaintext);

        assertThat(a).isNotEqualTo(b); // probabilistic: random nonce → distinct ciphertext
        assertThat(sealer.unseal(a)).isEqualTo(plaintext);
        assertThat(sealer.unseal(b)).isEqualTo(plaintext);
    }

    @Test
    void unseal_rejectsTamperedCiphertext() {
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] sealed = sealer.seal(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        // Flip a bit in the ciphertext region (after version + nonce).
        sealed[1 + 12 + 1] ^= 0x01;

        assertThatThrownBy(() -> sealer.unseal(sealed))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("unsealing failed");
    }

    @Test
    void unseal_rejectsTamperedNonce() {
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] sealed = sealer.seal(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        sealed[1] ^= 0x01; // mutate first nonce byte

        assertThatThrownBy(() -> sealer.unseal(sealed)).isInstanceOf(EncryptionException.class);
    }

    @Test
    void unseal_rejectsBlobSealedUnderDifferentAad() {
        // Genuine AAD-binding negative: forge a blob bound to a DIFFERENT AAD domain (the kind a
        // tenant-scoped CredentialBundleConverter would use) via the package-private seal-with-AAD
        // seam, then prove it cannot be unsealed under this sealer's fixed system AAD. Same key,
        // same nonce-handling, ONLY the AAD differs — so a failure here isolates the AAD binding.
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] tenantScopedAad = "workspace:42:credential_bundle".getBytes(StandardCharsets.UTF_8);

        byte[] sealedUnderForeignAad = sealer.seal(new byte[] {7, 7, 7, 7}, tenantScopedAad);

        assertThatThrownBy(() -> sealer.unseal(sealedUnderForeignAad))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("unsealing failed");
    }

    @Test
    void unseal_rejectsWrongVersionByte() {
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] sealed = sealer.seal(new byte[] {1, 2, 3, 4});
        sealed[0] = 0x02; // unsupported version

        assertThatThrownBy(() -> sealer.unseal(sealed))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("Unsupported sealed JWT key version");
    }

    @Test
    void unseal_rejectsShortInput() {
        JwtSigningKeySealer sealer = enabledSealer();

        assertThatThrownBy(() -> sealer.unseal(new byte[0]))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("too short");

        // version + partial nonce, no ciphertext/tag
        byte[] tooShort = new byte[] {JwtSigningKeySealer.FORMAT_VERSION_V1, 0, 0, 0};
        assertThatThrownBy(() -> sealer.unseal(tooShort))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("too short");
    }

    @Test
    void disabled_whenNoKeyInNonProd() {
        JwtSigningKeySealer sealer = new JwtSigningKeySealer(systemKey(null, "dev"));

        assertThat(sealer.isEnabled()).isFalse();
        assertThatThrownBy(() -> sealer.seal(new byte[] {1}))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("not enabled");
        assertThatThrownBy(() -> sealer.unseal(new byte[] {1})).isInstanceOf(EncryptionException.class);
    }

    @Test
    void disabled_whenBlankKeyInNonProd() {
        assertThat(new JwtSigningKeySealer(systemKey("   ")).isEnabled()).isFalse();
    }

    @Test
    void enabled_reportsKeyId() {
        JwtSigningKeySealer sealer = enabledSealer();
        assertThat(sealer.isEnabled()).isTrue();
        assertThat(sealer.keyId()).isEqualTo(JwtSigningKeySealer.KEY_ID);
        assertThat(sealer.keyId()).isEqualTo("aesgcm-system-v1");
    }

    @Test
    void sealRoundTrip_withLargePayload() {
        JwtSigningKeySealer sealer = enabledSealer();
        byte[] big = new byte[4096];
        Arrays.fill(big, (byte) 0xAB);

        assertThat(sealer.unseal(sealer.seal(big))).isEqualTo(big);
    }
}
