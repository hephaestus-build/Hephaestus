package de.tum.cit.aet.hephaestus.core.security;

import static de.tum.cit.aet.hephaestus.testconfig.TestSystemEncryptionKeys.systemKey;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Unit-level guarantees for the AES-256-GCM at-rest converter with shared system-key configuration:
 * round-trip, IV uniqueness, prod fail-fast, legacy-plaintext passthrough, and tamper detection.
 */
class EncryptedStringConverterTest extends BaseUnitTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef"; // 32 ASCII chars = 32 bytes

    private EncryptedStringConverter converter() {
        return new EncryptedStringConverter(systemKey(KEY, "test"));
    }

    @Test
    void roundTripsPlaintext() {
        EncryptedStringConverter c = converter();
        String enc = c.convertToDatabaseColumn("super-secret-token");
        assertThat(enc).startsWith("ENC:").doesNotContain("super-secret-token");
        assertThat(c.convertToEntityAttribute(enc)).isEqualTo("super-secret-token");
    }

    @Test
    void usesAFreshIvPerEncryption() {
        EncryptedStringConverter c = converter();
        assertThat(c.convertToDatabaseColumn("same")).isNotEqualTo(c.convertToDatabaseColumn("same"));
    }

    @Test
    void returnsLegacyUnprefixedValueUnchanged() {
        assertThat(converter().convertToEntityAttribute("plain-legacy-value")).isEqualTo("plain-legacy-value");
    }

    @Test
    void throwsOnTamperedCiphertext() {
        EncryptedStringConverter c = converter();
        String enc = c.convertToDatabaseColumn("secret");
        // Flip the last base64 char to corrupt the GCM tag.
        char last = enc.charAt(enc.length() - 1);
        String tampered = enc.substring(0, enc.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> c.convertToEntityAttribute(tampered)).isInstanceOf(EncryptionException.class);
    }

    @Test
    void throwsOnTruncatedCiphertextShorterThanIv() {
        // An ENC: blob whose decoded body is shorter than the 12-byte GCM IV must fail closed
        // (EncryptionException), not leak a NegativeArraySizeException from `new byte[len - 12]`.
        EncryptedStringConverter c = converter();
        String truncated = "ENC:" + Base64.getEncoder().encodeToString(new byte[5]);
        assertThatThrownBy(() -> c.convertToEntityAttribute(truncated)).isInstanceOf(EncryptionException.class);
    }

    @Test
    void disabledWhenKeyMissingOutsideProd() {
        // No key + non-prod ⇒ encryption disabled, values pass through untouched.
        EncryptedStringConverter disabled = new EncryptedStringConverter(systemKey("", "test"));
        assertThat(disabled.convertToDatabaseColumn("x")).isEqualTo("x");
    }
}
