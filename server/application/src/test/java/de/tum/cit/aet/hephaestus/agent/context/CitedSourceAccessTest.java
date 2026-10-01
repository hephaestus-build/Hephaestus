package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewHistoryContentSource;
import de.tum.cit.aet.hephaestus.agent.context.providers.ReviewRepositoryPreparer;
import de.tum.cit.aet.hephaestus.agent.conversation.ConversationThreadProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.ReviewMemberAiPolicy;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.core.fabric.FabricLayout;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class CitedSourceAccessTest extends BaseUnitTest {
    @TempDir
    Path root;

    private final JsonMapper mapper = new JsonMapper();
    private final ConversationThreadProjection conversations = mock(ConversationThreadProjection.class);
    private final DocumentProjection documents = mock(DocumentProjection.class);
    private final ReviewRepositoryPreparer repositories = mock(ReviewRepositoryPreparer.class);
    private final ReviewHistoryContentSource history = mock(ReviewHistoryContentSource.class);
    private final ReviewMemberAiPolicy memberPolicy = mock(ReviewMemberAiPolicy.class);
    private final MemberAiPreferences preferences = mock(MemberAiPreferences.class);
    private final WorkspaceMembershipRepository memberships = mock(WorkspaceMembershipRepository.class);

    private AgentJob job() {
        var workspace = new Workspace();
        workspace.setId(1L);
        var job = new AgentJob();
        job.setWorkspace(workspace);
        job.setId(UUID.randomUUID());
        job.setWorkerId("worker");
        return job;
    }

    private CitedSourceAccess access(JobEvidenceFiles files) {
        return new CitedSourceAccess(
                conversations, documents, repositories, history, files, mapper, memberPolicy, preferences, memberships);
    }

    @Test
    void bindsTheFrozenMessageIdentityNotAnAgentSuppliedReferenceAndRechecksConsent() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC());
        var job = job();
        String path = "context/chat/C1/2026-10.jsonl";
        byte[] bytes = "{\"channel\":\"C1\",\"ts\":\"1.000001\",\"text\":\"quote\",\"synced_at\":null}\n"
                .getBytes(StandardCharsets.UTF_8);
        try (var prepared = files.prepare(job, new PreparedEvidence(Map.of(path, bytes), null), null)) {
            assertThat(prepared.filesOnDisk()).containsKey(path);
            var citation = mapper.createObjectNode()
                    .put("artifactPath", path)
                    .put("startLine", 1)
                    .put("endLine", 1);
            citation.putObject("sourceReference").put("channel", "foreign");
            when(conversations.isMessageReadable(1L, "C1", "1.000001")).thenReturn(true);
            var access = access(files);
            access.bind(job, citation, ProvenanceDigest.sha256Hex(bytes));
            assertThat(citation.path("sourceReference")
                            .path("records")
                            .get(0)
                            .path("channel")
                            .asString())
                    .isEqualTo("C1");
            when(conversations.isMessageReadable(1L, "C1", "1.000001")).thenReturn(false);
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
            assertThatThrownBy(() -> access.bind(job, citation, ProvenanceDigest.sha256Hex(bytes)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("no longer permitted");
        }
    }

    @Test
    void refusesHistoryWhenItsDeveloperChangesTheProcessorChoiceAfterRendering() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC());
        var job = job();
        var citation = mapper.createObjectNode()
                .put("artifactPath", "context/people/42/observations.jsonl")
                .put("startLine", 1)
                .put("endLine", 1);
        when(memberPolicy.allowsPerson(job, 42L)).thenReturn(false);
        assertThatThrownBy(() -> access(files).bind(job, citation, "a".repeat(64)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("processor");
    }

    @Test
    void refusesMissingCanonicalReferencesAndForeignRepositories() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC());
        var access = access(files);
        for (String path : java.util.List.of(
                "context/chat/C1/2026-10.jsonl",
                "context/docs/c/d.md",
                "context/people/42/person.json",
                "context/scm/2/pulls/1/record.json",
                "repos/2/.git/HEAD")) {
            assertThat(access.permits(
                            1L,
                            mapper.createObjectNode().put("artifactPath", path),
                            SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                    .isFalse();
        }
        var citation = mapper.createObjectNode().put("artifactPath", "repos/2/.git/HEAD");
        citation.putObject("sourceReference")
                .putArray("records")
                .addObject()
                .put("type", "repository")
                .put("id", 2L);
        when(repositories.permittedRepositories(1L)).thenReturn(java.util.List.of());
        assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }
}
