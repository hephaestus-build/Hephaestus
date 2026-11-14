package de.tum.cit.aet.hephaestus.workspace;

import de.tum.cit.aet.hephaestus.core.security.StoredSystemSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * What an erased personal namespace keeps instead of its slug: a keyed hash, so nobody can take the slug while it is
 * not stored. The key is a stored system secret, so a system key rotation keeps these reservations. A token starts
 * with {@code ~}, which no slug contains.
 */
@Component
public class WorkspaceNamespaceReservation {

    /** The name of the HMAC key in {@code system_secret}. The 1.0 migration stores the key that 0.x derived. */
    public static final String KEY_NAME = "namespace-reservation";

    private static final String ALGORITHM = "HmacSHA256";

    private final StoredSystemSecrets storedSecrets;

    public WorkspaceNamespaceReservation(StoredSystemSecrets storedSecrets) {
        this.storedSecrets = storedSecrets;
    }

    /** The token that keeps an erased slug reserved; empty without a system key. Creates the key on first use. */
    public Optional<String> slugToken(String slug) {
        return storedSecrets
                .find(KEY_NAME)
                .or(() -> {
                    storedSecrets.ensure(KEY_NAME, 32);
                    return storedSecrets.find(KEY_NAME);
                })
                .map(key -> token(key, "slug", slug));
    }

    private static String token(byte[] key, String kind, String value) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            byte[] digest = mac.doFinal((kind + '\0' + value).getBytes(StandardCharsets.UTF_8));
            return "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
