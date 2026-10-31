package de.tum.cit.aet.hephaestus.core.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("database")
class ProductSurveyRemovalMigrationTest {
    private static final String CHANGELOG = "20261031175448-refactor-remove-product-surveys.xml";

    @Test
    void shouldDropPopulatedSurveysAndTheirNotificationsAndKeepProductFeedbackAndResearchConsent() throws Exception {
        var database = beforeRemoval();
        seed(database);

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            liquibase.update(new Contexts("prod"), new LabelExpression());

            assertThat(PracticeMigrationFixture.column(database, """
                    SELECT table_name FROM information_schema.tables
                    WHERE table_schema='public' AND table_name IN
                    ('product_survey', 'product_survey_participation', 'product_survey_email_invitation')
                    """)).isEmpty();
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT kind FROM notification_subscription WHERE account_id=991501 ORDER BY kind"))
                    .containsExactly("MENTOR_DIGEST", "PRODUCT_FEEDBACK", "WORKSPACE_ALERTS");
            assertThat(PracticeMigrationFixture.column(
                            database,
                            "SELECT event_type FROM event_publication WHERE listener_id='survey-removal-fixture'"))
                    .containsExactly("de.tum.cit.aet.hephaestus.notification.ProductFeedbackEmailRequested");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT message FROM product_feedback WHERE account_id=991501"))
                    .containsExactly("Keep this product feedback");
            assertThat(PracticeMigrationFixture.column(
                            database, "SELECT purpose || ':' || granted FROM consent_decision WHERE account_id=991501"))
                    .containsExactly("RESEARCH_PARTICIPATION:true");

            assertThatThrownBy(() -> liquibase.rollback(1, new Contexts("prod"), new LabelExpression()))
                    .hasStackTraceContaining("verified pre-upgrade database backup");
        }
    }

    @Test
    void shouldRejectAnIncompleteSourceSchemaBeforeDeletingNotificationRows() throws Exception {
        var database = beforeRemoval();
        seed(database);
        PracticeMigrationFixture.execute(database, "DROP TABLE product_survey_email_invitation");

        try (var connection =
                        DriverManager.getConnection(database.jdbcUrl(), database.username(), database.password());
                var liquibase = new Liquibase(
                        "db/changelog/next/" + CHANGELOG,
                        new ClassLoaderResourceAccessor(),
                        DatabaseFactory.getInstance()
                                .findCorrectDatabaseImplementation(new JdbcConnection(connection)))) {
            assertThatThrownBy(() -> liquibase.update(new Contexts("prod"), new LabelExpression()))
                    .hasStackTraceContaining("product_survey_email_invitation");
        }
        assertThat(
                        PracticeMigrationFixture.column(
                                database,
                                "SELECT kind FROM notification_subscription WHERE account_id=991501 AND kind LIKE '%SURVEY%' ORDER BY kind"))
                .containsExactly("PRODUCT_SURVEYS", "RESEARCH_SURVEYS", "SURVEY_SUMMARIES");
        assertThat(PracticeMigrationFixture.column(
                        database, "SELECT title FROM product_survey WHERE created_by_account_id=991501"))
                .containsExactly("Before removal");
    }

    private static TestDatabase beforeRemoval() throws Exception {
        var database = PostgreSQLTestContainer.createDatabase(
                "survey_removal_" + UUID.randomUUID().toString().replace("-", ""));
        PracticeMigrationFixture.upgradeBefore(database, "prod", CHANGELOG);
        return database;
    }

    private static void seed(TestDatabase database) {
        PracticeMigrationFixture.execute(database, """
                INSERT INTO account(id,display_name) VALUES (991501,'Survey removal');
                INSERT INTO product_survey(id,title,description,questions_json,starts_at,active,created_at,created_by_account_id)
                VALUES ('00000000-0000-0000-0000-000000991501','Before removal','Purpose','[]',now(),true,now(),991501);
                INSERT INTO product_survey_participation(id,survey_id,account_id,status,answers_json,invited_at,decided_at)
                VALUES ('00000000-0000-0000-0000-000000991502','00000000-0000-0000-0000-000000991501',991501,
                        'RESPONDED','[{"questionId":"why","text":"Personal response"}]',now(),now());
                INSERT INTO product_survey_email_invitation(id,survey_id,account_id,requested_by_account_id,requested_at,expires_at,reminder_enabled,request_generation)
                VALUES ('00000000-0000-0000-0000-000000991503','00000000-0000-0000-0000-000000991501',991501,991501,now(),now()+interval '1 day',true,1);
                INSERT INTO product_feedback(id,account_id,kind,message,app_version,created_at,submission_minute)
                VALUES ('00000000-0000-0000-0000-000000991504',991501,'IDEA','Keep this product feedback','test',now(),now());
                INSERT INTO consent_decision(account_id,purpose,granted,mechanism,notice_version,research_organization,occurred_at)
                VALUES (991501,'RESEARCH_PARTICIPATION',true,'ACCOUNT_SETTINGS','2026-10-04','Research organization',now());
                INSERT INTO notification_subscription(id,account_id,kind,enabled,unsubscribe_token,version)
                SELECT gen_random_uuid(),991501,kind,true,gen_random_uuid(),0
                FROM unnest(ARRAY['PRODUCT_SURVEYS','RESEARCH_SURVEYS','SURVEY_SUMMARIES','PRODUCT_FEEDBACK','WORKSPACE_ALERTS','MENTOR_DIGEST']) AS kind;
                INSERT INTO event_publication(id,listener_id,event_type,serialized_event,publication_date)
                SELECT gen_random_uuid(),'survey-removal-fixture','de.tum.cit.aet.hephaestus.notification.' || kind,'{}',now()
                FROM unnest(ARRAY['SurveyEmailRequested','SurveyEndedSummaryRequested','ProductFeedbackEmailRequested']) AS kind;
                """);
    }
}
