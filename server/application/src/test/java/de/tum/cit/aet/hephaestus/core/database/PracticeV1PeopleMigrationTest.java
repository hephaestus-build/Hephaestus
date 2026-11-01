package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("database")
class PracticeV1PeopleMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldRetainMainOwnedAuditAndProviderDefinitions(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        String definition = """
            SELECT value FROM (
              SELECT 'column|' || table_name || '|' || column_name || '|' || data_type || '|' ||
                is_nullable || '|' || coalesce(column_default,'') AS value
              FROM information_schema.columns WHERE table_schema='public'
                AND table_name IN ('config_audit_event','connection_audit')
              UNION ALL
              SELECT 'constraint|' || c.relname || '|' || k.conname || '|' || pg_get_constraintdef(k.oid)
              FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid WHERE c.relnamespace='public'::regnamespace
                AND c.relname IN ('config_audit_event','connection_audit') AND k.conname NOT IN ('ck_config_audit_event_entity_type','ck_config_audit_event_scope')
              UNION ALL
              SELECT 'index|' || tablename || '|' || indexname || '|' || indexdef
              FROM pg_indexes WHERE schemaname='public'
                AND tablename IN ('config_audit_event','connection_audit')
            ) source ORDER BY value
            """;
        var before = PracticeMigrationFixture.column(database, definition);
        assertThat(before).isNotEmpty();
        PracticeMigrationFixture.upgrade(database, context);
        assertThat(PracticeMigrationFixture.column(database, definition)).isEqualTo(before);
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT convalidated::text || '|' || confdeltype::text || '|' || pg_get_constraintdef(oid)
            FROM pg_constraint WHERE conrelid='llm_usage_event'::regclass
                AND conname='sfk_llm_usage_event_workspace'
            """))
                .containsExactly("true|c|FOREIGN KEY (workspace_id) REFERENCES workspace(id) ON DELETE CASCADE");
        assertThat(PracticeMigrationFixture.column(database, """
            SELECT table_name || '|' || column_name || '|' || data_type FROM information_schema.columns
            WHERE table_schema='public' AND (table_name,column_name) IN
              (('auth_event','details'),('config_audit_event','old_value'),('config_audit_event','new_value'),
               ('connection_audit','detail')) ORDER BY table_name,column_name
            """))
                .containsExactly(
                        "auth_event|details|text",
                        "config_audit_event|new_value|text",
                        "config_audit_event|old_value|text",
                        "connection_audit|detail|text");
    }

    @ParameterizedTest
    @ValueSource(strings = {"prod", "dev"})
    void shouldPreserveExactIdentityAuthorityAndConservativePersonChoices(String context) throws Exception {
        var database = PracticeMigrationFixture.releasedDatabase(context);
        PracticeMigrationFixture.execute(database, """
            INSERT INTO identity_provider(id,type,server_url) VALUES(999102,'GITLAB','https://gitlab.practice-v1.example'),
                (999112,'SLACK','https://slack.practice-v1.example');
            INSERT INTO "user"(id,native_id,provider_id,login,type) VALUES(999103,103,999102,'same-person','USER'),
                (999104,104,999102,'former-person','USER');
            INSERT INTO identity_link(id,account_id,provider_id,subject,linked_via,linked_at,last_login_at,disabled_at)
            VALUES(999107,992106,999102,'103','MANUAL_LINK','2026-01-01','2026-01-02','2026-01-03');
            INSERT INTO workspace_membership(workspace_id,user_id,role,hidden,created_at) VALUES(992101,999103,'ADMIN',true,'2026-01-01');
            INSERT INTO user_preferences(id,user_id,ai_review_enabled) VALUES(999108,992103,true),(999109,999103,false);
            INSERT INTO workspace_hidden_former_member(workspace_id,user_id) VALUES(992101,999104);
            INSERT INTO account(id,display_name,status,app_role) VALUES(999106,'Onboarding only','ACTIVE','USER');
            INSERT INTO workspace_member_onboarding(id,workspace_id,account_id,seen_revision,public_activity_seen,updated_at)
            VALUES(999110,992201,999106,7,true,'2026-01-01');
            INSERT INTO identity_link(id,account_id,provider_id,subject,team_id,linked_via)
            VALUES(999117,992106,999112,'U1','T1','OAUTH_LOGIN'),(999118,999106,999112,'U1','T2','OAUTH_LOGIN');
            INSERT INTO public_activity_objection(id,provider_id,subject)
            VALUES('00000000-0000-0000-0000-000000999121',992102,'103');
            INSERT INTO person_data_request(id,state,counts_json,completed_json,created_at,expires_at,version)
            VALUES('00000000-0000-0000-0000-000000999122','PREVIEW','{}','{}','2026-01-01','2027-01-01',0);
            INSERT INTO person_suppression(id,provider_id,subject,team_key,active_request_id)
            VALUES('00000000-0000-0000-0000-000000999123',999112,'U9','T3','00000000-0000-0000-0000-000000999122');
            INSERT INTO auth_event(id,occurred_at,account_id,event_type,result,workspace_id,identity_link_id,
                viewed_user_id,ip_inet,user_agent,details)
            VALUES(999131,'2026-01-01',992106,'USER_VIEW','SUCCESS',992101,992107,992103,
                '192.0.2.1','Exact browser','{"exact": true}');
            """);
        PracticeMigrationFixture.upgrade(database, context);
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT ai_choice || '|' || (ai_choice_at='2026-01-01T00:00:00Z'::timestamptz) || '|' ||"
                                + " feedback_delivery_enabled || '|' || public_activity_visible FROM account WHERE"
                                + " id=992106"))
                .containsExactly("IN_HOUSE_ONLY|true|false|false");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT role || '|' || hidden || '|' || (left_at IS NULL) FROM membership WHERE"
                                + " workspace_id=992101 AND account_id=992106"))
                .containsExactly("OWNER|true|true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT id || '|' || linked_via || '|' || (disabled_at='2026-01-03'::timestamptz) FROM"
                                + " identity WHERE id=999107"))
                .containsExactly("999107|MANUAL|true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT provider_user_key || '|' || team_key || '|' || account_id FROM identity WHERE"
                                + " id IN(999117,999118) ORDER BY id"))
                .containsExactly("U1|T1|992106", "U1|T2|999106");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT a.status || '|' || (a.primary_email IS NULL) || '|' || a.app_role || '|' ||"
                                + " m.hidden || '|' || (m.left_at IS NOT NULL) FROM identity i JOIN account a ON"
                                + " a.id=i.account_id JOIN membership m ON m.account_id=a.id WHERE"
                                + " i.provider_id=999102 AND i.provider_user_key='104'"))
                .containsExactly("UNCLAIMED|true|USER|true|true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT (left_at IS NOT NULL)::text || '|' || onboarding_seen_revision || '|' ||"
                                + " public_activity_seen FROM membership WHERE workspace_id=992201 AND"
                                + " account_id=999106"))
                .containsExactly("true|7|true");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT kind || '|' || provider_user_key || '|' || team_key || '|' ||"
                                + " active_request_id FROM identity_restriction WHERE"
                                + " id='00000000-0000-0000-0000-000000999123'"))
                .containsExactly("ERASED|U9|T3|00000000-0000-0000-0000-000000999122");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT viewed_account_id || '|' || viewed_actor_id || '|' || identity_id || '|' ||"
                                + " host(ip_inet) || '|' || user_agent || '|' || details || '|' ||"
                                + " occurred_at::date FROM auth_event WHERE id=999131"))
                .containsExactly("992106|992103|992107|192.0.2.1|Exact browser|{\"exact\": true}|2026-01-01");
        assertThat(PracticeMigrationFixture.column(
                        database,
                        "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND"
                                + " table_name='auth_event' AND column_name='details'"))
                .containsExactly("text");
    }
}
