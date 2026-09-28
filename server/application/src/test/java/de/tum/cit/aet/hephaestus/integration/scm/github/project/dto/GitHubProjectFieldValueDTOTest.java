package de.tum.cit.aet.hephaestus.integration.scm.github.project.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHBot;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHLabel;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHLabelConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMannequin;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHMilestone;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2Field;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldDateValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldIterationValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldLabelValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldMilestoneValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldNumberValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldPullRequestValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldRepositoryValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldReviewerValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldSingleSelectValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldTextValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldUserValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2ItemFieldValue;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2IterationField;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHProjectV2SingleSelectField;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHPullRequestConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRepository;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHRequestedReviewerConnection;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHTeam;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUser;
import de.tum.cit.aet.hephaestus.integration.scm.github.graphql.model.GHUserConnection;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GitHubProjectFieldValueDTO}.
 */
class GitHubProjectFieldValueDTOTest extends BaseUnitTest {

    private static final String FIELD_ID = "PVTF_field123";

    @Nested
    class NullAndUnknownInput {

        @Test
        void shouldReturnNullForNullFieldValue() {
            assertThat(GitHubProjectFieldValueDTO.fromFieldValue(null)).isNull();
        }

        @Test
        void shouldReturnNullForUnknownFieldValueType() {
            // Anonymous implementation of the interface
            GHProjectV2ItemFieldValue unknownType = new GHProjectV2ItemFieldValue() {};

            assertThat(GitHubProjectFieldValueDTO.fromFieldValue(unknownType)).isNull();
        }
    }

    @Nested
    class TextFieldValue {

        @Test
        void shouldExtractTextValue() {
            GHProjectV2ItemFieldTextValue value = new GHProjectV2ItemFieldTextValue();
            value.setField(createField(FIELD_ID));
            value.setText("Hello World");

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldId()).isEqualTo(FIELD_ID);
            assertThat(result.fieldType()).isEqualTo("TEXT");
            assertThat(result.textValue()).isEqualTo("Hello World");
            assertThat(result.numberValue()).isNull();
            assertThat(result.dateValue()).isNull();
        }

