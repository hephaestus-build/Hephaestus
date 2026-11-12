package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorContextChanges;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
import de.tum.cit.aet.hephaestus.mentor.spi.ConversationSandboxCopies;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The person's own Heph conversations are exported and deleted. Copies of the person's data in other people's
 * conversations are redacted: feedback Heph showed about the person, and tool results that read the person's data.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    MentorEntryRepository.COPIES_PERSON_DATA_STORE,
    "mentor_entry_feedback_copy",
    "mentor_entry",
    "mentor_turn",
    "mentor_conversation"
})
public class MentorPersonDataCatalog implements PersonDataCatalog {

    /** The person's conversations: the account's main conversations and their threads. */
    static final String OWN_CONVERSATIONS = "SELECT c.id FROM mentor_conversation c WHERE c.account_id = :account";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MentorRedaction redaction;
    private final List<PersonConversationCopySource> copySources;
    private final MentorContextChanges contextChanges;
    private final ConversationSandboxCopies sandboxCopies;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new MentorToolResultCopyPersonDataStore(jdbc, mapper, redaction, contextChanges, sandboxCopies),
                new MentorFeedbackCopyPersonDataStore(jdbc, mapper, redaction, copySources),
                new MentorEntryPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_entry",
                        "t.kind IN ('USER','HEPH','DIGEST') AND t.conversation_id IN (" + OWN_CONVERSATIONS + ")",
                        "id,conversation_id,seq,kind,surface,text,vote,slack_ts,created_at,edited_at,redacted_at",
                        -110),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_turn",
                        "mentor_turn",
                        "t.conversation_id IN (" + OWN_CONVERSATIONS + ")",
                        "id,conversation_id,surface,status,attempt,page_context,created_at,started_at,finished_at",
                        "id",
                        "",
                        -100),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_conversation",
                        "mentor_conversation",
                        "t.id IN (" + OWN_CONVERSATIONS + ")",
                        "id,workspace_id,account_id,parent_conversation_id,parent_entry_id,slack_team_id,slack_user_id,"
                                + "slack_channel_id,created_at,last_activity_at,last_read_at",
                        "id",
                        "",
                        -90));
    }
}
