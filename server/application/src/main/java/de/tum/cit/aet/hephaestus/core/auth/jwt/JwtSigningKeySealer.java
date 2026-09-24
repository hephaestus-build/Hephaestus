package de.tum.cit.aet.hephaestus.core.auth.jwt;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.SystemEncryptionKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Seals the {@code jwt_signing_key.private_key_pem} column at rest with AES-256-GCM,
 * reusing the system master key validated by {@link SystemEncryptionKey}. Credential bundles
 * have their own separately configured encryption keys.
 *
 * <h2>Why system-scoped (not tenant-scoped) AAD</h2>
 * Signing keys are system-wide, not per-workspace. The GCM AAD is a single fixed,
 * purpose-binding constant ({@link #AAD_STRING}) rather than a per-row tuple. This still
 * defends against confused-deputy substitution: a blob sealed for this column cannot be
 * decrypted in any context that supplies a different AAD (e.g. the tenant-scoped
 * {@code CredentialBundleConverter} AAD), and vice versa.
 *
 * <h2>Envelope layout</h2>
 * <pre>
 *   [0]        version byte ({@link #FORMAT_VERSION_V1} = 0x01)
 *   [1..13)    12-byte random GCM nonce
 *   [13..]     ciphertext || 16-byte GCM tag
 * </pre>
 *
 * <h2>Enablement / fail-fast</h2>
 * Enabled iff a valid 32-byte system key is present.
 * In the {@code prod} profile a missing key throws (prod requires it); in dev/CI/test an
 * absent key disables sealing so a local boot still works (writing raw {@code v0-unsealed}
 * rows). A key that is not 32 UTF-8 bytes is always rejected.
 */
@ConditionalOnServerRole
@Component
public class JwtSigningKeySealer {

    /** Tag stamped on {@code jwt_signing_key.encryption_key_id} for blobs sealed by this class. */
    public static final String KEY_ID = "aesgcm-system-v1";

    /** First byte of every sealed blob. */
    public static final byte FORMAT_VERSION_V1 = 0x01;

    /**
     * Fixed, system-scoped GCM AAD. Binds a sealed blob to this column/purpose so it cannot be
     * swapped with a tenant-scoped credential blob (distinct AAD domain → authentication fails).
     */
    static final String AAD_STRING = "system:jwt_signing_key.private_key_pem";

    private static final byte[] AAD = AAD_STRING.getBytes(StandardCharsets.UTF_8);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    /** Hoisted to avoid re-seeding on every seal; SecureRandom is thread-safe. */
    private static final SecureRandom IV_GENERATOR = new SecureRandom();

    private final @Nullable SecretKey secretKey;
    private final boolean enabled;

    public JwtSigningKeySealer(SystemEncryptionKey systemEncryptionKey) {
        this.secretKey = systemEncryptionKey.key();
        this.enabled = secretKey != null;
    }

    /** Whether sealing is operational (a valid key is configured). */
    public boolean isEnabled() {
        return enabled;
    }

    /** Tag to stamp on {@code encryption_key_id} when sealing is enabled. */
    public String keyId() {
        return KEY_ID;
    }

    /**
     * Seal raw PKCS#8 DER private-key bytes into the versioned AES-256-GCM envelope.
     *
     * @throws EncryptionException if sealing is disabled or the cipher fails
     */
    public byte[] seal(byte[] privateKeyDer) {
        return seal(privateKeyDer, AAD);
    }

    /**
     * Seal under an explicit AAD. Package-private test seam: the production path always uses the
     * fixed system AAD via {@link #seal(byte[])}, but exposing the AAD lets a test forge a blob
     * bound to a <em>different</em> AAD domain and assert it fails to {@link #unseal} under the
     * system AAD — proving the GCM binding is real, not cosmetic.
     */
    byte[] seal(byte[] privateKeyDer, byte[] aad) {
        requireEnabled("seal");
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            IV_GENERATOR.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD(aad);
            byte[] cipherText = cipher.doFinal(privateKeyDer);

            byte[] combined = new byte[1 + iv.length + cipherText.length];
            combined[0] = FORMAT_VERSION_V1;
            System.arraycopy(iv, 0, combined, 1, iv.length);
            System.arraycopy(cipherText, 0, combined, 1 + iv.length, cipherText.length);
            return combined;
        } catch (Exception e) {
            throw new EncryptionException("JWT signing-key sealing failed", e);
        }
    }

    /**
     * Reverse {@link #seal(byte[])}: returns the original PKCS#8 DER bytes. A wrong AAD, a
     * tampered blob, an unsupported version byte, or a too-short input all surface as a clear
     * {@link EncryptionException}.
     */
    public byte[] unseal(byte[] sealed) {
        requireEnabled("unseal");
        if (sealed.length < 1) {
            throw new EncryptionException("Sealed JWT key too short: 0 bytes");
        }
        byte version = sealed[0];
        if (version != FORMAT_VERSION_V1) {
            throw new EncryptionException(
                    "Unsupported sealed JWT key version: 0x" + Integer.toHexString(version & 0xFF));
        }
        if (sealed.length < 1 + GCM_IV_LENGTH + 1) {
            throw new EncryptionException(
                    "Sealed JWT key too short: " + sealed.length + " bytes (need > " + (1 + GCM_IV_LENGTH) + ")");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[sealed.length - 1 - GCM_IV_LENGTH];
            System.arraycopy(sealed, 1, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(sealed, 1 + GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD(AAD);
            return cipher.doFinal(cipherText);
        } catch (EncryptionException e) {
            throw e;
        } catch (Exception e) {
            throw new EncryptionException("JWT signing-key unsealing failed", e);
        }
    }

    private void requireEnabled(String op) {
        if (!enabled) {
            throw new EncryptionException(
                    "JwtSigningKeySealer is not enabled; cannot " + op + " without a configured key");
        }
    }
}
