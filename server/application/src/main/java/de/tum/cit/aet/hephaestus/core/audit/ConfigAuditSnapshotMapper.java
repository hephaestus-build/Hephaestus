package de.tum.cit.aet.hephaestus.core.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

// Audit snapshots must preserve null fields, ISO timestamps, and field order independently of application JSON
// settings. The stored text is append-only history, so it keeps the format that Jackson 2 wrote:
// ConfigAuditSnapshotMapperTest pins it.
final class ConfigAuditSnapshotMapper {

    private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
            .changeDefaultPropertyInclusion(
                    inclusion -> JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private ConfigAuditSnapshotMapper() {}

    @Nullable
    static JsonNode toNode(@Nullable ConfigAuditSnapshot snapshot) {
        return snapshot == null ? null : MAPPER.valueToTree(snapshot);
    }

    @Nullable
    static String toJson(@Nullable JsonNode node) {
        return node == null ? null : node.toString();
    }
}
