package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.core.privacy.spi.*;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({"chat_message_vote", "chat_message", "chat_thread"})
public class MentorPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "chat_message_vote",
                        "chat_message_vote",
                        "t.message_id IN (SELECT id FROM chat_message WHERE thread_id IN (SELECT id FROM chat_thread WHERE user_id IN (:users)))",
                        "message_id,created_at,is_upvoted,updated_at",
                        "message_id",
                        "",
                        -120),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "chat_message",
                        "chat_message",
                        "t.thread_id IN (SELECT id FROM chat_thread WHERE user_id IN (:users))",
                        "id,created_at,metadata,role,parent_message_id,thread_id,parts,version,status,llm_total_calls,llm_total_input_tokens,llm_total_output_tokens,llm_total_reasoning_tokens,llm_cache_read_tokens,llm_cache_write_tokens",
                        "id",
                        "",
                        -110),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "chat_thread",
                        "chat_thread",
                        "t.user_id IN (:users)",
                        "id,created_at,title,user_id,workspace_id,session_jsonl,surface",
                        "id",
                        "",
                        -90));
    }
}
