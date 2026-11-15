package de.tum.cit.aet.hephaestus.core.provenance;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSObject;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.text.ParseException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Component;

/** Reads a provenance record back and checks that this instance signed it. */
@NamedInterface("provenance")
@Component
@ConditionalOnServerRole
public class ProvenanceVerifier {

    private final ProvenanceKeys keys;
    private final String issuer;

    // Package-private: Spring Modulith adds the types of a public constructor to the named interface, and the keys
    // must stay inside core.
    ProvenanceVerifier(ProvenanceKeys keys, @Value("${hephaestus.webapp.url}") String issuer) {
        this.keys = keys;
        this.issuer = issuer;
    }

    /**
     * The record that {@code jws} carries, when one of this instance's keys signed it and its claims are complete.
     * Anything else, such as another instance's record, a changed claim or an unknown claim version, is empty.
     */
    public Optional<ProvenanceRecord> verify(String jws) {
        try {
            JWSObject object = JWSObject.parse(jws.strip());
            if (!ProvenanceSigner.TYPE.equals(object.getHeader().getType())) return Optional.empty();
            String kid = object.getHeader().getKeyID();
            if (kid == null) return Optional.empty();
            var key = keys.publicKey(kid);
            if (key.isEmpty() || !object.verify(Ed25519Jws.verifier(key.get()))) return Optional.empty();
            return record(object.getPayload().toJSONObject());
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
    }

    private Optional<ProvenanceRecord> record(Map<String, Object> claims) {
        if (!(claims.get("v") instanceof Long claimsVersion) || claimsVersion != ProvenanceSigner.CLAIMS_VERSION)
            return Optional.empty();
        if (!issuer.equals(claims.get("iss")) || !ProvenanceRecord.GENERATOR.equals(claims.get("gen")))
            return Optional.empty();
        if (!(claims.get("id") instanceof String id)
                || !(claims.get("ver") instanceof String release)
                || !(claims.get("at") instanceof String at)
                || !(claims.get("kind") instanceof String kind)
                || !(claims.get("sha256") instanceof String sha256)) return Optional.empty();
        Object model = claims.get("model");
        if (model != null && !(model instanceof String)) return Optional.empty();
        try {
            return Optional.of(new ProvenanceRecord(
                    UUID.fromString(id),
                    issuer,
                    release,
                    (String) model,
                    Instant.parse(at),
                    ProvenanceRecord.Kind.valueOf(kind),
                    sha256));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
