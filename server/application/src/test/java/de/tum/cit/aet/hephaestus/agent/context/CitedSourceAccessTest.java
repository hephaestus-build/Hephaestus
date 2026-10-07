package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.adapter.EvidenceFolderPersonDataCatalog;
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
import de.tum.cit.aet.hephaestus.testconfig.TestEntities;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembership;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceMembershipRepository;
import de.tum.cit.aet.hephaestus.workspace.spi.MemberAiPreferences;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class CitedSourceAccessTest extends BaseUnitTest {
    private static EvidenceFolderPersonDataCatalog personCopies() {
        AutoCloseable released = () -> {};
        return Mockito.mock(
                EvidenceFolderPersonDataCatalog.class,
                invocation -> invocation.getMethod().getName().equals("finishCapture")
                        ? released
                        : Answers.RETURNS_DEFAULTS.answer(invocation));
    }

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
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
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
    void normalizedThreadCannotBypassAWithdrawnMessage() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var job = job();
        String path = "context/conversation_thread.json";
        byte[] bytes =
                "{\"channel\":\"C1\",\"messages\":[{\"ts\":\"1\"},{\"ts\":\"2\"}]}".getBytes(StandardCharsets.UTF_8);
        try (var prepared = files.prepare(job, new PreparedEvidence(Map.of(path, bytes), null), null)) {
            assertThat(prepared.filesOnDisk()).containsKey(path);
            var citation = mapper.createObjectNode().put("artifactPath", path);
            when(conversations.isMessageReadable(1L, "C1", "1")).thenReturn(true);
            when(conversations.isMessageReadable(1L, "C1", "2")).thenReturn(true);
            var access = access(files);
            access.bind(job, citation, ProvenanceDigest.sha256Hex(bytes));
            assertThat(citation.path("sourceReference").path("records")).hasSize(2);
            when(conversations.isMessageReadable(1L, "C1", "2")).thenReturn(false);
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
        }
    }

    @Test
    void normalizedDocumentCannotBypassErasure() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var job = job();
        job.setMetadata(mapper.createObjectNode().put("docs_document_id", 7L));
        var document = DocumentProjection.ProjectedDocument.withoutAuthors("c", "d", "Title", "quote", false);
        when(documents.documentById(1L, 7L)).thenReturn(Optional.of(document));
        var citation = mapper.createObjectNode().put("artifactPath", "context/document.md");
        var access = access(files);
        access.bind(job, citation, "a".repeat(64));
        assertThat(citation.path("sourceReference")
                        .path("records")
                        .get(0)
                        .path("id")
                        .asLong())
                .isEqualTo(7L);
        when(documents.documentById(1L, 7L)).thenReturn(Optional.empty());
        assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }

    @Test
    void normalizedScmCannotBypassRepositoryRemoval() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var job = job();
        job.setMetadata(mapper.createObjectNode().put("repository_id", 2L));
        var citation = mapper.createObjectNode()
                .put("artifactPath", "context/comments.json")
                .put("sourceKind", "scm.pull-request.discussion");
        when(repositories.permittedRepositories(1L)).thenReturn(List.of());
        assertThatThrownBy(() -> access(files).bind(job, citation, "a".repeat(64)))
                .isInstanceOf(JobDeliveryException.class)
                .hasMessageContaining("no longer permitted");
        assertThat(citation.path("sourceReference")
                        .path("records")
                        .get(0)
                        .path("id")
                        .asLong())
                .isEqualTo(2L);
    }

    @Test
    void composedViewsCannotBypassCanonicalRecordAuthorization() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        for (String path : List.of("inputs/history/observations.json", "context/project_inventory.json")) {
            var citation = mapper.createObjectNode().put("artifactPath", path);
            assertThatThrownBy(() -> access(files).bind(job(), citation, "a".repeat(64)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("canonical");
            citation.putObject("sourceReference")
                    .putArray("records")
                    .addObject()
                    .put("type", "person");
            assertThat(access(files).permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
        }
    }

    @Test
    void refusesHistoryWhenItsDeveloperChangesTheProcessorChoiceAfterRendering() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
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
    void malformedNumericSourceIdentityIsATypedAdmissionRefusal() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        for (String path : List.of("context/people/not-a-person/person.json", "repos/not-a-repo/.git/HEAD")) {
            assertThatThrownBy(() -> access(files)
                            .bind(job(), mapper.createObjectNode().put("artifactPath", path), "a".repeat(64)))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("source identity");
        }
    }

    @Test
    void refusesMissingCanonicalReferencesAndForeignRepositories() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var access = access(files);
        for (String path : List.of(
                "context/conversation_thread.json",
                "context/document.md",
                "inputs/history/feedback.json",
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
        when(repositories.permittedRepositories(1L)).thenReturn(List.of());
        assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"observations.jsonl", "feedback.jsonl", "person.json"})
    void canonicalPersonReferencesReuseVisibilityAndRecordWithdrawal(String filename) {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var job = job();
        String path = "context/people/42/" + filename;
        UUID recordId = UUID.randomUUID();
        byte[] bytes = ("{\"id\":\"" + recordId + "\",\"synced_at\":null}\n").getBytes(StandardCharsets.UTF_8);
        when(memberPolicy.allowsPerson(job, 42L)).thenReturn(true);
        when(preferences.forDeveloper(1L, 42L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        var member = new WorkspaceMembership();
        when(memberships.findByWorkspace_IdAndUser_Id(1L, 42L)).thenReturn(Optional.of(member));
        String type = filename.startsWith("observations") ? "observation" : "feedback";
        when(history.permitsHistoryRecord(1L, type, recordId, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(true);
        when(history.permitsHistoryRecord(1L, type, recordId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .thenReturn(true);
        try (var prepared = files.prepare(job, new PreparedEvidence(Map.of(path, bytes), null), null)) {
            assertThat(prepared.filesOnDisk()).containsKey(path);
            var citation = mapper.createObjectNode().put("artifactPath", path).put("startLine", 1);
            var access = access(files);
            access.bind(job, citation, ProvenanceDigest.sha256Hex(bytes));
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isTrue();
            if (!filename.equals("person.json")) {
                when(history.permitsHistoryRecord(1L, type, recordId, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                        .thenReturn(false);
                assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                        .isFalse();
            }
            member.setHidden(true);
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
            member.setHidden(false);
            when(memberships.findByWorkspace_IdAndUser_Id(1L, 42L)).thenReturn(Optional.empty());
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
        }
    }

    @Test
    void canonicalDocumentRechecksItsSourceIdentityAndLocation() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var job = job();
        String path = "context/docs/engineering/design.md";
        byte[] bytes = "---\nsynced_at: null\nsource_id: \"source-7\"\n---\nquote\n".getBytes(StandardCharsets.UTF_8);
        var doc = mock(DocumentProjection.ProjectedDocument.class);
        when(doc.sourceId()).thenReturn("source-7");
        when(doc.slug()).thenReturn("design");
        when(doc.collectionSlug()).thenReturn("engineering");
        when(doc.bodyMarkdown()).thenReturn("quote");
        when(documents.documentsByReference(1L, List.of("source-7"))).thenReturn(List.of(doc));
        try (var prepared = files.prepare(job, new PreparedEvidence(Map.of(path, bytes), null), null)) {
            assertThat(prepared.filesOnDisk()).containsKey(path);
            var citation = mapper.createObjectNode().put("artifactPath", path);
            var access = access(files);
            access.bind(job, citation, ProvenanceDigest.sha256Hex(bytes));
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isTrue();
            when(doc.deleted()).thenReturn(true);
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
            when(doc.deleted()).thenReturn(false);
            when(doc.bodyMarkdown()).thenReturn(null);
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
            when(doc.bodyMarkdown()).thenReturn("quote");
            when(doc.sourceId()).thenReturn("another-source");
            assertThat(access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                    .isFalse();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "[]",
                "{}",
                "{\"records\":[]}",
                "{\"records\":[{\"type\":\"unknown\"}]}",
                "{\"records\":[{\"type\":\"observation\",\"person\":42,\"id\":\"invalid\"}]}"
            })
    void malformedPersistedReferencesFailClosed(String reference) {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        when(preferences.forDeveloper(1L, 42L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        when(memberships.findByWorkspace_IdAndUser_Id(1L, 42L)).thenReturn(Optional.of(new WorkspaceMembership()));
        var citation = mapper.createObjectNode().put("artifactPath", "context/people/42/observations.jsonl");
        citation.set("sourceReference", mapper.readTree(reference));
        assertThat(access(files).permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .isFalse();
    }

    @Test
    void shouldAnswerEveryCitationAsAloneWhenOneReadChecksThemAndReadEachSourceOnce() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var access = access(files);
        when(repositories.permittedRepositories(1L)).thenReturn(List.of(TestEntities.repository(2L, "acme/api")));
        when(preferences.forDeveloper(1L, 42L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        when(memberships.findByWorkspace_IdAndUser_Id(1L, 42L)).thenReturn(Optional.of(new WorkspaceMembership()));
        var citations = List.of(
                citation("context/diff.patch", "{\"records\":[{\"type\":\"repository\",\"id\":2}]}"),
                citation("repos/2/src/Main.java", "{\"records\":[{\"type\":\"repository\",\"id\":2}]}"),
                citation("context/diff.patch", "{\"records\":[{\"type\":\"repository\",\"id\":3}]}"),
                citation("context/people/42/person.json", "{\"records\":[{\"type\":\"person\",\"person\":42}]}"),
                citation("context/people/42/person.json", "{\"records\":[{\"type\":\"person\",\"person\":42}]}"),
                citation(
                        "context/diff.patch",
                        "{\"records\":[{\"type\":\"repository\",\"id\":2},{\"type\":\"repository\",\"id\":3}]}"),
                citation(
                        "context/people/42/observations.jsonl",
                        "{\"records\":[{\"type\":\"observation\",\"person\":42,\"id\":\"invalid\"}]}"),
                mapper.createObjectNode().put("artifactPath", "context/diff.patch"));
        // Each citation alone, the way every citation was checked before one read shared its checks.
        List<Boolean> alone = citations.stream()
                .map(citation -> access.permits(1L, citation, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY))
                .toList();
        Mockito.clearInvocations(repositories, preferences, memberships);

        var checks = access.checks(1L, SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY);
        List<Boolean> together = citations.stream().map(checks::permits).toList();

        assertThat(together).isEqualTo(alone).containsExactly(true, true, false, true, true, false, false, true);
        Mockito.verify(repositories, Mockito.times(1)).permittedRepositories(1L);
        Mockito.verify(preferences, Mockito.times(1)).forDeveloper(1L, 42L);
        Mockito.verify(memberships, Mockito.times(1)).findByWorkspace_IdAndUser_Id(1L, 42L);
    }

    /**
     * The golden comparison for history: a record of a developer's history is decided by authorizing the
     * observation it names, whose own citations may name older history in turn. Checked in batches, one per link
     * of the longest chain, every citation gets the answer it gets alone, where each record is checked on its own.
     */
    @Test
    void shouldAnswerHistoryCitationsAsAloneWhenOneReadChecksTheirChainsInBatches() {
        var files = new JobEvidenceFiles(
                new FabricLayout(root.toString()), mock(AgentJobRepository.class), Clock.systemUTC(), personCopies());
        var access = access(files);
        var purpose = SourceUsePurpose.PRACTICE_FEEDBACK_DELIVERY;
        when(repositories.permittedRepositories(1L)).thenReturn(List.of(TestEntities.repository(2L, "acme/api")));
        when(preferences.forDeveloper(1L, 42L)).thenReturn(new MemberAiPreferences.Decision(false, null));
        when(memberships.findByWorkspace_IdAndUser_Id(1L, 42L)).thenReturn(Optional.of(new WorkspaceMembership()));
        UUID newest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        UUID refused = UUID.randomUUID();
        UUID onRefused = UUID.randomUUID();
        UUID onHiddenRepository = UUID.randomUUID();
        // newest cites middle, which cites oldest; onRefused cites a record its owner refuses.
        Map<UUID, List<ObjectNode>> cites = Map.of(
                newest, List.of(history(middle)),
                middle, List.of(history(oldest), repository(2L)),
                oldest, List.of(repository(2L)),
                refused, List.of(repository(2L)),
                onRefused, List.of(history(refused)),
                onHiddenRepository, List.of(repository(3L)));
        // The history source as it works: a record is permitted when its owner keeps it and each citation of the
        // observation it names is permitted, checked with whatever checks the read has running.
        Predicate<UUID> permitted = id -> !id.equals(refused)
                && cites.getOrDefault(id, List.of()).stream()
                        .allMatch(citation -> access.checks(1L, purpose).permits(citation));
        when(history.permitsHistoryRecord(
                        Mockito.eq(1L), Mockito.eq("observation"), Mockito.any(), Mockito.eq(purpose)))
                .thenAnswer(call -> permitted.test(call.getArgument(2)));
        when(history.historyRecordCitations(Mockito.eq(1L), Mockito.eq("observation"), Mockito.any()))
                .thenAnswer(call -> {
                    Map<UUID, List<JsonNode>> found = new HashMap<>();
                    for (UUID id : call.<Collection<UUID>>getArgument(2)) {
                        if (cites.containsKey(id)) found.put(id, List.copyOf(cites.get(id)));
                    }
                    return found;
                });
        when(history.permittedHistoryRecords(
                        Mockito.eq(1L), Mockito.eq("observation"), Mockito.any(), Mockito.eq(purpose)))
                .thenAnswer(call -> {
                    Collection<UUID> ids = call.getArgument(2);
                    // The batch authorizes its observations together, as the evidence authorization does.
                    var checks = access.checks(1L, purpose);
                    checks.prepare(ids.stream()
                            .flatMap(id -> cites.getOrDefault(id, List.of()).stream())
                            .map(JsonNode.class::cast)
                            .toList());
                    return ids.stream().filter(permitted).collect(Collectors.toSet());
                });
        List<ObjectNode> citations = List.of(
                history(newest),
                history(middle),
                history(onRefused),
                history(refused),
                history(onHiddenRepository),
                citation(
                        "context/people/42/observations.jsonl",
                        "{\"records\":[{\"type\":\"observation\",\"person\":42,\"id\":\"" + newest
                                + "\"},{\"type\":\"observation\",\"person\":42,\"id\":\"" + onRefused
                                + "\"}]}"));
        List<Boolean> alone = citations.stream()
                .map(citation -> access.permits(1L, citation, purpose))
                .toList();
        Mockito.clearInvocations(history);

        var checks = access.checks(1L, purpose);
        checks.prepare(List.copyOf(citations));
        List<Boolean> together = citations.stream().map(checks::permits).toList();

        assertThat(together).isEqualTo(alone).containsExactly(true, true, false, false, false, false);
        // Three links in the longest chain, three batches; no record is checked alone.
        Mockito.verify(history, Mockito.times(3))
                .permittedHistoryRecords(Mockito.eq(1L), Mockito.eq("observation"), Mockito.any(), Mockito.eq(purpose));
        Mockito.verify(history, Mockito.never())
                .permitsHistoryRecord(Mockito.anyLong(), Mockito.any(), Mockito.any(), Mockito.any());
    }

    private ObjectNode history(UUID id) {
        return citation(
                "context/people/42/observations.jsonl",
                "{\"records\":[{\"type\":\"observation\",\"person\":42,\"id\":\"" + id + "\"}]}");
    }

    private ObjectNode repository(long id) {
        return citation("context/diff.patch", "{\"records\":[{\"type\":\"repository\",\"id\":" + id + "}]}");
    }

    private ObjectNode citation(String path, String reference) {
        var citation = mapper.createObjectNode().put("artifactPath", path);
        citation.set("sourceReference", mapper.readTree(reference));
        return citation;
    }
}
