package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The mentor's intake rows hold references and metadata, never message text. Export lists them; erasure deletes them,
 * events and requests before the sources they reference. The person-data job binds the owner, so the row policies
 * admit these predicates.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"mentor_event", "mentor_request", "mentor_source"})
// The stamp's native actor is the verified asker, the request's own account: erasing the account's rows erases it.
@ErasesColumns("mentor_request.scope_native_actor_id")
public class MentorPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                store(
                        "mentor_event",
                        "id,account_id,binding_id,native_event_id,kind,source_id,request_id,disposition,reason,"
                                + "occurred_at,received_at",
                        396),
                store(
                        "mentor_request",
                        "id,account_id,source_id,version,state,audience_transport,audience_connection_id,"
                                + "audience_container_id,audience_thread_root,scope_authority,opened_at,deadline",
                        397),
                store(
                        "mentor_source",
                        "id,account_id,binding_id,container_id,native_message_id,producer,revision,state",
                        398));
    }

    private JdbcPersonDataStore store(String table, String columns, int order) {
        return new JdbcPersonDataStore(jdbc, mapper, table, table, "t.account_id = :account", columns, "id", "", order);
    }
}
