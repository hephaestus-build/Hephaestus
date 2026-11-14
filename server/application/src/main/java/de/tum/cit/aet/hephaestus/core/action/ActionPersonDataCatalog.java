package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Erases addressed actions and anonymizes retained role-notice identities under the personal-data policy.
 * Deleting a pending action cancels it: the executor finds no row, and
 * an attempt in flight records nothing. A write that already passed its fence still reaches the provider, as it
 * would have a moment earlier.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"agent_action"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActionPersonDataCatalog implements PersonDataCatalog, AccountErasureContributor {
    private static final String ACCOUNT_REFERENCE = """
            t.subject_account_id = :account OR
            (t.kind = 'EMAIL' AND t.payload ->> 'kind' = 'ROLE_CHANGED' AND
              (t.payload #>> '{event,change,accountId}' = CAST(:account AS text) OR
               t.payload #>> '{event,change,actorAccountId}' = CAST(:account AS text)))
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
                        ACCOUNT_REFERENCE,
                        "id,subject_account_id,workspace_id,kind,state,payload,readback_ref,stop_reason,created_at,ended_at",
                        "id",
                        "",
                        // Before the stores whose rows an action's end locks, such as a product-feedback filing.
                        398) {
                    @Override
                    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
                        return super.export(selection, frozenScope).stream()
                                .map(row -> {
                                    if (Long.valueOf(row.path("subject_account_id")
                                                    .asLong(-1))
                                            .equals(frozenScope.accountId())) return row;
                                    // A notice to someone else contributes cleanup proof, not its recipient or payload.
                                    var proof = mapper.createObjectNode();
                                    for (String field : List.of("id", "kind", "state"))
                                        proof.set(field, row.path(field));
                                    return proof;
                                })
                                .toList();
                    }

                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        Long accountId = frozenScope.accountId();
                        return accountId == null ? 0 : eraseForAccount(accountId);
                    }
                });
    }

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        eraseForAccount(accountId);
    }

    private long eraseForAccount(long accountId) {
        var bindings = Map.of("account", accountId);
        int removed = jdbc.update("DELETE FROM agent_action WHERE subject_account_id = :account", bindings);
        int anonymized = jdbc.update("""
                UPDATE agent_action t SET payload = jsonb_set(t.payload, '{event,change}',
                    redact_role_change(t.payload #> '{event,change}', :account))
                WHERE t.kind = 'EMAIL' AND t.payload ->> 'kind' = 'ROLE_CHANGED'
                  AND (t.payload #>> '{event,change,accountId}' = CAST(:account AS text)
                    OR t.payload #>> '{event,change,actorAccountId}' = CAST(:account AS text))
                """, bindings);
        return (long) removed + anonymized;
    }
}
