package de.tum.cit.aet.hephaestus.agent.attempt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * The key that this worker signs and verifies its attempt tokens with. A worker verifies only the tokens that it issued
 * itself: each sandbox calls the gateway of the worker that runs its attempt. The key therefore lives in memory, and a
 * restarted worker's new key ends every token of the attempts that it no longer runs.
 *
 * @param kid the token's {@code kid} header
 */
public record AttemptSigningKey(String kid, RSAPublicKey publicKey, RSAPrivateKey privateKey) {

    public static AttemptSigningKey generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new AttemptSigningKey(
                    UUID.randomUUID().toString(), (RSAPublicKey) pair.getPublic(), (RSAPrivateKey) pair.getPrivate());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA key generation unavailable", e);
        }
    }
}
