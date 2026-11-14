package de.tum.cit.aet.hephaestus.core.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Seals the values that the system key protects: login provider client secrets, AI provider API keys, JWT signing keys
 * and the erased-namespace reservation key. ADR 0104 owns the design.
 *
 * <p>Each sealed value names the key that sealed it and is bound to its row, so a value copied to another row or column
 * does not open. Sealing uses the active key; opening uses the key that the value names, which may be the prior key
 * during a rotation. A key's identifier derives from the key itself, so a configuration cannot pair a key with the
 * identifier of another key.
 *
 * <pre>
 *   [0]       FORMAT (0x02)
 *   [1..5)    key id: the first 4 bytes of HMAC-SHA256(key, KEY_ID_LABEL)
 *   [5..17)   GCM nonce
 *   [17..]    ciphertext || 16-byte GCM tag
 *   AAD:      DOMAIN || bytes [0..5) || for each binding part: u16 length || UTF-8
 * </pre>
 */
@Component
public final class SystemSecrets {

    private static final Logger log = LoggerFactory.getLogger(SystemSecrets.class);

    static final byte FORMAT = 0x02;
    private static final int HEADER_LENGTH = 1 + Integer.BYTES;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int MINIMUM_LENGTH = HEADER_LENGTH + GCM_IV_LENGTH + GCM_TAG_BITS / Byte.SIZE;
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final byte[] DOMAIN = "hephaestus-system-secret\u001f".getBytes(StandardCharsets.UTF_8);
    private static final SecureRandom NONCES = new SecureRandom();
    private static final byte[] KEY_ID_LABEL = "hephaestus-system-key-id".getBytes(StandardCharsets.UTF_8);

    private final Map<Integer, SecretKey> keys;
    private final int activeId;

    public SystemSecrets(SecurityProperties properties, Environment environment) {
        String active = properties.encryptionKey();
        if (active == null) {
            if (environment.matchesProfiles("prod")) {
                throw new IllegalStateException(
                        "An encryption key is required in production. Set hephaestus.security.encryption-key.");
            }
            if (environment.matchesProfiles("specs", "cds-training")) {
                log.debug("System encryption is unavailable during artifact generation: reason=missing_key");
            } else {
                log.warn("Skipped encryption configuration: reason=missing_key, "
                        + "action=set_hephaestus_security_encryption_key_in_production");
            }
            this.keys = Map.of();
            this.activeId = 0;
            return;
        }
        Map<Integer, SecretKey> configured = new HashMap<>();
        SecretKey activeKey = parseKey(active, "encryption-key");
        this.activeId = keyId(activeKey);
        configured.put(activeId, activeKey);
        String prior = properties.priorEncryptionKey();
        if (prior != null) {
            SecretKey priorKey = parseKey(prior, "prior-encryption-key");
            if (configured.putIfAbsent(keyId(priorKey), priorKey) != null) {
                throw new IllegalArgumentException("The active and prior system encryption keys must differ");
            }
        }
        this.keys = Map.copyOf(configured);
        log.info(
                "Enabled system encryption: activeKeyId={}, priorKeyConfigured={}", keyIdText(activeId), prior != null);
    }

    /** Whether a system key is configured. Without one, nothing can be sealed or opened. */
    public boolean isEnabled() {
        return !keys.isEmpty();
    }

    /**
     * Why {@code sealed} does not open with the configured keys, for a message to the operator: the identifier of the
     * key that sealed it and the identifiers that this server holds. Identifiers derive from the keys by HMAC, so the
     * text never reveals a key.
     */
    public String describeMismatch(byte[] sealed) {
        int stored;
        try {
            stored = keyId(sealed);
        } catch (EncryptionException notSealed) {
            return "the value is not a sealed system secret";
        }
        if (keys.containsKey(stored)) {
            return "the value names the configured key " + keyIdText(stored)
                    + " but does not open with it: it is damaged, or it belongs to another row";
        }
        String held = keys.isEmpty()
                ? "no system key"
                : "the active key " + keyIdText(activeId)
                        + keys.keySet().stream()
                                .filter(id -> id != activeId)
                                .findFirst()
                                .map(id -> " and the prior key " + keyIdText(id))
                                .orElse(" and no prior key");
        return "the value was sealed by the system key with id " + keyIdText(stored) + ", and this server holds "
                + held;
    }

    /** The identifier of the active key, as hex, for logs. Never the key. */
    public String activeKeyId() {
        return keyIdText(activeId);
    }

