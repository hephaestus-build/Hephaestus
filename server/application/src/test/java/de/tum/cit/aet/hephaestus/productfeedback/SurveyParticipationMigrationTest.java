package de.tum.cit.aet.hephaestus.productfeedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.AnswerDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.OptionCountDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionDTO;
import de.tum.cit.aet.hephaestus.productfeedback.FeedbackDTOs.QuestionSummaryDTO;
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

@Tag("database")
class SurveyParticipationMigrationTest {

    private static final String CHANGELOG = "1789117764980_changelog.xml";
    private static final List<QuestionDTO> QUESTIONS = List.of(
            new QuestionDTO("useful", "How useful?", QuestionType.RATING, List.of(), true, "Not", "Very"),
            new QuestionDTO(
                    "team", "Team?", QuestionType.SINGLE_CHOICE, List.of("Backend", "Frontend"), false, null, null),
            new QuestionDTO("why", "Why?", QuestionType.TEXT, List.of(), false, null, null),
            new QuestionDTO("skipped", "Skipped?", QuestionType.TEXT, List.of(), false, null, null));

    @Test
    void shouldReshapeSubmittedAnswersByQuestionTypeWhenCopyingSubmissions() throws Exception {
        var mapper = new ObjectMapper();
        var fixture = PostgreSQLTestContainer.createDatabase("survey_participation_migration");
        var contexts = new Contexts("prod");
        var labels = new LabelExpression();
        try (var connection = DriverManager.getConnection(fixture.jdbcUrl(), fixture.username(), fixture.password())) {
            var database =
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (var liquibase = new Liquibase("db/master.xml", new ClassLoaderResourceAccessor(), database)) {
                var pending = liquibase.listUnrunChangeSets(contexts, labels);
                int before = (int) pending.stream()
                        .takeWhile(changeSet -> !changeSet.getFilePath().endsWith(CHANGELOG))
                        .count();
                int inChangelog = (int) pending.stream()
                        .filter(changeSet -> changeSet.getFilePath().endsWith(CHANGELOG))
                        .count();
                liquibase.update(before, contexts, labels);

                try (var statement = connection.prepareStatement("""
                        INSERT INTO account (id, display_name) VALUES (991101, 'responder'), (991102, 'decliner');
                        INSERT INTO product_survey (id, title, description, questions_json, starts_at, active, created_at)
                        VALUES ('00000000-0000-0000-0000-000000991103', 'Survey', 'Purpose', CAST(? AS jsonb), now(), true, now());
                        INSERT INTO product_survey_submission (id, survey_id, account_id, disposition, answers_json, created_at)
                        VALUES ('00000000-0000-0000-0000-000000991104', '00000000-0000-0000-0000-000000991103', 991101,
                                'RESPONDED', CAST(? AS jsonb), '2026-01-02T03:04:05Z'),
                               ('00000000-0000-0000-0000-000000991105', '00000000-0000-0000-0000-000000991103', 991102,
                                'DISMISSED', NULL, '2026-01-02T03:04:06Z');
                        """)) {
                    statement.setString(1, mapper.writeValueAsString(QUESTIONS));
                    statement.setString(
                            2,
                            mapper.writeValueAsString(
                                    Map.of("useful", "4", "team", "Backend", "why", " Fast ", "skipped", "  ")));
                    statement.execute();
                }
                connection.commit();

                liquibase.update(inChangelog, contexts, labels);

                List<String> statuses = new ArrayList<>();
                List<List<AnswerDTO>> responses = new ArrayList<>();
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("""
                            SELECT status, answers_json, invited_at, decided_at
                            FROM product_survey_participation
                            WHERE survey_id = '00000000-0000-0000-0000-000000991103'
                            ORDER BY account_id
                            """)) {
                    while (rows.next()) {
                        statuses.add(rows.getString("status"));
                        assertThat(rows.getTimestamp("decided_at")).isEqualTo(rows.getTimestamp("invited_at"));
                        String answers = rows.getString("answers_json");
                        if (answers != null)
                            responses.add(mapper.readValue(answers, new TypeReference<List<AnswerDTO>>() {}));
                    }
                }

                assertThat(statuses).containsExactly("RESPONDED", "DECLINED");
                assertThat(responses)
                        .containsExactly(List.of(
                                new AnswerDTO("useful", null, null, 4),
                                new AnswerDTO("team", null, List.of("Backend"), null),
                                new AnswerDTO("why", "Fast", null, null)));
                assertThat(SurveyQuestions.validateAnswers(QUESTIONS, responses.getFirst()))
                        .as("the copied answers are what the application would have stored itself")
                        .isEqualTo(responses.getFirst());
                assertThat(SurveyQuestions.summarize(QUESTIONS, responses))
                        .extracting(QuestionSummaryDTO::questionId, QuestionSummaryDTO::answered)
                        .containsExactly(
                                tuple("useful", 1L), tuple("team", 1L), tuple("why", 1L), tuple("skipped", 0L));
                assertThat(SurveyQuestions.summarize(QUESTIONS, responses)
                                .get(1)
                                .counts())
                        .containsExactly(new OptionCountDTO("Backend", 1), new OptionCountDTO("Frontend", 0));
            }
        }
    }
}
