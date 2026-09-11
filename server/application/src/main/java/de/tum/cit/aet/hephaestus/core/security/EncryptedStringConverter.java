package de.tum.cit.aet.hephaestus.core.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter for encrypting sensitive string fields at rest with AES-256-GCM
 * (authenticated encryption; 12-byte random IV prepended to ciphertext, 128-bit auth tag).
 * Apply via {@code @Convert(converter = EncryptedStringConverter.class)}.
 *
 * <p>Configuration: set {@code hephaestus.security.encryption-key} to a
 * 32-byte (256-bit) secret key.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static final Logger log = LoggerFactory.getLogger(EncryptedStringConverter.class);

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final String PREFIX = "ENC:";
    /** Hoisted to avoid re-seeding /dev/random on every encrypt; SecureRandom is thread-safe. */
    private static final SecureRandom IV_GENERATOR = new SecureRandom();

    private final @Nullable SecretKey secretKey;
    private final boolean enabled;

    /**
     * No-arg constructor required by JPA/Hibernate when running outside Spring context
     * (e.g., Liquibase schema diff). Encryption is disabled in this mode.
     */
    public EncryptedStringConverter() {
        this.secretKey = null;
        this.enabled = false;
        log.debug("Instantiated EncryptedStringConverter: enabled=false, reason=no_spring_context");
    }

    /** The Spring-wired path shares key validation; standalone schema tooling uses the no-arg path. */
    @Autowired
    public EncryptedStringConverter(SystemEncryptionKey systemEncryptionKey) {
        this.secretKey = systemEncryptionKey.key();
        this.enabled = secretKey != null;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || !enabled) {
            return attribute;
        }

        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            IV_GENERATOR.nextBytes(iv);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            log.error("Failed to encrypt value", e);
            throw new EncryptionException("Encryption failed", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null || !enabled) {
            return dbData;
        }

        // Handle unencrypted legacy data
        if (!dbData.startsWith(PREFIX)) {
            log.debug("Found unencrypted value in database: action=returning_as_is");
            return dbData;
        }

        try {
            String encoded = dbData.substring(PREFIX.length());
            byte[] combined = Base64.getDecoder().decode(encoded);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] plainText = cipher.doFinal(cipherText);
            return new String(plainText, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Failed to decrypt value", e);
            throw new EncryptionException("Decryption failed", e);
        }
    }
}
