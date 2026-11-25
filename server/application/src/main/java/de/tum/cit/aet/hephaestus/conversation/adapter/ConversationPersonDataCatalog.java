package de.tum.cit.aet.hephaestus.conversation.adapter;

import de.tum.cit.aet.hephaestus.conversation.ConversationContextChanges;
import de.tum.cit.aet.hephaestus.conversation.ConversationEntryRepository;
import de.tum.cit.aet.hephaestus.conversation.ConversationRedaction;
import de.tum.cit.aet.hephaestus.conversation.spi.ConversationSandboxCopies;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ErasesColumns;
import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.workspace.spi.WorkspaceMemberships;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The person's own Hephaestus conversations, check-ins and contact counts are exported and deleted. Copies of the person's data in other people's
 * conversations are redacted: feedback Hephaestus showed about the person, and tool results that read the person's data.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
// The conversation store deletes the person's conversations through OWN_CONVERSATIONS. An entry or a turn references
// its conversation with the conversation's account and cascades on delete, so it goes with them.
@ErasesColumns({"conversation.account_id", "conversation_entry.account_id", "conversation_turn.account_id"})
@PersonDataStores({
    ConversationEntryRepository.COPIES_PERSON_DATA_STORE,
    "conversation_entry_feedback_copy",
    "conversation_entry",
    "conversation_turn",
    "conversation",
    "check_in",
    "account_meter"
})
public class ConversationPersonDataCatalog implements PersonDataCatalog {

    /** The person's conversations: the account's main conversations and their threads. */
    static final String OWN_CONVERSATIONS = "SELECT c.id FROM conversation c WHERE c.account_id = :account";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConversationRedaction redaction;
    private final List<PersonConversationCopySource> copySources;
    private final ConversationContextChanges contextChanges;
    private final ConversationSandboxCopies sandboxCopies;
    private final WorkspaceMemberships memberships;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new ConversationToolResultCopyPersonDataStore(
                        jdbc, mapper, redaction, contextChanges, sandboxCopies, memberships),
                new ConversationFeedbackCopyPersonDataStore(jdbc, mapper, redaction, copySources),
                new ConversationEntryPersonDataStore(
                        jdbc,
                        mapper,
                        "conversation_entry",
                        "t.kind IN ('USER','ANSWER','DIGEST') AND t.conversation_id IN (" + OWN_CONVERSATIONS + ")",
                        "id,conversation_id,seq,kind,surface,text,vote,slack_ts,created_at,edited_at,redacted_at",
                        -110),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "conversation_turn",
                        "conversation_turn",
                        "t.conversation_id IN (" + OWN_CONVERSATIONS + ")",
                        "id,conversation_id,surface,status,attempt,page_context,created_at,started_at,finished_at",
                        "id",
                        "",
                        -100),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "conversation",
                        "conversation",
                        "t.id IN (" + OWN_CONVERSATIONS + ")",
                        "id,workspace_id,account_id,parent_conversation_id,parent_entry_id,slack_team_id,slack_user_id,"
                                + "slack_channel_id,created_at,last_activity_at,last_read_at",
                        "id",
                        "",
                        -90),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "check_in",
                        "check_in",
                        "t.account_id = :account",
                        "id,workspace_id,account_id,kind,topic,place,state,created_at,state_changed_at",
                        "id",
                        "",
                        -80),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "account_meter",
                        "account_meter",
                        "t.account_id = :account",
                        "account_id,meter,local_date,count,last_at",
                        "account_id,meter",
                        "",
                        -80));
    }
}
