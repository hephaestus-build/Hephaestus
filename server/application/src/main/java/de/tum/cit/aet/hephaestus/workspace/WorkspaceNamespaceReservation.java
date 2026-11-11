package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.security.SystemEncryptionKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * What an erased personal namespace keeps instead of its slug: a keyed hash, so nobody can take the slug while it is
 * not stored. The key derives from the system encryption key, so a new system key releases these reservations. A
 * token starts with {@code ~}, which no slug contains.
 */
@Component
public class WorkspaceNamespaceReservation {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] PURPOSE = "hephaestus erased workspace namespace v1".getBytes(StandardCharsets.UTF_8);

    private final @Nullable SecretKey key;

    public WorkspaceNamespaceReservation(SystemEncryptionKey systemKey) {
        SecretKey system = systemKey.key();
        this.key = system == null
                ? null
                : new SecretKeySpec(mac(new SecretKeySpec(system.getEncoded(), ALGORITHM), PURPOSE), ALGORITHM);
    }

    /** The token that keeps an erased slug reserved; empty without a system key. */
    public Optional<String> slugToken(String slug) {
        return token("slug", slug);
    }

    private Optional<String> token(String kind, String value) {
        if (key == null) return Optional.empty();
        byte[] digest = mac(key, (kind + '\0' + value).getBytes(StandardCharsets.UTF_8));
        return Optional.of("~" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    }

    private static byte[] mac(SecretKey key, byte[] input) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
