package de.tum.cit.aet.hephaestus.agent.context.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.outline.domain.signal.DocsSignals;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The evidence half of the {@code docs.document} contract: the two files a document review reads.
 *
 * <p>The interesting cases are the two absences. A subject the mirror no longer holds, and a subject
 * whose body was evicted, both have to produce <em>no file</em> rather than an empty one — the evidence
 * layer reports a missing required source as a refusal with a reason, while a document-shaped file with
 * no document in it reads to the model as a document that said nothing, and a practice would then
 * report a developer for writing it.
 */
class DocumentContentSourceTest extends BaseUnitTest {

    private static final long WORKSPACE_ID = 42L;
    private static final long DOCUMENT_ID = 7L;
    private static final SourceKind SOURCE_KIND = new SourceKind("docs.document.core");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private DocumentProjection projection;

    private DocumentContentSource source;

    @BeforeEach
    void setUp() {
        source = new DocumentContentSource(projection, objectMapper);
    }

    @Test
    @DisplayName("declares the same kind the domain module owns, so the restated literal cannot drift")
    void declaresTheKindTheDomainOwns() {
        assertThat(source.artifactKind()).isEqualTo(DocsSignals.DOCUMENT);
        assertThat(source.sourceKinds()).extracting(kind -> kind.value()).containsExactly("docs.document.core");
    }

    @Test
    void buildsOnlyForADocumentReview() {
        assertThat(source.supports(new ContextRequest.DocumentReviewRequest(job())))
                .isTrue();
        assertThat(source.supports(new ContextRequest.PracticeReviewRequest(job())))
                .isFalse();
    }

    @Test
    @DisplayName("stages the body as the wiki holds it and its provenance as data beside it")
    void stagesTheBodyRawAndTheProvenanceAsJson() {
        String body = "# Architecture decision\n\nThe body prose.\n";
        when(projection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.of(document(body)));
        Map<String, byte[]> files = new LinkedHashMap<>();

        source.contribute(new ContextRequest.DocumentReviewRequest(job()), files);

        assertThat(files).containsOnlyKeys(DocumentContentSource.BODY_KEY, DocumentContentSource.METADATA_KEY);
        assertThat(DocumentContentSource.BODY_KEY).isEqualTo("inputs/context/document.md");
        assertThat(DocumentContentSource.METADATA_KEY).isEqualTo("inputs/context/document.json");
        // Byte for byte: no banner, no front matter, nothing the author did not write.
        assertThat(new String(files.get(DocumentContentSource.BODY_KEY), StandardCharsets.UTF_8))
                .isEqualTo(body);

        JsonNode metadata = objectMapper.readTree(files.get(DocumentContentSource.METADATA_KEY));
        assertThat(metadata.propertyNames())
                .containsExactlyInAnyOrder(
                        "title",
                        "collection",
                        "collectionSlug",
                        "slug",
                        "createdBy",
                        "updatedBy",
                        "createdAt",
                        "updatedAt",
                        "archived");
        assertThat(metadata.get("title").asString()).isEqualTo("Architecture decision");
        assertThat(metadata.get("collection").asString()).isEqualTo("Engineering");
        assertThat(metadata.get("collectionSlug").asString()).isEqualTo("engineering");
        assertThat(metadata.get("slug").asString()).isEqualTo("architecture-decision");
        assertThat(metadata.get("createdBy").asString()).isEqualTo("Ada Lovelace");
        assertThat(metadata.get("updatedBy").asString()).isEqualTo("Ada Lovelace");
        assertThat(metadata.get("createdAt").asString()).isEqualTo("2026-08-01T00:00:00Z");
        assertThat(metadata.get("updatedAt").asString()).isEqualTo("2026-08-05T00:00:00Z");
        assertThat(metadata.get("archived").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("emits nothing when the mirror no longer holds the subject")
    void emitsNothingWhenTheSubjectIsGone() {
        when(projection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.empty());
        Map<String, byte[]> files = new LinkedHashMap<>();

        source.contribute(new ContextRequest.DocumentReviewRequest(job()), files);

        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("emits nothing when the subject has no readable body")
    void emitsNothingWhenTheBodyIsUnreadable() {
        when(projection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.of(document(null)));
        Map<String, byte[]> files = new LinkedHashMap<>();

        source.contribute(new ContextRequest.DocumentReviewRequest(job()), files);

        assertThat(files).isEmpty();
    }

    @Test
    @DisplayName("claims COMPLETE only for a document it actually read")
    void reportsItsOwnCompleteness() {
        when(projection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.of(document("The body prose.")));

        var captured = source.capture(new ContextRequest.DocumentReviewRequest(job()), Set.of(SOURCE_KIND));

        assertThat(captured.completeness()).containsEntry(SOURCE_KIND, SourceCompleteness.COMPLETE);
        assertThat(captured.contentStates()).containsEntry(SOURCE_KIND, SourceContentState.NON_EMPTY);
        assertThat(captured.stateOverrides()).isEmpty();
    }

    @Test
    @DisplayName("a missing subject is reported as unavailable, never as a complete reading of nothing")
    void reportsAnAbsenceRatherThanAnEmptyDocument() {
        when(projection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.empty());

        var captured = source.capture(new ContextRequest.DocumentReviewRequest(job()), Set.of(SOURCE_KIND));

        // The catalog lets this source claim COMPLETE, so staying silent here would misreport an unread
        // document as one that said nothing.
        assertThat(captured.completeness()).isEmpty();
        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides())
                .containsEntry(SOURCE_KIND, new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND));
    }

    @Test
    @DisplayName("an evicted body is distinguished from a document that was never there")
    void separatesAnEvictedBodyFromAMissingDocument() {
        when(projection.documentById(WORKSPACE_ID, DOCUMENT_ID)).thenReturn(Optional.of(document(null)));

        var captured = source.capture(new ContextRequest.DocumentReviewRequest(job()), Set.of(SOURCE_KIND));

        assertThat(captured.stateOverrides())
                .containsEntry(SOURCE_KIND, new SourceCaptureState.Unavailable(SourceAbsenceReason.CONTENT_EVICTED));
    }

    @Test
    void capturesNothingWhenItsSourceWasNotSelected() {
        var captured = source.capture(
                new ContextRequest.DocumentReviewRequest(job()), Set.of(new SourceKind("scm.pull-request.diff")));

        assertThat(captured.files()).isEmpty();
        assertThat(captured.stateOverrides()).isEmpty();
    }

    @Test
    @DisplayName("refuses a job that names no document rather than reviewing an arbitrary one")
    void refusesAJobWithoutASubject() {
        AgentJob job = job();
        job.setMetadata(objectMapper.createObjectNode());

        assertThatThrownBy(
                        () -> source.contribute(new ContextRequest.DocumentReviewRequest(job), new LinkedHashMap<>()))
                .isInstanceOf(JobPreparationException.class);
    }

    private AgentJob job() {
        AgentJob job = new AgentJob();
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put(DocumentContentSource.DOCUMENT_ID_METADATA_KEY, DOCUMENT_ID);
        job.setMetadata(metadata);
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return job;
    }

    private static ProjectedDocument document(@Nullable String body) {
        return new ProjectedDocument(
                "engineering",
                "architecture-decision",
                "Architecture decision",
                body,
                false,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-05T00:00:00Z"),
                "Ada Lovelace",
                "outline|ada",
                null,
                "Ada Lovelace",
                "outline|ada",
                null,
                List.of(),
                false,
                "Engineering");
    }
}
