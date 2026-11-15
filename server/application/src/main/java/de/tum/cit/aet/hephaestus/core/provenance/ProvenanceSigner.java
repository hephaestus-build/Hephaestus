package de.tum.cit.aet.hephaestus.core.provenance;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.util.Base64URL;
import de.tum.cit.aet.hephaestus.core.release.RunningRelease;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/** Signs the provenance record of a text that Hephaestus generated. */
@NamedInterface("provenance")
@Component
@ConditionalOnServerRole
public class ProvenanceSigner {

    /** The JWS {@code typ} of every provenance record, so a record is never taken for another kind of token. */
    static final JOSEObjectType TYPE = new JOSEObjectType("hephaestus-provenance+jws");

    /** The version of the claim set in {@code v}. A verifier refuses a version it does not know. */
    static final int CLAIMS_VERSION = 1;

    private final ProvenanceKeys keys;
    private final String issuer;
    private final String version;

    // Package-private: Spring Modulith adds the types of a public constructor to the named interface, and the keys
    // must stay inside core.
    ProvenanceSigner(ProvenanceKeys keys, RunningRelease release, @Value("${hephaestus.webapp.url}") String issuer) {
        this.keys = keys;
        this.issuer = issuer;
        this.version = release.get().version();
    }

    /** A record and its compact JWS. */
    @NamedInterface("provenance")
    public record Signed(ProvenanceRecord record, String jws) {}

    /**
     * Signs the record of {@code text}.
     *
     * @param model the model that generated it, as its provider names it; {@code null} when no model was involved
     */
    public Signed sign(ProvenanceRecord.Kind kind, String text, @Nullable String model, Instant generatedAt) {
        var record = new ProvenanceRecord(
                UUID.randomUUID(),
                issuer,
                version,
                model,
                generatedAt,
                kind,
                Base64URL.encode(ProvenanceText.sha256(text)).toString());
        ProvenanceKeys.SigningKey key = keys.signingKey();
        var jws = new JWSObject(
                new JWSHeader.Builder(JWSAlgorithm.EdDSA)
                        .type(TYPE)
                        .keyID(key.kid())
                        .build(),
                new Payload(claims(record)));
        try {
            jws.sign(Ed25519Jws.signer(key.privateKey()));
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign a provenance record", e);
        }
        return new Signed(record, jws.serialize());
    }

    private static Map<String, Object> claims(ProvenanceRecord record) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("v", CLAIMS_VERSION);
        claims.put("iss", record.issuer());
        claims.put("id", record.id().toString());
        claims.put("gen", ProvenanceRecord.GENERATOR);
        claims.put("ver", record.version());
        if (record.model() != null) claims.put("model", record.model());
        claims.put("at", record.generatedAt().toString());
        claims.put("kind", record.kind().name());
        claims.put("sha256", record.contentSha256());
        return claims;
    }
}
