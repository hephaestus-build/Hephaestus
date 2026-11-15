package de.tum.cit.aet.hephaestus.core.provenance;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jose.util.Base64URL;
import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.core.security.EncryptionException;
import de.tum.cit.aet.hephaestus.core.security.SystemSecretOwner;
import de.tum.cit.aet.hephaestus.core.security.SystemSecrets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The instance's provenance signing keys. The first one is made at start, under a cluster lock, and sealed by the
 * system key. Each read goes to the database: the table holds a handful of rows, and a replica must see a key that
 * another replica made at once.
 */
@Service
@ConditionalOnServerRole
@WorkspaceAgnostic("Provenance signing keys belong to the instance, not to a workspace")
public class ProvenanceKeys implements SystemSecretOwner {

    private static final Logger log = LoggerFactory.getLogger(ProvenanceKeys.class);

    /** An Ed25519 X.509 SubjectPublicKeyInfo is a fixed 12-byte prefix followed by the 32-byte public key. */
    private static final int RAW_PUBLIC_KEY_LENGTH = 32;

    private final ProvenanceSigningKeyRepository repository;
    private final SystemSecrets secrets;
    private final Clock clock;

    public ProvenanceKeys(ProvenanceSigningKeyRepository repository, SystemSecrets secrets, Clock clock) {
        this.repository = repository;
        this.secrets = secrets;
        this.clock = clock;
    }

    /** The key that signs now. */
    record SigningKey(String kid, PrivateKey privateKey) {}

    /**
     * Makes the first signing key unless one exists. Only the start-up bootstrap calls it ({@code ProvenanceStartupConfiguration}),
     * so a key has one way into the table, and the cluster lock lets one server make it.
     */
    @Transactional
    public void ensureSigningKey() {
        if (repository.count() > 0) return;
        repository.acquireBootstrapLock();
        if (repository.count() > 0) return;
        try {
            KeyPair pair =
                    KeyPairGenerator.getInstance(Ed25519Jws.JCA_ALGORITHM).generateKeyPair();
            String kid = UUID.randomUUID().toString();
            byte[] privateKey = pair.getPrivate().getEncoded();
            try {
                repository.save(new ProvenanceSigningKey(
                        kid, pair.getPublic().getEncoded(), privateKey, clock.instant(), secrets));
            } finally {
                Arrays.fill(privateKey, (byte) 0);
            }
            log.info("Created provenance signing key: kid={}", kid);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Every Java runtime provides Ed25519", e);
        }
    }

    /**
     * Opens every stored key, so a server whose system key differs from the one that sealed them stops at start with
     * the identifiers involved, instead of failing at its first signature.
     */
    void verifyAllOpen() {
        for (ProvenanceSigningKey key : repository.findAll()) {
            try {
                key.privateKey(secrets);
            } catch (EncryptionException mismatch) {
                throw new IllegalStateException(
                        "hephaestus.security.encryption-key does not open the stored provenance signing key "
                                + key.getKid() + ": " + key.sealingMismatch(secrets)
                                + ". Restore the system key that sealed it, as docs/admin/secret-custody.mdx describes.",
                        mismatch);
            }
        }
    }

    @Override
    @Transactional
    public int resealWithActiveKey(SystemSecrets secrets) {
        int resealed = 0;
        for (ProvenanceSigningKey key : repository.lockAllForKeyRotation()) {
            if (key.resealWithActiveKey(secrets)) resealed++;
        }
        return resealed;
    }

    // Package-private, so no module outside provenance can reach a private key; the repository call is its own
    // read-only transaction.
    SigningKey signingKey() {
        ProvenanceSigningKey key = repository
                .findFirstByOrderByCreatedAtDescKidDesc()
                .orElseThrow(
                        () -> new IllegalStateException(
                                "No provenance signing key exists, so Hephaestus cannot sign the text it generates. The"
                                        + " start-up bootstrap makes the key; a database whose keys were deleted needs a restart."));
        byte[] encoded = key.privateKey(secrets);
        try {
            PrivateKey privateKey =
                    KeyFactory.getInstance(Ed25519Jws.JCA_ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(encoded));
            return new SigningKey(key.getKid(), privateKey);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The provenance signing key " + key.getKid() + " is not an Ed25519 key", e);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    Optional<PublicKey> publicKey(String kid) {
        return repository.findById(kid).map(ProvenanceKeys::publicKeyOf);
    }

    /** Every public key, newest first, as a JWK set with no private part. */
    @Transactional(readOnly = true)
    public JWKSet publicJwkSet() {
        List<JWK> keys = repository.findAll().stream()
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .<JWK>map(key -> new OctetKeyPair.Builder(Curve.Ed25519, Base64URL.encode(rawPublicKey(key)))
                        .keyID(key.getKid())
                        .keyUse(KeyUse.SIGNATURE)
                        .issueTime(Date.from(key.getCreatedAt()))
                        .build())
                .toList();
        return new JWKSet(keys);
    }

    private static PublicKey publicKeyOf(ProvenanceSigningKey key) {
        try {
            return KeyFactory.getInstance(Ed25519Jws.JCA_ALGORITHM)
                    .generatePublic(new X509EncodedKeySpec(key.getPublicKey()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("The provenance key " + key.getKid() + " is not an Ed25519 key", e);
        }
    }

    private static byte[] rawPublicKey(ProvenanceSigningKey key) {
        byte[] encoded = key.getPublicKey();
        return Arrays.copyOfRange(encoded, encoded.length - RAW_PUBLIC_KEY_LENGTH, encoded.length);
    }
}
