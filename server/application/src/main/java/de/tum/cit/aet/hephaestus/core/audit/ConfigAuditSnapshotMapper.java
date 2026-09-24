package de.tum.cit.aet.hephaestus.core.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import de.tum.cit.aet.hephaestus.core.audit.spi.ConfigAuditSnapshot;
import org.jspecify.annotations.Nullable;

/**
 * Serializes snapshots for the audit trail.
 *
 * <p>Deliberately a dedicated mapper rather than the shared application one. Two settings are
 * load-bearing and must not drift with global config:
 *
 * <ul>
 *   <li>{@link JsonInclude.Include#ALWAYS} — a null field is meaningful state, not an absent one.
 *       {@code PracticeReviewSettings} uses null for "inherit the fleet default", so under
 *       {@code NON_NULL} the snapshots {@code {cooldownMinutes: 30}} and {@code {}} would differ only
 *       by a key's presence, so "the admin cleared the override back to inherit" would silently vanish
 *       from the diff.</li>
 *   <li>ISO-8601 timestamps rather than epoch numbers, so a snapshot stays readable years later.</li>
 * </ul>
 *
 * <p>Records serialize their components in declaration order, which is what makes the diff and no-op
 * suppression meaningful. That determinism holds only up to the insert: {@code jsonb} renormalizes key
 * order at rest, so the bytes on disk are not the bytes compared.
 */
final class ConfigAuditSnapshotMapper {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .defaultPropertyInclusion(
                    JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
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