    /** Seals {@code plaintext} under the active key, bound to {@code binding}. */
    public byte[] seal(byte[] plaintext, SecretBinding binding) {
        SecretKey key = requireKey(activeId);
        byte[] header = header(activeId);
        byte[] nonce = new byte[GCM_IV_LENGTH];
        NONCES.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(aad(header, binding));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(header.length + nonce.length + ciphertext.length)
                    .put(header)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new EncryptionException("Sealing a system secret failed", e);
        }
    }

    /** Opens a value that {@link #seal} produced for the same {@code binding}, under whichever key it names. */
    public byte[] open(byte[] sealed, SecretBinding binding) {
        int id = keyId(sealed);
        SecretKey key = requireKey(id);
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, sealed, HEADER_LENGTH, GCM_IV_LENGTH));
            cipher.updateAAD(aad(Arrays.copyOf(sealed, HEADER_LENGTH), binding));
            int offset = HEADER_LENGTH + GCM_IV_LENGTH;
            return cipher.doFinal(sealed, offset, sealed.length - offset);
        } catch (GeneralSecurityException e) {
            // GCM reports a wrong key, a tampered value and a value from another row the same way.
            throw new EncryptionException("A system secret did not open for " + binding.column(), e);
        }
    }

    /** {@link #seal} for a text column: the sealed bytes as Base64. */
    public String sealText(String plaintext, SecretBinding binding) {
        return Base64.getEncoder().encodeToString(seal(plaintext.getBytes(StandardCharsets.UTF_8), binding));
    }

    /** {@link #open} for a text column that {@link #sealText} wrote. */
    public String openText(String sealed, SecretBinding binding) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(sealed);
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("A system secret in " + binding.column() + " is not a sealed value", e);
        }
        return new String(open(bytes, binding), StandardCharsets.UTF_8);
    }

    /** Whether {@code sealed} names the active key, so a rotation does not need to seal it again. */
    public boolean isSealedWithActiveKey(byte[] sealed) {
        return keyId(sealed) == activeId;
    }

    /** {@link #isSealedWithActiveKey} for a text column. */
    public boolean isSealedWithActiveKey(String sealed) {
        try {
            return isSealedWithActiveKey(Base64.getDecoder().decode(sealed));
        } catch (IllegalArgumentException e) {
            throw new EncryptionException("A system secret is not a sealed value", e);
        }
    }

    private static int keyId(byte[] sealed) {
        if (sealed.length < MINIMUM_LENGTH) {
            throw new EncryptionException("A system secret is truncated");
        }
        if (sealed[0] != FORMAT) {
            throw new EncryptionException(
                    "A system secret has the unknown format 0x" + Integer.toHexString(sealed[0] & 0xFF));
        }
        return ByteBuffer.wrap(sealed, 1, Integer.BYTES).getInt();
    }

    private SecretKey requireKey(int id) {
        if (keys.isEmpty()) {
            throw new EncryptionException("A system encryption key is required to read or persist secrets.");
        }
        SecretKey key = keys.get(id);
        if (key == null) {
            throw new EncryptionException("No configured system encryption key has the key id " + keyIdText(id)
                    + ". Configure the key that sealed this value as the active or the prior key.");
        }
        return key;
    }

    private static int keyId(SecretKey key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getEncoded(), "HmacSHA256"));
            return ByteBuffer.wrap(mac.doFinal(KEY_ID_LABEL), 0, Integer.BYTES).getInt();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }

    private static String keyIdText(int id) {
        return HexFormat.of().toHexDigits(id);
    }

    private static byte[] header(int id) {
        return ByteBuffer.allocate(HEADER_LENGTH).put(FORMAT).putInt(id).array();
    }

    private static byte[] aad(byte[] header, SecretBinding binding) {
        List<byte[]> parts = binding.encodedParts();
        int length = DOMAIN.length + header.length;
        for (byte[] part : parts) length += Short.BYTES + part.length;
        ByteBuffer aad = ByteBuffer.allocate(length).put(DOMAIN).put(header);
        for (byte[] part : parts) aad.putShort((short) part.length).put(part);
        return aad.array();
    }

    static SecretKey parseKey(String value, String property) {
        // Validate bytes, not characters: a 32-character key with a multibyte character is longer than 32 bytes.
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != 32) {
            // Report both counts, never the key. "32 bytes" alone misleads an operator whose 32-character
            // passphrase holds an umlaut; "32 characters" alone is wrong for that operator.
            int characters = value.length();
            String howToFix = bytes.length == characters
                    ? "For ASCII, bytes and characters are the same, so that is 32 characters: "
                    : "This key contains non-ASCII characters, and those cost more than one byte each. "
                            + "Use ASCII only, exactly 32 characters: ";
            throw new IllegalArgumentException(
                    "hephaestus.security." + property + " must be a 32-byte AES-256 key. Got "
                            + bytes.length + " bytes from " + characters + " characters. " + howToFix
                            + "openssl rand -hex 16");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
