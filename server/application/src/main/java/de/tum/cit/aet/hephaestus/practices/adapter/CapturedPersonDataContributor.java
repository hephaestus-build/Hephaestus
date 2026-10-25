package de.tum.cit.aet.hephaestus.practices.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonCopyIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Retained product custody keeps exact-person selection independent of runtime receipt lifetime. */
final class CapturedPersonDataContributor implements PersonDataContributor {
    private final PersonDataContributor store;
    private final PersonIdentityResolver identities;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    CapturedPersonDataContributor(
            PersonDataContributor store,
            PersonIdentityResolver identities,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper mapper) {
        this.store = store;
        this.identities = identities;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public String store() {
        return store.store();
    }

    @Override
    public int getOrder() {
        return store.getOrder();
    }

    @Override
    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
        var rows = store.export(selection, frozenScope);
        String ownerTable = switch (store.store()) {
            case "result", "problem", "result_mark", "review_item" -> "result";
            case "feedback",
                    "feedback_basis",
                    "feedback_send",
                    "feedback_response",
                    "feedback_copy",
                    "feedback_decision" -> "feedback";
            default -> "";
        };
        if (ownerTable.isEmpty() || rows.isEmpty()) return rows;
        String ownerColumn = store.store().equals(ownerTable) ? "developer_id" : ownerTable + "_id";
        Long accountId = frozenScope.accountId();
        Set<UUID> owned = accountId == null || ownerColumn.equals("developer_id")
                ? Set.of()
                : ownedReferences(rows, ownerTable, ownerColumn, accountId);
        var keys = selection.rows().getFirst().columns().keySet();
        return rows.stream()
                .map(row -> {
                    var reference = row.path(ownerColumn);
                    boolean own = accountId != null
                            && (ownerColumn.equals("developer_id")
                                    ? reference.asLong() == accountId
                                    : reference.isString() && owned.contains(UUID.fromString(reference.asString())));
                    if (own) return row;
                    var metadata = mapper.createObjectNode();
                    keys.forEach(key -> metadata.set(key, row.path(key)));
                    return metadata;
                })
                .toList();
    }

    private Set<UUID> ownedReferences(List<JsonNode> rows, String table, String column, long accountId) {
        var ids = rows.stream()
                .map(row -> row.path(column))
                .filter(JsonNode::isString)
                .map(row -> UUID.fromString(row.asString()))
                .collect(Collectors.toSet());
        if (ids.isEmpty()) return Set.of();
        return Set.copyOf(jdbc.query(
                "SELECT id FROM " + table
                        + " WHERE developer_id=:account AND id=ANY(:ids) AND workspace_id=ANY(:workspaces)",
                Map.of(
                        "account",
                        accountId,
                        "ids",
                        new SqlArrayValue("uuid", ids.toArray()),
                        "workspaces",
                        new SqlArrayValue(
                                "bigint",
                                rows.stream()
                                        .map(row -> row.path("workspace_id").asLong())
                                        .distinct()
                                        .toArray())),
                (row, index) -> Objects.requireNonNull(row.getObject("id", UUID.class))));
    }

    @Override
    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
        return store.erase(selection, frozenScope);
    }

    @Override
    public void prepareErasure(PersonDataSelection selection) {
        store.prepareErasure(selection);
    }

    @Override
    public List<ExternalDelivery> externalDeliveries(PersonDataSelection selection) {
        return store.externalDeliveries(selection);
    }

    @Override
    public PersonDataSelection select(PersonScope person) {
        if (person.identities().isEmpty()) return store.select(person);
        var providers = identities.providers();
        var copied = person.identities().stream()
                .flatMap(identity -> providers.stream()
                        .filter(provider -> provider.id() == identity.providerId())
                        .map(provider -> new PersonCopyIdentity(
                                provider.type(), provider.serverUrl(), identity.subject(), identity.teamId())))
                .distinct()
                .toList();
        if (copied.isEmpty()) return store.select(person);
        var jobs = new LinkedHashSet<>(person.derivedJobIds());
        jobs.addAll(jdbc.query(
                "SELECT DISTINCT captured.job_id FROM review_item member CROSS JOIN LATERAL (SELECT member.job_id"
                        + " UNION ALL SELECT source.value::uuid FROM"
                        + " jsonb_array_elements_text(COALESCE(member.detail->'custody'->'sourceJobIds','[]'::jsonb))"
                        + " source(value)) captured WHERE captured.job_id IS NOT NULL AND EXISTS(SELECT 1 FROM"
                        + " jsonb_array_elements(COALESCE(member.detail->'custody'->'copiedIdentities','[]'::jsonb)) copy,"
                        + " jsonb_array_elements(CAST(:copies AS jsonb)) person WHERE"
                        + " copy->>'providerType'=person->>'providerType' AND"
                        + " copy->>'providerOrigin'=person->>'providerOrigin' AND copy->>'subject'=person->>'subject' AND"
                        + " copy->>'teamId' IS NOT DISTINCT FROM person->>'teamId')",
                Map.of("copies", mapper.writeValueAsString(copied)),
                (rs, n) -> Objects.requireNonNull(rs.getObject(1, UUID.class))));
        return store.select(new PersonScope(
                person.accountId(),
                person.identities(),
                person.userIds(),
                person.conversationIds(),
                person.outlineDocumentIds(),
                person.scmArtifactIds(),
                List.copyOf(jobs)));
    }
}
