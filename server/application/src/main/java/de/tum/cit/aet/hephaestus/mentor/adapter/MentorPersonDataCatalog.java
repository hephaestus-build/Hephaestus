package de.tum.cit.aet.hephaestus.mentor.adapter;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonConversationCopySource;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryRepository;
import de.tum.cit.aet.hephaestus.mentor.MentorRedaction;
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

    /** The person's conversations: their own by actor, or the main of an exact Slack identity, and its threads. */
    static final String OWN_CONVERSATIONS = """
            SELECT c.id FROM mentor_conversation c
            WHERE c.user_id = ANY(:users) OR COALESCE(c.parent_conversation_id, c.id) IN (
                SELECT m.id FROM mentor_conversation m
                JOIN jsonb_to_recordset(CAST(:identities AS jsonb)) AS i("providerId" bigint, subject text, "teamId" text)
                    ON i.subject = m.slack_user_id AND i."teamId" = m.slack_team_id
                JOIN identity_provider p ON p.id = i."providerId"
                WHERE p.type = 'SLACK' AND p.server_url = 'https://slack.com')
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MentorRedaction redaction;
    private final List<PersonConversationCopySource> copySources;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new MentorToolResultCopyPersonDataStore(jdbc, mapper, redaction),
                new MentorFeedbackCopyPersonDataStore(jdbc, mapper, redaction, copySources),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_entry",
                        "mentor_entry",
                        "t.kind IN ('USER','HEPH','DIGEST') AND t.conversation_id IN (" + OWN_CONVERSATIONS + ")",
                        "id,conversation_id,seq,kind,surface,text,vote,slack_ts,created_at,edited_at,redacted_at",
                        "id",
                        "",
                        -110),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_turn",
                        "mentor_turn",
                        "t.conversation_id IN (" + OWN_CONVERSATIONS + ")",
                        "id,conversation_id,surface,status,attempt,page_context,input_tokens,output_tokens,cached_tokens,"
                                + "cache_write_tokens,reasoning_tokens,llm_calls,cost_usd,created_at,started_at,"
                                + "finished_at",
                        "id",
                        "",
                        -100),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_conversation",
                        "mentor_conversation",
                        "t.id IN (" + OWN_CONVERSATIONS + ")",
                        "id,workspace_id,user_id,parent_conversation_id,parent_entry_id,slack_team_id,slack_user_id,"
                                + "slack_channel_id,created_at,last_activity_at,last_read_at",
                        "id",
                        "",
                        -90));
    }
}
