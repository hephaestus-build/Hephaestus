package de.tum.cit.aet.hephaestus.integration.slack.adapter;

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
@PersonDataStores({
    "slack_thread",
    "slack_message",
    "mentor_slack_thread",
    "slack_participant_consent",
    "slack_channel_consent_event",
    "slack_monitored_channel"
})
public class IntegrationSlackPersonDataCatalog implements PersonDataCatalog {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new SlackThreadPersonDataContributor(jdbc, mapper),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "slack_message",
                        "slack_message",
                        "t.author_member_id = ANY(:users) OR EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i(\"providerId\" bigint,subject text,\"teamId\" text) JOIN identity_provider p ON p.id=i.\"providerId\" WHERE p.type='SLACK' AND p.server_url='https://slack.com' AND i.subject=t.author_slack_user_id AND i.\"teamId\"=t.slack_team_id)",
                        "id,workspace_id,slack_team_id,slack_channel_id,slack_ts,slack_thread_ts,author_slack_user_id,text,edited_at,deleted_at,ingested_at,author_member_id",
                        "id",
                        "",
                        -60),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mentor_slack_thread",
                        "mentor_slack_thread",
                        "t.chat_thread_id IN (SELECT id FROM chat_thread WHERE user_id = ANY(:users)) OR EXISTS (SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i(\"providerId\" bigint,subject text,\"teamId\" text) JOIN identity_provider p ON p.id=i.\"providerId\" WHERE p.type='SLACK' AND p.server_url='https://slack.com' AND i.subject=t.slack_user_id AND i.\"teamId\"=t.slack_team_id)",
                        "id,workspace_id,chat_thread_id,slack_team_id,slack_channel_id,slack_thread_ts,slack_user_id,created_at",
                        "id",
                        "",
                        -130),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "slack_participant_consent",
                        "slack_participant_consent",
                        "EXISTS(SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb)) AS i(\"providerId\" bigint,subject text,\"teamId\" text) JOIN identity_provider p ON p.id=i.\"providerId\" AND p.type='SLACK' AND p.server_url='https://slack.com' JOIN connection c ON c.workspace_id=t.workspace_id AND c.config->>'teamId'=i.\"teamId\" WHERE i.subject=t.slack_user_id)",
                        "workspace_id,slack_user_id,ingestion_opted_out,research_opted_out,source,decided_at",
                        "workspace_id,slack_user_id",
                        "",
                        -40),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "slack_channel_consent_event",
                        "slack_channel_consent_event",
                        "t.actor_user_id = ANY(:users)",
                        "id,workspace_id,slack_channel_id,from_state,to_state,actor_user_id,reason,created_at",
                        "id",
                        "actor_user_id=NULL,reason=NULL",
                        -40),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "slack_monitored_channel",
                        "slack_monitored_channel",
                        "FALSE",
                        "id,workspace_id,slack_team_id,slack_channel_id,channel_name,consent_state,consent_announced_at,created_at,last_history_synced_ts,history_synced_at,last_sync_error",
                        "id",
                        "",
                        0));
    }
}
