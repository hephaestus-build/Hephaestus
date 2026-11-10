package de.tum.cit.aet.hephaestus.agent.attempt;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.UUID;

/**
 * The key that this server signs its attempt tokens with. Its private half lives only in this process's memory, so a
 * restart rotates it. {@link AttemptKeyring} publishes its public half, and any server can then verify the tokens.
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
