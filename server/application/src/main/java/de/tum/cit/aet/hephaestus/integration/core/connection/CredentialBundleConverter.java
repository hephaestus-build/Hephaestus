package de.tum.cit.aet.hephaestus.integration.core.connection;

import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.MissingCredentialKeyException;
import de.tum.cit.aet.hephaestus.core.security.SecurityProperties;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApiCredentialProvider.CredentialBundle;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Encrypts credential bundles with row identity bound as AES-GCM additional authenticated data. */
@Component
public class CredentialBundleConverter {

    private static final Logger log = LoggerFactory.getLogger(CredentialBundleConverter.class);

    public static final String ALGORITHM_TAG = "aesgcm-v2";
    public static final byte FORMAT_VERSION_V2 = 0x02;

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_TAG_BYTES = GCM_TAG_LENGTH / Byte.SIZE;
    private static final SecureRandom IV_GENERATOR = new SecureRandom();

    private final Map<Integer, SecretKey> keys;
    private final int activeKeyVersion;
    private final @Nullable Integer priorKeyVersion;
    private final boolean enabled;
    private final ObjectMapper objectMapper;

    @Autowired
    public CredentialBundleConverter(
            SecurityProperties properties, Environment environment, ObjectMapper objectMapper) {
        this(
                properties.credentialEncryptionKey(),
                properties.credentialEncryptionKeyVersion(),
                properties.priorCredentialEncryptionKey(),
                properties.priorCredentialEncryptionKeyVersion(),
                environment.matchesProfiles("prod"),
                objectMapper);
    }

    public CredentialBundleConverter(@Nullable String encryptionKey, boolean production) {
        this(encryptionKey, 1, null, null, production, testObjectMapper());
    }

    public CredentialBundleConverter(
            @Nullable String encryptionKey,
            int activeKeyVersion,
            @Nullable String priorCredentialEncryptionKey,
            @Nullable Integer priorKeyVersion,
            boolean production) {
        this(
                encryptionKey,
                activeKeyVersion,
                priorCredentialEncryptionKey,
                priorKeyVersion,
                production,
                testObjectMapper());
    }

    private CredentialBundleConverter(
            @Nullable String encryptionKey,
            int activeKeyVersion,
            @Nullable String priorCredentialEncryptionKey,
            @Nullable Integer priorKeyVersion,
            boolean production,
            ObjectMapper objectMapper) {
        if (activeKeyVersion < 1) throw new IllegalArgumentException("Active key version must be positive");
        if ((priorCredentialEncryptionKey == null) != (priorKeyVersion == null)) {
            throw new IllegalArgumentException("Prior key and version must be configured together");
        }
        if (priorKeyVersion != null && priorKeyVersion == activeKeyVersion) {
            throw new IllegalArgumentException("Active and prior key versions must differ");
        }
        this.activeKeyVersion = activeKeyVersion;
        this.priorKeyVersion = priorKeyVersion;
        this.objectMapper = objectMapper;
        if (encryptionKey == null || encryptionKey.isBlank()) {
            if (production) {
                throw new IllegalStateException(
                        "Credential encryption key is required in production! Set hephaestus.security.credential-encryption-key");
            }
            this.keys = Map.of();
            this.enabled = false;
            return;
        }
        SecretKey active = parseKey(encryptionKey, "credential-encryption-key");
        this.keys = priorCredentialEncryptionKey == null
                ? Map.of(activeKeyVersion, active)
                : Map.of(
                        activeKeyVersion,
                        active,
                        Objects.requireNonNull(priorKeyVersion),
                        parseKey(priorCredentialEncryptionKey, "prior-credential-encryption-key"));
        this.enabled = true;
        log.info(
                "Enabled credential encryption: activeKeyVersion={}, priorKeyConfigured={}",
                activeKeyVersion,
                priorCredentialEncryptionKey != null);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int activeKeyVersion() {
        return activeKeyVersion;
    }

    public byte[] encrypt(CredentialBundle bundle, EncryptionContext ctx) {
        requireEnabled("persist");
        return encryptInternal(serialize(bundle), ctx.toAad(), activeKeyVersion);
    }

    public CredentialBundle decrypt(byte[] dbData, EncryptionContext ctx) {
        return decrypt(dbData, ctx, activeKeyVersion);
    }

    public CredentialBundle decrypt(byte[] dbData, EncryptionContext ctx, @Nullable Integer keyVersion) {
        int resolvedVersion =
                keyVersion != null ? keyVersion : priorKeyVersion != null ? priorKeyVersion : activeKeyVersion;
        return decrypt(dbData, ctx, resolvedVersion);
    }

    private CredentialBundle decrypt(byte[] dbData, EncryptionContext ctx, int keyVersion) {
        requireEnabled("decrypt");
        byte version = versionByte(dbData);
        if (version != FORMAT_VERSION_V2) throw unsupportedVersion(version);
        return deserialize(decryptInternal(dbData, ctx.toAad(), requireKey(keyVersion)));
    }

    private byte[] encryptInternal(byte[] plaintext, byte[] aad, int keyVersion) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            byte[] iv = new byte[GCM_IV_LENGTH];
            IV_GENERATOR.nextBytes(iv);
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(keyVersion), new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD(aad);
            byte[] cipherText = cipher.doFinal(plaintext);

            byte[] combined = new byte[1 + iv.length + cipherText.length];
            combined[0] = FORMAT_VERSION_V2;
            System.arraycopy(iv, 0, combined, 1, iv.length);
            System.arraycopy(cipherText, 0, combined, 1 + iv.length, cipherText.length);
            return combined;
        } catch (Exception e) {
            throw new EncryptionException("Credential encryption failed", e);
        }
    }

    private byte[] decryptInternal(byte[] dbData, byte[] aad, SecretKey key) {
        int headerLength = 1 + GCM_IV_LENGTH;
        if (dbData.length < headerLength + GCM_TAG_BYTES) {
            throw new EncryptionException("Credential ciphertext is truncated");
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] cipherText = new byte[dbData.length - headerLength];
            System.arraycopy(dbData, 1, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(dbData, headerLength, cipherText, 0, cipherText.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            cipher.updateAAD(aad);
            return cipher.doFinal(cipherText);
        } catch (Exception e) {
            throw new EncryptionException("Credential decryption failed", e);
        }
    }

    private static byte versionByte(byte[] dbData) {
        if (dbData.length == 0) throw new EncryptionException("Credential ciphertext is empty");
        return dbData[0];
    }

    private SecretKey requireKey(int version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new MissingCredentialKeyException(
                    "No encryption key configured for credential key version " + version);
        }
        return key;
    }

    private static SecretKey parseKey(String value, String property) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 32) {
            throw new IllegalArgumentException("hephaestus.security." + property + " must be exactly 32 UTF-8 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private static EncryptionException unsupportedVersion(byte version) {
        return new EncryptionException("Unsupported credential blob version: 0x" + Integer.toHexString(version & 0xFF));
    }

    private void requireEnabled(String operation) {
        if (!enabled) {
            throw new MissingCredentialKeyException(
                    "Credential encryption is disabled; cannot " + operation + " without a configured key");
        }
    }

    private byte[] serialize(CredentialBundle bundle) {
        try {
            return objectMapper.writeValueAsBytes(bundle);
        } catch (Exception e) {
            throw new EncryptionException("Failed to serialize credential bundle", e);
        }
    }

    private CredentialBundle deserialize(byte[] json) {
        try {
            return objectMapper.readValue(json, CredentialBundle.class);
        } catch (Exception e) {
            throw new EncryptionException("Failed to deserialize credential bundle", e);
        }
    }

    private static ObjectMapper testObjectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
