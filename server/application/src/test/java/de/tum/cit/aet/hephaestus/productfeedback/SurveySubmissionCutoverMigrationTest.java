package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionType;
import de.tum.cit.aet.hephaestus.testconfig.PostgreSQLTestContainer;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The 1.0 changelog copies each survey submission that v0.81.0's copy missed before it drops the old table: a v0.80.x
 * role could write one during that rolling upgrade.
 */
@Tag("database")
class SurveySubmissionCutoverMigrationTest {

    private static final String CHANGELOG = "1793129327729_changelog.xml";

    private static final List<QuestionDTO> QUESTIONS = List.of(
            new QuestionDTO("useful", "How useful?", QuestionType.RATING, List.of(), true, false, "Not", "Very"),
            new QuestionDTO("why", "Why?", QuestionType.TEXT, List.of(), false, false, null, null),
            new QuestionDTO("skipped", "Skipped?", QuestionType.TEXT, List.of(), false, false, null, null));

    @Test
    void shouldCopyOnlyTheSubmissionThatTheEarlierCopyMissed() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = PostgreSQLTestContainer.createDatabase("survey_submission_cutover");
        var contexts = new Contexts("prod");
        var labels = new LabelExpression();
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                // This fixture resumes a deliberately partial history before the production entry boundary.
                liquibase
                        .getDatabaseChangeLog()
                        .getPreconditions()
                        .getNestedPreconditions()
                        .clear();
                var pending = liquibase.listUnrunChangeSets(contexts, labels);
                int before = (int) pending.stream()
                        .takeWhile(changeSet -> !changeSet.getFilePath().endsWith(CHANGELOG))
                        .count();
                liquibase.update(before, contexts, labels);

                // 991201 answered before v0.81.0 copied it; 991202 answered on a v0.80.x role after that copy.
                try (var statement = connection.prepareStatement("""
                        INSERT INTO account (id, display_name) VALUES (991201, 'copied'), (991202, 'late');
                        INSERT INTO product_survey (id, title, description, questions_json, starts_at, active, created_at)
                        VALUES ('00000000-0000-0000-0000-000000991203', 'Survey', 'Purpose', CAST(? AS jsonb), now(), true, now());
                        INSERT INTO product_survey_submission (id, survey_id, account_id, disposition, answers_json, created_at)
                        VALUES ('00000000-0000-0000-0000-000000991204', '00000000-0000-0000-0000-000000991203', 991201,
                                'RESPONDED', CAST(? AS jsonb), '2026-01-02T03:04:05Z'),
                               ('00000000-0000-0000-0000-000000991205', '00000000-0000-0000-0000-000000991203', 991202,
                                'RESPONDED', CAST(? AS jsonb), '2026-01-02T03:04:06Z');
                        INSERT INTO product_survey_participation (id, survey_id, account_id, status, answers_json, invited_at, decided_at)
                        VALUES ('00000000-0000-0000-0000-000000991204', '00000000-0000-0000-0000-000000991203', 991201,
                                'RESPONDED', '[{"questionId":"useful","rating":2}]', '2026-01-02T03:04:05Z', '2026-01-02T03:04:05Z');
                        """)) {
                    statement.setString(1, mapper.writeValueAsString(QUESTIONS));
                    statement.setString(2, mapper.writeValueAsString(Map.of("useful", "2")));
                    statement.setString(
                            3, mapper.writeValueAsString(Map.of("useful", "5", "why", " Late ", "skipped", "  ")));
                    statement.execute();
                }
                connection.commit();

                liquibase.update(contexts, labels);

                List<Long> accounts = new ArrayList<>();
                List<List<AnswerDTO>> answers = new ArrayList<>();
                List<String> decided = new ArrayList<>();
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("""
                            SELECT account_id, status, answers_json, invited_at, decided_at
                            FROM product_survey_participation
                            WHERE survey_id = '00000000-0000-0000-0000-000000991203'
                            ORDER BY account_id
                            """)) {
                    while (rows.next()) {
                        accounts.add(rows.getLong("account_id"));
                        assertThat(rows.getString("status")).isEqualTo("RESPONDED");
                        assertThat(rows.getTimestamp("decided_at")).isEqualTo(rows.getTimestamp("invited_at"));
                        decided.add(rows.getTimestamp("decided_at").toInstant().toString());
                        answers.add(mapper.readValue(
                                rows.getString("answers_json"), new TypeReference<List<AnswerDTO>>() {}));
                    }
                }

                assertThat(accounts).containsExactly(991201L, 991202L);
                // The earlier copy stays as it was; the late submission is reshaped, its blank answer dropped.
                assertThat(answers)
                        .containsExactly(
                                List.of(new AnswerDTO("useful", null, null, 2)),
                                List.of(
                                        new AnswerDTO("useful", null, null, 5),
                                        new AnswerDTO("why", "Late", null, null)));
                assertThat(decided).containsExactly("2026-01-02T03:04:05Z", "2026-01-02T03:04:06Z");
            }
        }
    }
}
