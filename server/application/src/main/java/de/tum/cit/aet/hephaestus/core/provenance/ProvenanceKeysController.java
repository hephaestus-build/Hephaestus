package de.tum.cit.aet.hephaestus.core.provenance;

import de.tum.cit.aet.hephaestus.core.WorkspaceAgnostic;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public keys that verify provenance records, so anyone can check that this instance signed a record
 * without asking it. Retired keys stay listed, because the records they signed stay valid.
 */
@RestController
@ConditionalOnServerRole
@Tag(name = "Provenance", description = "Public keys that verify the provenance records of generated text")
public class ProvenanceKeysController {

    private final ProvenanceKeys keys;

    public ProvenanceKeysController(ProvenanceKeys keys) {
        this.keys = keys;
    }

    @GetMapping(value = "/.well-known/hephaestus-provenance-keys.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    @WorkspaceAgnostic("The instance's public keys, the same for every workspace")
    @Operation(summary = "Public keys of the provenance records", operationId = "getProvenanceKeys")
    public Map<String, Object> keys() {
        return keys.publicJwkSet().toJSONObject();
    }
}
