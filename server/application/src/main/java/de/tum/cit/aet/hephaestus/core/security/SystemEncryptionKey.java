package de.tum.cit.aet.hephaestus.core.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Validates the system encryption key once for both Spring and Hibernate-owned consumers.
 * Credential bundles use their separate, versioned key configuration.
 */
@Component
public final class SystemEncryptionKey {

    private static final Logger log = LoggerFactory.getLogger(SystemEncryptionKey.class);

    private final @Nullable SecretKey key;

    public SystemEncryptionKey(SecurityProperties properties, Environment environment) {
        String encryptionKey = properties.encryptionKey();
        if (encryptionKey == null || encryptionKey.isBlank()) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException(
                        "Encryption key is required in production! Set hephaestus.security.encryption-key");
            }
            if (environment.matchesProfiles("specs", "cds-training")) {
                log.debug("System encryption is unavailable during artifact generation: reason=missing_key");
            } else {
                log.warn("Skipped encryption configuration: reason=missing_key, "
                        + "action=set_hephaestus_security_encryption_key_in_production");
            }
            this.key = null;
        } else {
            // Validate the actual AES key length in BYTES (not chars): a 32-char key with multibyte
            // characters is >32 UTF-8 bytes and would otherwise construct fine here and only throw
            // InvalidKeyException on the first encrypt. Fail fast at startup instead.
            byte[] keyBytes = encryptionKey.getBytes(StandardCharsets.UTF_8);
            if (keyBytes.length != 32) {
                // Report BOTH counts. "32 bytes" alone sends an operator who pasted a 32-character
                // passphrase with an umlaut in it looking for a 34-character key; "32 characters"
                // alone is a lie for exactly that operator. Never echo the key itself.
                int characters = encryptionKey.length();
                String howToFix = keyBytes.length == characters
                        ? "For ASCII, bytes and characters are the same, so that is 32 characters: "
                        : "This key contains non-ASCII characters, and those cost more than one byte each. "
                                + "Use ASCII only, exactly 32 characters: ";
                throw new IllegalArgumentException(
                        "hephaestus.security.encryption-key (HEPHAESTUS_SECURITY_ENCRYPTION_KEY) must be a "
                                + "32-byte AES-256 key. Got "
                                + keyBytes.length
                                + " bytes from "
                                + characters
                                + " characters. "
                                + howToFix
                                + "openssl rand -base64 24 | cut -c1-32");
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
            log.info("Enabled system encryption for sensitive data at rest");
        }
    }

    public @Nullable SecretKey key() {
        return key;
    }
}
