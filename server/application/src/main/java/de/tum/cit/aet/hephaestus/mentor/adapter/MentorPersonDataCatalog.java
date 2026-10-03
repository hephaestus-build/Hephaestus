package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "chat_message_vote",
    "chat_message",
    "chat_thread",
    "chat_message_feedback_copy",
    "chat_thread_feedback_runtime_copy",
    "chat_thread_runtime_journal"
})
public class MentorPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final List<PersonConversationCopySource> copySources;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new MentorFeedbackCopyPersonDataStore(jdbc, mapper, copySources, false),
                new MentorFeedbackCopyPersonDataStore(jdbc, mapper, copySources, true),
                new MentorRuntimeJournalPersonDataStore(jdbc, mapper),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "chat_message_vote",
                        "chat_message_vote",
                        "t.message_id IN (SELECT id FROM chat_message WHERE thread_id IN (SELECT id FROM chat_thread WHERE user_id = ANY(:users)))",
                        "message_id,created_at,is_upvoted,updated_at",
                        "message_id",
                        "",
                        -120),
                new MentorMessagePersonDataStore(jdbc, mapper),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "chat_thread",
                        "chat_thread",
                        "t.user_id = ANY(:users)",
                        "id,created_at,title,user_id,workspace_id,surface",
                        "id",
                        "",
                        -90));
    }
}
