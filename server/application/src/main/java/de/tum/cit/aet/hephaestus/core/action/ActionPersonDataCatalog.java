package de.tum.cit.aet.hephaestus.core.action;

import de.tum.cit.aet.hephaestus.core.auth.spi.AccountErasureContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Erases the actions that address an account. Deleting a pending action cancels it: the executor finds no row, and
 * an attempt in flight records nothing. A write that already passed its fence still reaches the provider, as it
 * would have a moment earlier.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"agent_action"})
public class ActionPersonDataCatalog implements PersonDataCatalog, AccountErasureContributor {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(new JdbcPersonDataStore(
                jdbc,
                mapper,
                "agent_action",
                "agent_action",
                "t.subject_account_id = :account",
                "id,workspace_id,kind,state,payload,readback_ref,stop_reason,created_at,ended_at",
                "id",
                "",
                400));
    }

    @Override
    @Transactional
    public void eraseAccount(long accountId) {
        jdbc.update("DELETE FROM agent_action WHERE subject_account_id = :account", Map.of("account", accountId));
    }
}
