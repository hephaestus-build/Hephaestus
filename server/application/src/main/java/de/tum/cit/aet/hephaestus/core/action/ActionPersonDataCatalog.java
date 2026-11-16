package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentity;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Erases actions addressed to an account or to an exact provider identity, and anonymizes the account in other
 * recipients' retained role notices under the personal-data policy. Deleting a pending action cancels it: the executor
 * finds no row, and an attempt in flight records nothing. A write that already passed its fence still reaches the
 * provider, as it would have a moment earlier.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"agent_action"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActionPersonDataCatalog implements PersonDataCatalog, AccountErasureContributor {
    /** The rows that belong to the person: addressed to the account, or to one of the exact frozen native identities. */
    private static final String OWNED = """
            t.id IN (
              SELECT a.id FROM agent_action a WHERE a.subject_account_id = :account
              UNION
              SELECT a.id FROM jsonb_to_recordset(CAST(:identities AS jsonb))
                AS i("providerId" bigint, subject text, "teamId" text)
              JOIN agent_action a ON a.native_recipient IS NOT NULL
                AND a.native_recipient->>'subject' = i.subject
                AND CAST(a.native_recipient->>'providerId' AS bigint) = i."providerId"
                AND a.native_recipient->>'teamId' IS NOT DISTINCT FROM i."teamId")
            """;

    /** Another recipient's retained role notice that names the account as its subject or actor. */
    private static final String ROLE_REFERENCE = """
            t.kind = 'EMAIL' AND t.payload ->> 'kind' = 'ROLE_CHANGED' AND
              (t.payload #>> '{event,change,accountId}' = CAST(:account AS text) OR
               t.payload #>> '{event,change,actorAccountId}' = CAST(:account AS text))
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "agent_action",
                        "agent_action",
                        "(" + OWNED + ") OR (" + ROLE_REFERENCE + ")",
                        "id,subject_account_id,workspace_id,kind,state,payload,native_recipient,readback_ref,stop_reason,created_at,ended_at",
                        "id",
                        "",
                        // Before the stores whose rows an action's end locks, such as a product-feedback filing.
                        398) {
                    @Override
                    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
                        return super.export(selection, frozenScope).stream()
                                .map(row -> owns(row, frozenScope) ? row : proof(row))
                                .toList();
                    }

                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        return eraseMatching(JdbcPersonDataStore.parameters(frozenScope, mapper));
                    }
                });
    }

    /** Selected rows only: a row that is neither addressed nor natively owned was selected as a role reference. */
    private boolean owns(JsonNode row, PersonScope scope) {
        Long accountId = scope.accountId();
        if (accountId != null && row.path("subject_account_id").asLong(-1) == accountId) return true;
        JsonNode recipient = row.path("native_recipient");
        return recipient.isObject() && scope.identities().contains(mapper.treeToValue(recipient, PersonIdentity.class));
    }

    /** A notice to someone else contributes cleanup proof, not its recipient or payload. */
    private JsonNode proof(JsonNode row) {
        var proof = mapper.createObjectNode();
        for (String field : List.of("id", "kind", "state")) proof.set(field, row.path(field));
        return proof;
    }

    // Not named erase: inside the anonymous store, the inherited erase(...) would shadow it by name.
    private long eraseMatching(Map<String, ?> bindings) {
        // Delete first, so that a row which is both owned and a role reference counts once.
        int removed = jdbc.update("DELETE FROM agent_action t WHERE " + OWNED, bindings);
        int anonymized = jdbc.update("""
                UPDATE agent_action t SET payload = jsonb_set(t.payload, '{event,change}',
                    redact_role_change(t.payload #> '{event,change}', :account))
                WHERE\s""" + ROLE_REFERENCE, bindings);
        return (long) removed + anonymized;
    }

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        String identities = jdbc.queryForObject("""
                SELECT COALESCE(jsonb_agg(jsonb_build_object('providerId', provider_id,
                    'subject', provider_user_key, 'teamId', NULLIF(team_key, ''))), '[]'::jsonb)::text
                FROM identity WHERE account_id = :account
                """, Map.of("account", accountId), String.class);
        eraseMatching(Map.of("account", accountId, "identities", Objects.requireNonNull(identities)));
    }
}
