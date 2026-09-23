package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.IssueReviewRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.PracticeReviewRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceContribution;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceSource;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Materialises a workspace's mirrored Outline documents into the sandbox context — a pure extract+load of raw doc
 * rows through the agent-owned {@link DocumentProjection} SPI, so this source never reads {@code outline_document}
 * itself and the coupling runs one way.
 *
 * <p>Mentor chat emits one {@code outline_docs.json}; review emits a {@code .md} tree under
 * {@code inputs/context/outline/}. Both are telescoped, never the whole corpus.
 *
 * <p>Document bodies and author names are attacker-controlled third-party text, so every review {@code .md} carries
 * an inline {@code UNTRUSTED_EXTERNAL} banner: the body must read as data, never as instructions.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineDocumentContentSource implements EvidenceSource {

    private static final SourceKind KIND = new SourceKind("outline.documents");

    @Override
    public Set<SourceKind> sourceKinds() {
        return Set.of(KIND);
    }

    @Override
    public SourceKind sourceKindFor(String path) {
        return KIND;
    }

    private static final Logger log = LoggerFactory.getLogger(OutlineDocumentContentSource.class);

    /** Mentor-path output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "outline_docs.json";

    /** Review-path sub-tree root for the per-document {@code .md} files. */
    static final String REVIEW_PREFIX = OUTPUT_PREFIX + "outline/";

    /**
     * Index of staged documents and unresolved links below {@link #REVIEW_PREFIX}. Written
     * even when empty, so an empty search result is distinct from an absent capture.
     */
    static final String REVIEW_INDEX_KEY = REVIEW_PREFIX + "index.json";

    /** Cap on documents surfaced to the mentor per turn — the corpus-breadth envelope (telescope, not dump). */
    static final int MAX_MENTOR_DOCUMENTS = 15;

    /**
     * Retrieval fills the review set with top full-text hits only when fewer than this many documents were
     * link-resolved. Links stay first: they are explicit author intent.
     */
    static final int REVIEW_RETRIEVAL_TARGET = 3;

    /** Cap on distinct query terms derived from an artifact — bounds the tsquery, keeps ranking sharp. */
    static final int MAX_QUERY_TERMS = 24;

    /** URLs carry no retrieval signal (slugs/hosts pollute the term set) — stripped before tokenizing. */
    private static final Pattern URL_NOISE = Pattern.compile("https?://\\S+");

    /** Splits artifact text into candidate terms; everything non-alphanumeric is markdown/punctuation noise. */
    private static final Pattern NON_TERM = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    /** Per-document body excerpt fed to the mentor; keeps the single JSON file bounded. */
    static final int MENTOR_BODY_CHARS = 4_000;

    private final DocumentProjection projection;
    private final ObjectMapper objectMapper;
    private final PullRequestRepository pullRequestRepository;
    private final IssueRepository issueRepository;
    private final ChatMessageRepository chatMessageRepository;

    public OutlineDocumentContentSource(
            DocumentProjection projection,
            ObjectMapper objectMapper,
            PullRequestRepository pullRequestRepository,
            IssueRepository issueRepository,
            ChatMessageRepository chatMessageRepository) {
        this.projection = projection;
        this.objectMapper = objectMapper;
        this.pullRequestRepository = pullRequestRepository;
        this.issueRepository = issueRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return (request instanceof MentorChatRequest
                || request instanceof PracticeReviewRequest
                || request instanceof IssueReviewRequest);
    }

    /** Documentation is enrichment: a missing corpus or resolution failure degrades to writing nothing. */
    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        try {
            if (request instanceof MentorChatRequest mentor) {
                contributeMentor(mentor, files);
            } else if (request instanceof PracticeReviewRequest review) {
                contributeReview(review.job(), "pull_request_id", files, true);
            } else if (request instanceof IssueReviewRequest issueReview) {
                contributeReview(issueReview.job(), "issue_id", files, false);
            }
        } catch (RuntimeException e) {
            throw new EvidenceCollectionException("Outline-document collection failed", e);
        }
    }

    private void contributeMentor(MentorChatRequest request, Map<String, byte[]> files) {
        List<ProjectedDocument> documents = rankedMentorDocuments(request);
        if (documents.isEmpty()) {
            return;
        }
        ArrayNode array = objectMapper.createArrayNode();
        int emitted = 0;
        for (ProjectedDocument doc : documents) {
            if (emitted >= MAX_MENTOR_DOCUMENTS) {
                break;
            }
            ObjectNode node = array.addObject();
            node.put("collection", doc.collectionSlug());
            node.put("slug", doc.slug());
            node.put("title", doc.title());
            node.put("body", excerptBody(doc));
            // Human-facing collection label; "collection" above stays the path/slug identity.
            if (doc.collectionName() != null) {
                node.put("collection_name", doc.collectionName());
            }
            if (doc.createdAt() != null) {
                node.put("created", doc.createdAt().toString());
            }
            if (doc.updatedAt() != null) {
                node.put("last_updated", doc.updatedAt().toString());
            }
            if (doc.createdByName() != null) {
                node.put("author", doc.createdByName());
            }
            if (doc.createdByMemberId() != null) {
                node.put("author_member_id", doc.createdByMemberId());
            }
            if (doc.updatedByName() != null) {
                node.put("last_edited_by", doc.updatedByName());
            }
            if (doc.updatedByMemberId() != null) {
                node.put("last_edited_by_member_id", doc.updatedByMemberId());
            }
            if (!doc.collaborators().isEmpty()) {
                // Machine-facing: raw subjects are included here; the human byline never shows them.
                ArrayNode collaborators = node.putArray("collaborators");
                for (ProjectedDocument.Collaborator collaborator : doc.collaborators()) {
                    ObjectNode entry = collaborators.addObject();
                    entry.put("subject", collaborator.subject());
                    if (collaborator.name() != null) {
                        entry.put("name", collaborator.name());
                    }
                    if (collaborator.memberId() != null) {
                        entry.put("member_id", collaborator.memberId());
                    }
                }
            }
            emitted++;
        }
        try {
            files.put(OUTPUT_KEY, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(array));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Outline documents context", e);
        }
    }

    /**
     * Ranked by relevance to the turn's user message when one is available and matches anything;
     * recency order ({@link DocumentProjection#documentsForWorkspace}) otherwise, so a query-less turn
     * (or a corpus the query misses entirely) still gets documentation instead of nothing.
     */
    private List<ProjectedDocument> rankedMentorDocuments(MentorChatRequest request) {
        String query = deriveQueryText(null, currentUserMessageText(request));
        if (!query.isBlank()) {
            List<ProjectedDocument> ranked =
                    projection.searchDocuments(request.workspaceId(), query, MAX_MENTOR_DOCUMENTS);
            if (!ranked.isEmpty()) {
                return ranked;
            }
        }
        return projection.documentsForWorkspace(request.workspaceId());
    }

    @Nullable
    private String currentUserMessageText(MentorChatRequest request) {
        if (request.currentUserMessageId() == null) {
            return null;
        }
        return chatMessageRepository
                .findById(request.currentUserMessageId())
                .map(message -> visibleText(message.getParts()))
                .orElse(null);
    }

    /** Concatenated {@code text} parts of a UIMessage parts array (the mentor message substrate). */
    private static String visibleText(@Nullable JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JsonNode part : parts) {
            if ("text".equals(part.path("type").asString())) {
                out.append(part.path("text").asString()).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Distinct keywords from the artifact title + body, {@code OR}-joined for {@code websearch_to_tsquery}:
     * unquoted websearch terms are ANDed, which over-constrains any real artifact text to zero matches, so
     * relevance has to come from {@code ts_rank} over OR'd terms instead. URLs and sub-3-char tokens carry
     * no signal and are dropped; empty when nothing survives.
     */
    static String deriveQueryText(@Nullable String title, @Nullable String body) {
        String raw = (title == null ? "" : title) + " " + (body == null ? "" : body);
        String cleaned = URL_NOISE.matcher(raw).replaceAll(" ");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : NON_TERM.split(cleaned)) {
            if (token.length() < 3) {
                continue;
            }
            terms.add(token.toLowerCase(Locale.ROOT));
            if (terms.size() >= MAX_QUERY_TERMS) {
                break;
            }
        }
        return String.join(" OR ", terms);
    }

    private static String excerptBody(ProjectedDocument doc) {
        if (doc.deleted() || doc.bodyMarkdown() == null) {
            return "(document removed upstream or evicted from the local mirror)";
        }
        String body = doc.bodyMarkdown();
        if (body.length() <= MENTOR_BODY_CHARS) {
            return body;
        }
        int end = MENTOR_BODY_CHARS;
        // Never split a UTF-16 surrogate pair at the excerpt boundary.
        if (Character.isHighSurrogate(body.charAt(end - 1))) {
            end--;
        }
        return body.substring(0, end);
    }

    // Review path — a .md tree scoped to the documents linked from the artifact.

    private void contributeReview(
            AgentJob job, String artifactIdField, Map<String, byte[]> files, boolean pullRequest) {
        if (job == null || job.getWorkspace() == null) {
            return;
        }
        JsonNode meta = job.getMetadata();
        if (meta == null || meta.isNull() || meta.isMissingNode()) {
            return;
        }
        Long artifactId = MetaJson.optLong(meta, artifactIdField);
        if (artifactId == null) {
            return;
        }
        long workspaceId = job.getWorkspace().getId();
        Optional<Issue> artifact = pullRequest
                ? pullRequestRepository.findById(artifactId).map(pr -> (Issue) pr)
                : issueRepository.findById(artifactId);
        String title = artifact.map(Issue::getTitle).orElse(null);
        String body = artifact.map(Issue::getBody).orElse(null);
        // The link grammar (what a documentation reference looks like) is the projection impl's vendor
        // knowledge — this source stays vendor-blind.
        Set<String> references = projection.extractReferences(body);
        List<ProjectedDocument> linked =
                references.isEmpty() ? List.of() : projection.documentsByReference(workspaceId, references);
        Set<String> unresolved = references.isEmpty() ? Set.of() : unresolvedReferences(references, linked);
        // Deterministic order + de-dup by path: linked docs sorted by (collection, slug, title) so a slug
        // collision resolves stably to the first document and the materialised bytes are identical across runs.
        Map<String, ProjectedDocument> byPath = new LinkedHashMap<>();
        linked.stream()
                .sorted(Comparator.comparing(
                                ProjectedDocument::collectionSlug, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ProjectedDocument::slug, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(ProjectedDocument::title, Comparator.nullsFirst(Comparator.naturalOrder())))
                .forEach(doc -> byPath.putIfAbsent(reviewPath(doc), doc));
        Set<String> linkedPaths = Set.copyOf(byPath.keySet());
        // Retrieval fill: links are explicit author intent and always materialise; when they undershoot the
        // target, full-text hits for the artifact text fill the remainder (rank order, deduped by path) so a
        // relevant-but-unlinked document still reaches the review.
        if (byPath.size() < REVIEW_RETRIEVAL_TARGET) {
            String query = deriveQueryText(title, body);
            if (!query.isBlank()) {
                List<ProjectedDocument> hits =
                        projection.searchDocuments(workspaceId, query, REVIEW_RETRIEVAL_TARGET + byPath.size());
                for (ProjectedDocument hit : hits) {
                    if (byPath.size() >= REVIEW_RETRIEVAL_TARGET) {
                        break;
                    }
                    byPath.putIfAbsent(reviewPath(hit), hit);
                }
            }
        }
        for (Map.Entry<String, ProjectedDocument> entry : byPath.entrySet()) {
            String markdown = entry.getValue().bodyMarkdown();
            if (markdown != null && !entry.getValue().deleted()) {
                files.computeIfAbsent(entry.getKey(), unused -> markdown.getBytes(StandardCharsets.UTF_8));
            }
        }
        writeReviewIndex(files, byPath, linkedPaths, unresolved);
    }

    /** The staged documents, in staging order, with what the wiki knows about each. Written even when there are none. */
    private void writeReviewIndex(
            Map<String, byte[]> files,
            Map<String, ProjectedDocument> byPath,
            Set<String> linkedPaths,
            Set<String> unresolved) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode documents = root.putArray("documents");
        for (Map.Entry<String, ProjectedDocument> entry : byPath.entrySet()) {
            ProjectedDocument doc = entry.getValue();
            ObjectNode node = documents.addObject();
            node.put("path", entry.getKey());
            // A body the mirror no longer holds stages no file; the entry says the link pointed somewhere.
            node.put("available", files.containsKey(entry.getKey()));
            node.put("selectedBy", linkedPaths.contains(entry.getKey()) ? "LINK" : "SEARCH");
            node.put("collection", doc.collectionSlug());
            node.put("collectionName", doc.collectionName());
            node.put("slug", doc.slug());
            node.put("title", doc.title());
            node.put("createdBy", doc.createdByName());
            node.put("updatedBy", doc.updatedByName());
            node.put(
                    "updatedAt",
                    doc.updatedAt() == null ? null : doc.updatedAt().toString());
            node.put("archived", doc.archived());
            ArrayNode contributors = node.putArray("contributors");
            doc.collaborators().stream()
                    .map(ProjectedDocument.Collaborator::name)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(contributors::add);
        }
        ArrayNode unresolvedRefs = root.putArray("unresolvedReferences");
        unresolved.forEach(unresolvedRefs::add);
        files.put(
                REVIEW_INDEX_KEY, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
    }

    /**
     * Reads emptiness out of the index rather than out of the staged file list.
     *
     * <p>The index is always staged, so "there is a file" is no longer the same question as "a document was
     * found". Left to the default, every review would report documentation as present.
     */
    @Override
    public EvidenceContribution capture(ContextRequest request, Set<SourceKind> selectedKinds) {
        EvidenceContribution captured = EvidenceSource.super.capture(request, selectedKinds);
        byte[] index = captured.files().get(REVIEW_INDEX_KEY);
        if (!selectedKinds.contains(KIND) || index == null) {
            return captured;
        }
        JsonNode documents = objectMapper.readTree(index).path("documents");
        return new EvidenceContribution(
                captured.files(),
                captured.completeness(),
                captured.immutableIdentities(),
                captured.observedAt(),
                captured.sourceEffectiveAt(),
                Map.of(KIND, documents.isEmpty() ? SourceContentState.EMPTY : SourceContentState.NON_EMPTY));
    }

    private static String reviewPath(ProjectedDocument doc) {
        return (REVIEW_PREFIX + slugSegment(doc.collectionSlug(), "uncategorized")
                + "/"
                + slugSegment(doc.slug(), "untitled")
                + ".md");
    }

    /**
     * The extracted references that did NOT resolve to a mirrored document, in extraction order.
     *
     * <p>{@link DocumentProjection#documentsByReference} does not report which reference produced which
     * document, and this source stays vendor-blind to the link grammar, so resolution is checked with a
     * generic, conservative containment test: a reference counts as resolved the moment ANY returned
     * document's {@code slug}/{@code collectionSlug} appears inside it. The bias is deliberately
     * one-sided — a false negative here is harmless, a false positive would itself be a nag.
     */
    private static Set<String> unresolvedReferences(Set<String> references, List<ProjectedDocument> documents) {
        if (documents.isEmpty()) {
            return references;
        }
        List<String> resolvedTokens = documents.stream()
                .flatMap(doc -> Stream.of(doc.slug(), doc.collectionSlug()))
                .filter(token -> token != null && !token.isBlank())
                .map(token -> token.toLowerCase(Locale.ROOT))
                .toList();
        Set<String> unresolved = new LinkedHashSet<>();
        for (String reference : references) {
            String lower = reference.toLowerCase(Locale.ROOT);
            boolean resolved = resolvedTokens.stream().anyMatch(lower::contains);
            if (!resolved) {
                unresolved.add(reference);
            }
        }
        return unresolved;
    }

    /** Sanitises a collection/document slug into a safe, deterministic path segment; falls back when empty. */
    private static String slugSegment(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String cleaned =
                raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
