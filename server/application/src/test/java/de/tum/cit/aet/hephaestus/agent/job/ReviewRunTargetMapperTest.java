package de.tum.cit.aet.hephaestus.agent.job;

import static org.assertj.core.api.Assertions.assertThat;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.spi.ReviewRunLookup.Target;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class ReviewRunTargetMapperTest extends BaseUnitTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest(name = "{0}")
    @MethodSource("targets")
    void mapsSubmissionMetadata(
            String ignoredDescription,
            AgentJobType jobType,
            @Nullable IntegrationKind integrationKind,
            @Nullable JsonNode metadata,
            Target expected) {
        AgentJob job = new AgentJob();
        job.setJobType(jobType);
        job.setIntegrationKind(integrationKind);
        ReflectionTestUtils.setField(job, "metadata", metadata);

        assertThat(ReviewRunTargetMapper.from(job)).isEqualTo(expected);
    }

    static Stream<Arguments> targets() {
        ObjectNode pullRequest = MAPPER.createObjectNode();
        pullRequest.put("pull_request_id", 7L);
        pullRequest.put("pr_number", 42);
        pullRequest.put("title", "Make output visible");
        pullRequest.put("repository_full_name", "team/project");
        pullRequest.put("pr_url", "https://github.com/team/project/pull/42");

        ObjectNode issue = MAPPER.createObjectNode();
        issue.put("issue_id", 73L);
        issue.put("issue_number", 19);
        issue.put("title", "Clarify the contract");
        issue.put("repository_full_name", "team/project");
        issue.put("issue_url", "https://gitlab.com/team/project/-/issues/19");

        ObjectNode conversation = MAPPER.createObjectNode();
        conversation.put("slack_thread_id", 91L);
        conversation.put("slack_channel_name", "engineering");

        ObjectNode document = MAPPER.createObjectNode();
        document.put("docs_document_id", 77L);
        document.put("title", "Deployment runbook");
        document.put("docs_collection_name", "Engineering");

        ObjectNode malformed = MAPPER.createObjectNode();
        malformed.put("pull_request_id", "not-an-id");
        malformed.put("pr_number", 4.2);
        malformed.put("title", " ");

        return Stream.of(
                Arguments.of(
                        "pull request",
                        AgentJobType.PULL_REQUEST_REVIEW,
                        IntegrationKind.GITHUB,
                        pullRequest,
                        new Target(
                                ArtifactKinds.PULL_REQUEST,
                                7L,
                                IntegrationKind.GITHUB,
                                42,
                                "Make output visible",
                                "team/project",
                                null,
                                "https://github.com/team/project/pull/42")),
                Arguments.of(
                        "issue",
                        AgentJobType.ISSUE_REVIEW,
                        IntegrationKind.GITLAB,
                        issue,
                        new Target(
                                ArtifactKinds.ISSUE,
                                73L,
                                IntegrationKind.GITLAB,
                                19,
                                "Clarify the contract",
                                "team/project",
                                null,
                                "https://gitlab.com/team/project/-/issues/19")),
                Arguments.of(
                        "conversation",
                        AgentJobType.CONVERSATION_REVIEW,
                        IntegrationKind.SLACK,
                        conversation,
                        new Target(
                                ArtifactKinds.CONVERSATION_THREAD,
                                91L,
                                IntegrationKind.SLACK,
                                null,
                                "Conversation",
                                null,
                                "engineering",
                                null)),
                Arguments.of(
                        "document",
                        AgentJobType.DOCUMENT_REVIEW,
                        IntegrationKind.OUTLINE,
                        document,
                        new Target(
                                ArtifactKinds.DOCUMENT,
                                77L,
                                IntegrationKind.OUTLINE,
                                null,
                                "Deployment runbook",
                                "Engineering",
                                null,
                                null)),
                Arguments.of(
                        "a document whose title the mirror lost still names its kind",
                        AgentJobType.DOCUMENT_REVIEW,
                        null,
                        null,
                        new Target(ArtifactKinds.DOCUMENT, null, null, null, "Document", null, null, null)),
                Arguments.of(
                        "malformed metadata",
                        AgentJobType.PULL_REQUEST_REVIEW,
                        null,
                        malformed,
                        new Target(ArtifactKinds.PULL_REQUEST, null, null, null, "Pull request", null, null, null)),
                Arguments.of(
                        "missing metadata",
                        AgentJobType.ISSUE_REVIEW,
                        null,
                        null,
                        new Target(ArtifactKinds.ISSUE, null, null, null, "Issue", null, null, null)));
    }
}