        @Test
        void shouldReturnNullWhenFieldIsNull() {
            GHProjectV2ItemFieldTextValue value = new GHProjectV2ItemFieldTextValue();
            value.setText("Hello");
            // field is null

            assertThat(GitHubProjectFieldValueDTO.fromFieldValue(value)).isNull();
        }
    }

    @Nested
    class NumberFieldValue {

        @Test
        void shouldExtractNumberValue() {
            GHProjectV2ItemFieldNumberValue value = new GHProjectV2ItemFieldNumberValue();
            value.setField(createField(FIELD_ID));
            value.setNumber(42.5);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("NUMBER");
            assertThat(result.numberValue()).isEqualTo(42.5);
            assertThat(result.fieldId()).isEqualTo(FIELD_ID);
        }

        @Test
        void shouldReturnNullWhenFieldIsNull() {
            GHProjectV2ItemFieldNumberValue value = new GHProjectV2ItemFieldNumberValue();
            value.setNumber(10.0);

            assertThat(GitHubProjectFieldValueDTO.fromFieldValue(value)).isNull();
        }
    }

    @Nested
    class DateFieldValue {

        @Test
        void shouldExtractDateValue() {
            GHProjectV2ItemFieldDateValue value = new GHProjectV2ItemFieldDateValue();
            value.setField(createField(FIELD_ID));
            value.setDate(LocalDate.of(2025, 6, 15));

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("DATE");
            assertThat(result.dateValue()).isEqualTo(LocalDate.of(2025, 6, 15));
            assertThat(result.fieldId()).isEqualTo(FIELD_ID);
        }

        @Test
        void shouldHandleNullDate() {
            GHProjectV2ItemFieldDateValue value = new GHProjectV2ItemFieldDateValue();
            value.setField(createField(FIELD_ID));
            // date is null

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("DATE");
            assertThat(result.dateValue()).isNull();
        }
    }

    @Nested
    class SingleSelectFieldValue {

        @Test
        void shouldExtractSingleSelectOptionId() {
            GHProjectV2ItemFieldSingleSelectValue value = new GHProjectV2ItemFieldSingleSelectValue();
            GHProjectV2SingleSelectField ssField = new GHProjectV2SingleSelectField();
            ssField.setId(FIELD_ID);
            value.setField(ssField);
            value.setOptionId("option-abc");

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("SINGLE_SELECT");
            assertThat(result.singleSelectOptionId()).isEqualTo("option-abc");
            assertThat(result.fieldId()).isEqualTo(FIELD_ID);
        }
    }

    @Nested
    class IterationFieldValue {

        @Test
        void shouldExtractIterationId() {
            GHProjectV2ItemFieldIterationValue value = new GHProjectV2ItemFieldIterationValue();
            GHProjectV2IterationField iterField = new GHProjectV2IterationField();
            iterField.setId(FIELD_ID);
            value.setField(iterField);
            value.setIterationId("iter-xyz");

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("ITERATION");
            assertThat(result.iterationId()).isEqualTo("iter-xyz");
            assertThat(result.fieldId()).isEqualTo(FIELD_ID);
        }
    }

    @Nested
    class LabelFieldValue {

        @Test
        void shouldExtractLabelsAsJsonArray() {
            GHLabel label1 = new GHLabel();
            label1.setName("bug");
            GHLabel label2 = new GHLabel();
            label2.setName("enhancement");

            GHLabelConnection labelConn = new GHLabelConnection();
            labelConn.setNodes(List.of(label1, label2));

            GHProjectV2ItemFieldLabelValue value = new GHProjectV2ItemFieldLabelValue();
            value.setField(createField(FIELD_ID));
            value.setLabels(labelConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("LABELS");
            assertThat(result.textValue()).contains("bug", "enhancement");
        }

        @Test
        void shouldHandleEmptyLabels() {
            GHLabelConnection labelConn = new GHLabelConnection();
            labelConn.setNodes(List.of());

            GHProjectV2ItemFieldLabelValue value = new GHProjectV2ItemFieldLabelValue();
            value.setField(createField(FIELD_ID));
            value.setLabels(labelConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("LABELS");
            assertThat(result.textValue()).isEqualTo("[]");
        }

        @Test
        void shouldHandleNullLabelsConnection() {
            GHProjectV2ItemFieldLabelValue value = new GHProjectV2ItemFieldLabelValue();
            value.setField(createField(FIELD_ID));
            // labels is null

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("LABELS");
            assertThat(result.textValue()).isEqualTo("[]");
        }
    }

    @Nested
    class UserFieldValue {

        @Test
        void shouldExtractUserLoginsAsJsonArray() {
            GHUser user1 = new GHUser();
            user1.setLogin("alice");
            GHUser user2 = new GHUser();
            user2.setLogin("bob");

            GHUserConnection userConn = new GHUserConnection();
            userConn.setNodes(List.of(user1, user2));

            GHProjectV2ItemFieldUserValue value = new GHProjectV2ItemFieldUserValue();
            value.setField(createField(FIELD_ID));
            value.setUsers(userConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("ASSIGNEES");
            assertThat(result.textValue()).contains("alice", "bob");
        }

        @Test
        void shouldHandleNullUsersConnection() {
            GHProjectV2ItemFieldUserValue value = new GHProjectV2ItemFieldUserValue();
            value.setField(createField(FIELD_ID));

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("ASSIGNEES");
            assertThat(result.textValue()).isEqualTo("[]");
        }
    }

    @Nested
    class ReviewerFieldValue {

        @Test
        void shouldExtractUserReviewerLogins() {
            GHUser user = new GHUser();
            user.setLogin("reviewer1");

            GHRequestedReviewerConnection reviewerConn = new GHRequestedReviewerConnection();
            reviewerConn.setNodes(List.of(user));

            GHProjectV2ItemFieldReviewerValue value = new GHProjectV2ItemFieldReviewerValue();
            value.setField(createField(FIELD_ID));
            value.setReviewers(reviewerConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("REVIEWERS");
            assertThat(result.textValue()).contains("reviewer1");
        }

        @Test
        void shouldExtractTeamReviewerNames() {
            GHTeam team = new GHTeam();
            team.setName("backend-team");

            GHRequestedReviewerConnection reviewerConn = new GHRequestedReviewerConnection();
            reviewerConn.setNodes(List.of(team));

            GHProjectV2ItemFieldReviewerValue value = new GHProjectV2ItemFieldReviewerValue();
            value.setField(createField(FIELD_ID));
            value.setReviewers(reviewerConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("REVIEWERS");
            assertThat(result.textValue()).contains("backend-team");
        }

        @Test
        void shouldHandleMixedUserAndTeamReviewers() {
            GHUser user = new GHUser();
            user.setLogin("alice");
            GHTeam team = new GHTeam();
            team.setName("core-team");

            GHRequestedReviewerConnection reviewerConn = new GHRequestedReviewerConnection();
            reviewerConn.setNodes(List.of(user, team));

            GHProjectV2ItemFieldReviewerValue value = new GHProjectV2ItemFieldReviewerValue();
            value.setField(createField(FIELD_ID));
            value.setReviewers(reviewerConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("REVIEWERS");
            assertThat(result.textValue()).contains("alice", "core-team");
        }

        @Test
        void shouldKeepBotAndMannequinLoginsWhenTheyAreAskedToReview() {
            GHUser user = new GHUser();
            user.setLogin("alice");
            GHBot bot = new GHBot();
            bot.setLogin("copilot-pull-request-reviewer");
            GHMannequin mannequin = new GHMannequin();
            mannequin.setLogin("imported-reviewer");
            GHRequestedReviewerConnection reviewerConn = new GHRequestedReviewerConnection();
            reviewerConn.setNodes(List.of(user, bot, mannequin));
            reviewerConn.setTotalCount(3);
            GHProjectV2ItemFieldReviewerValue value = new GHProjectV2ItemFieldReviewerValue();
            value.setField(createField(FIELD_ID));
            value.setReviewers(reviewerConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.textValue()).contains("alice", "copilot-pull-request-reviewer", "imported-reviewer");
        }

        @Test
        void shouldHandleNullReviewersConnection() {
            GHProjectV2ItemFieldReviewerValue value = new GHProjectV2ItemFieldReviewerValue();
            value.setField(createField(FIELD_ID));

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("REVIEWERS");
            assertThat(result.textValue()).isEqualTo("[]");
        }
    }

    @Nested
    class MilestoneFieldValue {

        @Test
        void shouldExtractMilestoneTitle() {
            GHMilestone milestone = new GHMilestone();
            milestone.setTitle("v1.0");

            GHProjectV2ItemFieldMilestoneValue value = new GHProjectV2ItemFieldMilestoneValue();
            value.setField(createField(FIELD_ID));
            value.setMilestone(milestone);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("MILESTONE");
            assertThat(result.textValue()).isEqualTo("v1.0");
        }

        @Test
        void shouldHandleNullMilestone() {
            GHProjectV2ItemFieldMilestoneValue value = new GHProjectV2ItemFieldMilestoneValue();
            value.setField(createField(FIELD_ID));

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("MILESTONE");
            assertThat(result.textValue()).isNull();
        }
    }

    @Nested
    class RepositoryFieldValue {

        @Test
        void shouldExtractRepositoryNameWithOwner() {
            GHRepository repository = new GHRepository();
            repository.setNameWithOwner("org/repo");

            GHProjectV2ItemFieldRepositoryValue value = new GHProjectV2ItemFieldRepositoryValue();
            value.setField(createField(FIELD_ID));
            value.setRepository(repository);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("REPOSITORY");
            assertThat(result.textValue()).isEqualTo("org/repo");
        }

        @Test
        void shouldHandleNullRepository() {
            GHProjectV2ItemFieldRepositoryValue value = new GHProjectV2ItemFieldRepositoryValue();
            value.setField(createField(FIELD_ID));

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("REPOSITORY");
            assertThat(result.textValue()).isNull();
        }
    }

    @Nested
    class PullRequestFieldValue {

        @Test
        void shouldExtractPrNumbersAsJsonArray() {
            GHPullRequest pr1 = new GHPullRequest();
            pr1.setNumber(42);
            GHPullRequest pr2 = new GHPullRequest();
            pr2.setNumber(99);

            GHPullRequestConnection prConn = new GHPullRequestConnection();
            prConn.setNodes(List.of(pr1, pr2));

            GHProjectV2ItemFieldPullRequestValue value = new GHProjectV2ItemFieldPullRequestValue();
            value.setField(createField(FIELD_ID));
            value.setPullRequests(prConn);

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("PULL_REQUESTS");
            assertThat(result.textValue()).contains("42", "99");
        }

        @Test
        void shouldHandleNullPullRequestsConnection() {
            GHProjectV2ItemFieldPullRequestValue value = new GHProjectV2ItemFieldPullRequestValue();
            value.setField(createField(FIELD_ID));

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldType()).isEqualTo("PULL_REQUESTS");
            assertThat(result.textValue()).isEqualTo("[]");
        }
    }

    @Nested
    class ExtractFieldId {

        @Test
        void shouldExtractIdFromProjectV2Field() {
            GHProjectV2ItemFieldTextValue value = new GHProjectV2ItemFieldTextValue();
            GHProjectV2Field field = new GHProjectV2Field();
            field.setId("field-abc");
            value.setField(field);
            value.setText("test");

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldId()).isEqualTo("field-abc");
        }

        @Test
        void shouldExtractIdFromSingleSelectField() {
            GHProjectV2ItemFieldTextValue value = new GHProjectV2ItemFieldTextValue();
            GHProjectV2SingleSelectField field = new GHProjectV2SingleSelectField();
            field.setId("ss-field-123");
            value.setField(field);
            value.setText("test");

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldId()).isEqualTo("ss-field-123");
        }

        @Test
        void shouldExtractIdFromIterationField() {
            GHProjectV2ItemFieldTextValue value = new GHProjectV2ItemFieldTextValue();
            GHProjectV2IterationField field = new GHProjectV2IterationField();
            field.setId("iter-field-456");
            value.setField(field);
            value.setText("test");

            GitHubProjectFieldValueDTO result = GitHubProjectFieldValueDTO.fromFieldValue(value);

            assertNotNull(result);
            assertThat(result.fieldId()).isEqualTo("iter-field-456");
        }
    }

    // Helper Methods

    private GHProjectV2Field createField(String id) {
        GHProjectV2Field field = new GHProjectV2Field();
        field.setId(id);
        return field;
    }
}
