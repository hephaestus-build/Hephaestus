package de.tum.cit.aet.hephaestus.core.privacy;

import de.tum.cit.aet.hephaestus.core.privacy.spi.JdbcPersonDataStore;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataCatalog;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataContributor;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataSelection;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonDataStores;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonIdentityResolver;
import de.tum.cit.aet.hephaestus.core.privacy.spi.PersonScope;
import de.tum.cit.aet.hephaestus.core.privacy.spi.ResearchRecoverySubjects;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
@PersonDataStores({
    "auth_rate_limit_bucket",
    "passkey_credential",
    "passkey_challenge",
    "passkey_recovery_code",
    "account_feature",
    "account_public_activity_hidden_by_default",
    "identity",
    "issued_jwt",
    "client_session",
    "client_sign_in_handoff",
    "mcp_client_grant",
    "account_export",
    "basis_edge",
    "consent_decision",
    "auth_event",
    "config_audit_event",
    "config_audit_event_acting_account",
    "config_audit_event_membership_subject",
    "instance_settings",
    "event_publication",
    "person_data_request",
    "person_data_request_administration",
    "person_data_store_administration",
    CorePrivacyPersonDataCatalog.PRIVACY_JOURNAL,
    "identity_restriction"
})
public class CorePrivacyPersonDataCatalog implements PersonDataCatalog {
    static final String PRIVACY_JOURNAL = "privacy_journal";

    private static final String PUBLICATION_SUBJECT = """
        EXISTS (
            SELECT 1 FROM jsonb_array_elements(CAST(:identities AS jsonb)) AS identity(value)
            WHERE identity.value = t.serialized_event::jsonb -> 'subjectIdentity'
        )
        """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PersonIdentityResolver identities;
    private final Optional<ResearchRecoverySubjects> researchSubjects;

    private List<PersonDataRecovery.NativeIdentity> nativeTargets(PersonScope scope) {
        if (scope.identities().isEmpty()) return List.of();
        var providers = identities.providers();
        return scope.identities().stream()
                .flatMap(identity -> providers.stream()
                        .filter(provider -> provider.id() == identity.providerId())
                        .map(provider -> new PersonDataRecovery.NativeIdentity(
                                provider.type(), provider.serverUrl(), identity.subject(), identity.teamId())))
                .toList();
    }

    @Override
    public List<PersonDataContributor> contributors() {
        return List.of(
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "auth_rate_limit_bucket",
                        "auth_rate_limit_bucket",
                        "split_part(t.id,':',2)='acct' AND split_part(t.id,':',3)=CAST(:account AS text)",
                        "id,expires_at",
                        "id",
                        "",
                        900),
                new PersonDataStoreAdministrationContributor(jdbc, mapper),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        PRIVACY_JOURNAL,
                        PRIVACY_JOURNAL,
                        """
                        t.refs->>'accountId' = CAST(:account AS text)
                        OR (t.kind = 'RESEARCH_DATASET_RELEASED' AND t.entry_id = ANY(:researchReleases))
                        OR EXISTS (
                            SELECT 1 FROM jsonb_to_recordset(CAST(:nativeTargets AS jsonb))
                                AS p("providerType" text, "serverUrl" text, subject text, "teamId" text)
                            WHERE EXISTS (
                                SELECT 1 FROM jsonb_to_recordset(COALESCE(t.refs->'identities', '[]'::jsonb))
                                    AS r("providerType" text, "serverUrl" text, subject text, "teamId" text)
                                WHERE (r."providerType", r."serverUrl", r.subject) =
                                      (p."providerType", p."serverUrl", p.subject)
                                  AND r."teamId" IS NOT DISTINCT FROM p."teamId"
                            ) OR (t.kind = 'SLACK_PARTICIPANT_WITHDRAWN' AND p."providerType" = 'SLACK'
                                  AND p."serverUrl" = 'https://slack.com'
                                  AND p.subject = t.refs->>'slackUserId' AND p."teamId" = t.refs->>'slackTeamId')
                        )
                        """,
                        "entry_id,kind,recorded_at",
                        "entry_id",
                        "",
                        800) {
                    @Override
                    public PersonDataSelection select(PersonScope scope) {
                        return selectUsing(journalBindings(scope));
                    }

                    @Override
                    public List<JsonNode> export(PersonDataSelection selection, PersonScope frozenScope) {
                        return exportMatchingScope(selection, journalBindings(frozenScope));
                    }

                    private Map<String, Object> journalBindings(PersonScope scope) {
                        var bindings = new HashMap<>(JdbcPersonDataStore.parameters(scope, mapper));
                        bindings.put("nativeTargets", mapper.writeValueAsString(nativeTargets(scope)));
                        var accountId = scope.accountId();
                        var releases = accountId == null
                                ? List.<UUID>of()
                                : researchSubjects
                                        .map(subjects -> subjects.releasesFor(accountId))
                                        .orElseGet(List::of);
                        bindings.put("researchReleases", new SqlArrayValue("uuid", releases.toArray()));
                        return bindings;
                    }

                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        // Recovery still needs the fact until the backups that can undo it expire.
                        return 0L;
                    }

