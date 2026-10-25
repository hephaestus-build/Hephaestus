package de.tum.cit.aet.hephaestus.core.security;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Encryption key configuration.
 *
 * @param encryptionKey Key that encrypts sensitive values at rest, such as login provider secrets, LLM connection keys
 *     and JWT signing keys. Exactly 32 bytes, so 32 ASCII characters. Required in production.
 * @param credentialEncryptionKey Active key that encrypts the stored credentials of integration connections. Exactly
 *     32 bytes, so 32 ASCII characters. Required in production.
 * @param credentialEncryptionKeyVersion Positive version number stored with each credential that the active credential
 *     key encrypts. Must differ from prior-credential-encryption-key-version.
 * @param priorCredentialEncryptionKey Previous credential key, still used to decrypt credentials stored under its
 *     version during a key rotation. Exactly 32 bytes. Set it together with prior-credential-encryption-key-version.
 * @param priorCredentialEncryptionKeyVersion Version number of the previous credential key. Set it together with
 *     prior-credential-encryption-key.
 * @param credentialRotationEnabled Re-encrypts stored credentials with the active credential key in batches on the
 *     server role. Requires both the active and the prior credential key.
 */
@Validated
@ConfigurationProperties(prefix = "hephaestus.security")
public record SecurityProperties(
        @Nullable String encryptionKey,
        @Nullable String credentialEncryptionKey,
        @DefaultValue("1") int credentialEncryptionKeyVersion,
        @Nullable String priorCredentialEncryptionKey,
        @Nullable Integer priorCredentialEncryptionKeyVersion,
        @DefaultValue("false") boolean credentialRotationEnabled,
        @DefaultValue("100") int credentialRotationBatchSize) {

    public SecurityProperties {
        // The Compose files forward these keys with an empty default, so an operator who set
        // neither still hands the container `HEPHAESTUS_SECURITY_…_KEY=""`. Blank is how "unset"
        // arrives here — a String binds it as "" while the paired Integer version binds it as null,
        // so comparing the raw values would read a fresh install as a half-finished key rotation and
        // refuse to boot. Normalize first; every rule below then reads one notion of "configured".
        credentialEncryptionKey = blankToNull(credentialEncryptionKey);
        priorCredentialEncryptionKey = blankToNull(priorCredentialEncryptionKey);

        if (credentialEncryptionKeyVersion < 1) {
            throw new IllegalArgumentException(
                    "hephaestus.security.credential-encryption-key-version must be positive");
        }
        if ((priorCredentialEncryptionKey == null) != (priorCredentialEncryptionKeyVersion == null)) {
            throw new IllegalArgumentException(
                    "hephaestus.security.prior-credential-encryption-key and prior-credential-encryption-key-version must be configured together");
        }
        if (priorCredentialEncryptionKeyVersion != null
                && priorCredentialEncryptionKeyVersion == credentialEncryptionKeyVersion) {
            throw new IllegalArgumentException("Active and prior encryption key versions must differ");
        }
        if (credentialRotationBatchSize < 1 || credentialRotationBatchSize > 10_000) {
            throw new IllegalArgumentException(
                    "hephaestus.security.credential-rotation-batch-size must be between 1 and 10000");
        }
        if (credentialRotationEnabled && (credentialEncryptionKey == null || priorCredentialEncryptionKey == null)) {
            throw new IllegalArgumentException("Credential rotation requires both active and prior encryption keys");
        }
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
