package de.tum.cit.aet.hephaestus.core.provenance;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSProvider;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.jca.JCAContext;
import com.nimbusds.jose.util.Base64URL;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Set;

/**
 * EdDSA over Ed25519 (RFC 8037) for Nimbus, with the JDK's own Ed25519. Nimbus's Ed25519 signer needs Google Tink,
 * which this application does not ship.
 */
final class Ed25519Jws {

    static final String JCA_ALGORITHM = "Ed25519";

    private Ed25519Jws() {}

    static JWSSigner signer(PrivateKey key) {
        return new Signer(key);
    }

    static JWSVerifier verifier(PublicKey key) {
        return new Verifier(key);
    }

    private abstract static class Provider implements JWSProvider {
        private final JCAContext context = new JCAContext();

        @Override
        public Set<JWSAlgorithm> supportedJWSAlgorithms() {
            return Set.of(JWSAlgorithm.EdDSA);
        }

        @Override
        public JCAContext getJCAContext() {
            return context;
        }

        static void requireEdDsa(JWSHeader header) throws JOSEException {
            if (!JWSAlgorithm.EdDSA.equals(header.getAlgorithm()))
                throw new JOSEException("Only EdDSA is accepted, not " + header.getAlgorithm());
        }
    }

    private static final class Signer extends Provider implements JWSSigner {
        private final PrivateKey key;

        Signer(PrivateKey key) {
            this.key = key;
        }

        @Override
        public Base64URL sign(JWSHeader header, byte[] signingInput) throws JOSEException {
            requireEdDsa(header);
            try {
                Signature signature = Signature.getInstance(JCA_ALGORITHM);
                signature.initSign(key);
                signature.update(signingInput);
                return Base64URL.encode(signature.sign());
            } catch (GeneralSecurityException e) {
                throw new JOSEException("Ed25519 signing failed", e);
            }
        }
    }

    private static final class Verifier extends Provider implements JWSVerifier {
        private final PublicKey key;

        Verifier(PublicKey key) {
            this.key = key;
        }

        @Override
        public boolean verify(JWSHeader header, byte[] signingInput, Base64URL signature) throws JOSEException {
            requireEdDsa(header);
            try {
                Signature verification = Signature.getInstance(JCA_ALGORITHM);
                verification.initVerify(key);
                verification.update(signingInput);
                return verification.verify(signature.decode());
            } catch (GeneralSecurityException e) {
                return false;
            }
        }
    }
}