                    @Override
                    public boolean selectionMustStayUnchanged() {
                        return false;
                    }
                },
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "passkey_credential",
                        "passkey_credential",
                        "t.account_id = :account",
                        "id,account_id",
                        "id",
                        "",
                        910),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "passkey_challenge",
                        "passkey_challenge",
                        "t.account_id = :account",
                        "id,account_id,purpose,expires_at",
                        "id",
                        "",
                        910),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "passkey_recovery_code",
                        "passkey_recovery_code",
                        "t.account_id = :account",
                        "account_id",
                        "hash",
                        "",
                        910),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "account_feature",
                        "account_feature",
                        "t.account_id = :account",
                        "account_id,flag,enabled_at",
                        "account_id,flag",
                        "",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "account_public_activity_hidden_by_default",
                        "account_public_activity_hidden_by_default",
                        "t.account_id = :account",
                        "account_id",
                        "account_id",
                        "",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "identity",
                        "identity",
                        "t.account_id = :account",
                        "id,account_id,provider_id,provider_user_key,team_key,linked_at,linked_via,last_login_at,disabled_at",
                        "id",
                        "",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "issued_jwt",
                        "issued_jwt",
                        "t.account_id = :account",
                        "jti,account_id,issued_at,expires_at,revoked_at,revoked_reason,user_agent,ip_inet",
                        "jti",
                        "",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "client_session",
                        "client_session",
                        "t.account_id = :account",
                        "id,account_id,client_kind,client_id,session_expires_at,auth_time,created_at,revoked_at,revoked_reason",
                        "id",
                        "",
                        910),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "client_sign_in_handoff",
                        "client_sign_in_handoff",
                        "t.account_id = :account",
                        "account_id,client_kind,client_id,redirect_uri,session_expires_at,auth_time,expires_at,consumed_at,created_at",
                        "code_hash",
                        "",
                        910),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "mcp_client_grant",
                        "mcp_client_grant",
                        "t.account_id = :account",
                        "id,workspace_id,account_id,client_id,client_name,redirect_uri,resource,scopes,status,created_at,"
                                + "approved_at,ended_at,passkey_verified_at,passkey_credential_id,last_used_at",
                        "id",
                        "",
                        910),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "account_export",
                        "account_export",
                        "t.account_id = :account",
                        "id,account_id,status,requested_at,completed_at,expires_at",
                        "id",
                        "",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "basis_edge",
                        "basis_edge",
                        "t.account_id = :account",
                        "id,workspace_id,dependent_kind,dependent_id,source_kind,source_id,created_at",
                        "id",
                        "",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "consent_decision",
                        "consent_decision",
                        "t.account_id = :account",
                        "id,account_id,purpose,granted,occurred_at,mechanism,notice_version,research_organization",
                        "id",
                        "account_id=NULL",
                        900),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "auth_event",
                        "auth_event",
                        "t.account_id = :account OR t.acting_account_id = :account OR"
                                + " t.viewed_account_id=:account OR t.viewed_actor_id = ANY(:users)",
                        "id,occurred_at,event_type,result,failure_reason,provider_id,workspace_id,ip_inet,user_agent",
                        "id,occurred_at",
                        "ip_inet=NULL,user_agent=NULL,details=NULL,account_id=CASE WHEN"
                                + " account_id=:account THEN NULL ELSE account_id"
                                + " END,acting_account_id=CASE WHEN acting_account_id=:account THEN"
                                + " NULL ELSE acting_account_id END,viewed_actor_id=CASE WHEN"
                                + " viewed_account_id=:account OR viewed_actor_id=ANY(:users) THEN NULL"
                                + " ELSE viewed_actor_id END,viewed_account_id=CASE WHEN"
                                + " viewed_account_id=:account THEN NULL ELSE viewed_account_id END",
                        850),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "config_audit_event",
                        "config_audit_event",
                        "t.actor_account_id = :account",
                        "id,occurred_at,workspace_id,entity_type,entity_id,action,changed_keys",
                        "id",
                        "actor_account_id=NULL,old_value=NULL,new_value=NULL",
                        850),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "config_audit_event_acting_account",
                        "config_audit_event",
                        "t.acting_account_id = :account",
                        "id,occurred_at,workspace_id,entity_type,entity_id,action,changed_keys",
                        "id",
                        "acting_account_id=NULL,old_value=NULL,new_value=NULL",
                        850),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "config_audit_event_membership_subject",
                        "config_audit_event",
                        "(t.entity_type='WORKSPACE_ROLE' AND t.entity_id=CAST(:account AS text)) OR"
                                + " (t.entity_type='ACTIVITY_AUTOMATION' AND t.entity_id IN (SELECT"
                                + " u::text FROM unnest(:users) u))",
                        "id,occurred_at,workspace_id,entity_type,entity_id,action,changed_keys,old_value,new_value",
                        "id",
                        "entity_id='ERASED'",
                        850),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "instance_settings",
                        "instance_settings",
                        "t.silent_mode_changed_by_account_id = :account",
                        "id,silent_mode_engaged,silent_mode_reason,silent_mode_changed_at,silent_mode_changed_by_account_id",
                        "id",
                        "silent_mode_changed_by_account_id=NULL,silent_mode_reason=NULL",
                        800),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "event_publication",
                        "event_publication",
                        "(t.serialized_event::jsonb)->>'accountId' = CAST(:account AS text) OR"
                                + " (t.serialized_event::jsonb)->>'recipientAccountId' = CAST(:account"
                                + " AS text) OR "
                                + PUBLICATION_SUBJECT + " OR"
                                + " (t.serialized_event::jsonb) #>> '{change,accountId}' = CAST(:account AS text) OR"
                                + " (t.serialized_event::jsonb) #>> '{change,actorAccountId}' = CAST(:account AS text)",
                        "id,event_type,publication_date,completion_date,status",
                        "id",
                        "",
                        -250) {
                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        var bindings = parameters(frozenScope, mapper);
                        int removed = jdbc.update("""
                            DELETE FROM event_publication t
                            WHERE t.serialized_event::jsonb ->> 'accountId' = CAST(:account AS text)
                               OR t.serialized_event::jsonb ->> 'recipientAccountId' = CAST(:account AS text)
                               OR
                            """ + PUBLICATION_SUBJECT, bindings);
                        int anonymized = jdbc.update("""
                            UPDATE event_publication t SET serialized_event = jsonb_set(t.serialized_event::jsonb,
                                '{change}', redact_role_change(t.serialized_event::jsonb -> 'change', :account))::text
                            WHERE t.serialized_event::jsonb #>> '{change,accountId}' = CAST(:account AS text)
                               OR t.serialized_event::jsonb #>> '{change,actorAccountId}' = CAST(:account AS text)
                            """, bindings);
                        return (long) removed + anonymized;
                    }
                },
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "person_data_request",
                        "person_data_request",
                        "t.scope_json IS NOT NULL AND"
                                + " ((t.scope_json::jsonb)->>'accountId'=CAST(:account AS text) OR"
                                + " EXISTS(SELECT 1 FROM"
                                + " jsonb_array_elements((t.scope_json::jsonb)->'identities') stored"
                                + " JOIN jsonb_array_elements(CAST(:identities AS jsonb)) chosen ON"
                                + " stored=chosen))",
                        "id,state,created_at,expires_at,completed_at,counts_json,completed_json,failure_code",
                        "id",
                        "scope_json=CASE WHEN state IN ('ERASING','FAILED') THEN scope_json"
                                + " END,selections_json=CASE WHEN state IN ('ERASING','FAILED') THEN"
                                + " selections_json END",
                        1100),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "person_data_request_administration",
                        "person_data_request",
                        "t.administrator_account_id=:account",
                        "id,administrator_account_id,state,created_at,expires_at,completed_at",
                        "id,administrator_account_id",
                        "administrator_account_id=NULL,version=version+1",
                        950),
                new JdbcPersonDataStore(
                        jdbc,
                        mapper,
                        "identity_restriction",
                        "identity_restriction",
                        "EXISTS(SELECT 1 FROM jsonb_to_recordset(CAST(:identities AS jsonb))"
                                + " i(\"providerId\" bigint,subject text,\"teamId\" text) WHERE"
                                + " i.\"providerId\"=t.provider_id AND i.subject=t.provider_user_key"
                                + " AND COALESCE(i.\"teamId\",'')=t.team_key)",
                        "id,provider_id,provider_user_key,team_key,kind",
                        "id",
                        "",
                        0) {
                    @Override
                    public long erase(PersonDataSelection selection, PersonScope frozenScope) {
                        return 0;
                    }
                });
    }
}
