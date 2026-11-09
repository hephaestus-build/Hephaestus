package de.tum.cit.aet.hephaestus.core.database;

import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer.TestDatabase;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

final class NativePracticeFixture {
    private static final Map<String, TestDatabase> SCHEMA_TEMPLATES = new HashMap<>();

    private NativePracticeFixture() {}

    static synchronized TestDatabase database(String context) {
        var template = SCHEMA_TEMPLATES.get(context);
        if (template == null) {
            template = PostgreSQLTestContainer.createDatabase("native_schema_" + context);
            PostgreSQLTestContainer.migrateDatabase(template, context);
            SCHEMA_TEMPLATES.put(context, template);
        }
        return PostgreSQLTestContainer.copyDatabase(
                "native_" + UUID.randomUUID().toString().replace("-", ""), template);
    }

    static TestDatabase resultParent(String context) {
        var database = database(context);
        seed(database, "result-parent.sql");
        return database;
    }

    static TestDatabase resultParentAfterUpgrade(String context) throws Exception {
        var database = PracticeMigrationFixture.migratedDatabase(context);
        seed(database, "result-parent.sql");
        return database;
    }

    static TestDatabase feedbackParent(String context) {
        var database = resultParent(context);
        seed(database, "feedback-parent.sql");
        return database;
    }

    private static void seed(TestDatabase database, String file) {
        new ResourceDatabasePopulator(new ClassPathResource("db/practice-native/" + file))
                .execute(new DriverManagerDataSource(database.jdbcUrl(), database.username(), database.password()));
    }

    static String insertResult(String outcome, String coverage) {
        return "INSERT INTO"
                + " result(id,workspace_id,review_id,practice_id,practice_version_id,item_id,item_version,developer_id,outcome,summary,coverage,recorded_at)"
                + " SELECT"
                + " '00000000-0000-0000-0000-000000978021',workspace_id,id,978004,978005,item_id,item_version,978003,'"
                + outcome + "','Review summary'," + coverage
                + ",now() FROM review WHERE id='00000000-0000-0000-0000-000000978011'";
    }
}
