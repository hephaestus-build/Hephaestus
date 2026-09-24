package de.tum.cit.aet.hephaestus.agent.context.providers;

import static de.tum.cit.aet.hephaestus.agent.handler.spi.JobMetadataReader.requireLong;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewContextBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises the {@code docs.document} review context under {@code inputs/context/} as one quarantined
 * {@code document.md}: the document's prose, with its title, collection, author and upstream timestamps in
 * a front-matter block.
 *
 * <p>The repo-less, diff-less counterpart of {@link IssueContentSource}, and deliberately <em>not</em> the
 * same thing as {@link OutlineDocumentContentSource}: that one collects documents a change happens to
 * reference as supporting evidence, so retrieval there can never prove it found every relevant one. Here
 * the document is the subject, so the one document a review was occasioned by is a complete capture of it.
 *
 * <p>Reads the mirror through the agent-owned {@link DocumentProjection} SPI, implemented by the vendor
 * module owning the schema, so the dependency runs one way and this class names no vendor.
 */
@Component
public class DocumentContentSource implements EvidenceSource, ReviewContextBuilder {

    /**
     * {@code ReviewContractValidator} refuses to start if a descriptor calls itself reviewable and no builder
     * claims its kind — this bean is what opens {@code docs.document} for practice authoring.
     */
    @Override
    public ArtifactKind artifactKind() {
        return DOCUMENT;
    }

    /**
     * Restated rather than imported: {@code agent} may not depend on a vendor module. Held to the
     * descriptor's spelling by {@code DocumentContentSourceTest}.
     */
    private static final ArtifactKind DOCUMENT = ArtifactKind.of("docs.document");

    private static final SourceKind KIND = new SourceKind("docs.document.core");

    /** The job-metadata key naming the mirrored document a review is about. */
    public static final String DOCUMENT_ID_METADATA_KEY = "docs_document_id";

    /** The body as the wiki holds it, unchanged. */
    static final String BODY_KEY = OUTPUT_PREFIX + "document.md";

    /** Who wrote it, where it lives and when it changed; third-party text like the body. */
    static final String METADATA_KEY = OUTPUT_PREFIX + "document.json";

    private static final Logger log = LoggerFactory.getLogger(DocumentContentSource.class);

    private final DocumentProjection projection;
    private final ObjectMapper objectMapper;

    public DocumentContentSource(DocumentProjection projection, ObjectMapper objectMapper) {
        this.projection = projection;
        this.objectMapper = objectMapper;
    }

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof ContextRequest.DocumentReviewRequest;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        files.putAll(resolve(request).files());
    }

    /**
     * Overridden rather than left to the catalog default: {@code docs.document.core} declares
     * {@code supportsComplete}, so an empty capture there would read as a complete reading of a document
     * that said nothing rather than one the mirror had lost. One row, rendered whole, so the only honest
     * states are COMPLETE or an absence with a reason.
     */
    @Override
    @Transactional(readOnly = true)
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        if (!selectedKinds.contains(KIND)) {
            return new EvidenceContribution(Map.of(), Map.of());
        }
        Subject subject = resolve(request);
        if (subject.files().isEmpty()) {
            return new EvidenceContribution(
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(),
                    Map.of(KIND, SourceContentState.EMPTY),
                    Map.of(KIND, new SourceCaptureState.Unavailable(subject.absence())));
        }
        return new EvidenceContribution(
                subject.files(),
                Map.of(KIND, SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(KIND, SourceContentState.NON_EMPTY),
                Map.of());
    }

    /** @param absence why, meaningful only when {@code files} is empty */
    private record Subject(Map<String, byte[]> files, SourceAbsenceReason absence) {
        static Subject absent(SourceAbsenceReason reason) {
            return new Subject(Map.of(), reason);
        }
    }

    private Subject resolve(ContextRequest request) {
        AgentJob job = ((ContextRequest.DocumentReviewRequest) request).job();
        JsonNode metadata = job.getMetadata();
        if (metadata == null || metadata.isNull() || metadata.isMissingNode()) {
            throw new JobPreparationException("Job has no metadata: jobId=" + job.getId());
        }
        if (job.getWorkspace() == null) {
            throw new JobPreparationException("Job has no workspace: jobId=" + job.getId());
        }
        long workspaceId = job.getWorkspace().getId();
        long documentId = requireLong(metadata, DOCUMENT_ID_METADATA_KEY);

        Optional<DocumentProjection.ProjectedDocument> found = projection.documentById(workspaceId, documentId);
        if (found.isEmpty()) {
            log.info("Document context: subject not found, documentId={}, jobId={}", documentId, job.getId());
            return Subject.absent(SourceAbsenceReason.NOT_FOUND);
        }
        DocumentProjection.ProjectedDocument document = found.get();
        if (document.deleted()) {
            log.info("Document context: subject is tombstoned, documentId={}, jobId={}", documentId, job.getId());
            return Subject.absent(SourceAbsenceReason.NOT_FOUND);
        }
        if (document.bodyMarkdown() == null) {
            // Row present, body evicted under the mirror's size cap: distinct from NOT_FOUND, so an operator is
            // told to raise the cap rather than look for a deleted document.
            log.info(
                    "Document context: subject has no mirrored body, documentId={}, jobId={}", documentId, job.getId());
            return Subject.absent(SourceAbsenceReason.CONTENT_EVICTED);
        }
        log.info("Document context built: documentId={}, jobId={}", documentId, job.getId());
        ObjectNode metadataNode = objectMapper.createObjectNode();
        metadataNode.put("title", document.title());
        metadataNode.put("collection", document.collectionName());
        metadataNode.put("collectionSlug", document.collectionSlug());
        metadataNode.put("slug", document.slug());
        metadataNode.put("createdBy", document.createdByName());
        metadataNode.put("updatedBy", document.updatedByName());
        metadataNode.put(
                "createdAt",
                document.createdAt() == null ? null : document.createdAt().toString());
        metadataNode.put(
                "updatedAt",
                document.updatedAt() == null ? null : document.updatedAt().toString());
        metadataNode.put("archived", document.archived());
        try {
            return new Subject(
                    Map.of(
                            METADATA_KEY,
                            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(metadataNode),
                            BODY_KEY,
                            document.bodyMarkdown().getBytes(StandardCharsets.UTF_8)),
                    SourceAbsenceReason.NOT_FOUND);
        } catch (JacksonException e) {
            throw new JobPreparationException("Failed to serialize document metadata", e);
        }
    }
}
