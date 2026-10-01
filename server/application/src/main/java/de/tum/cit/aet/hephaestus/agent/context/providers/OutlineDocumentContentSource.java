package de.tum.cit.aet.hephaestus.agent.context.providers;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidenceCollectionException;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection;
import de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.mentor.ChatMessageRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
 * <p>Heph 1.x emits one purpose-bound {@code outline_docs.json}. Practice reviews use the job folder renderer.
 *
 * <p>Document bodies and author names are untrusted external data, never runtime instructions.
 */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class OutlineDocumentContentSource implements ContentSource {

    private static final Logger log = LoggerFactory.getLogger(OutlineDocumentContentSource.class);

    /** Mentor-path output key. Whitelisted in {@code MentorContextKeys#ALLOWED_OUTPUT_KEYS}. */
    public static final String OUTPUT_KEY = ContentSource.OUTPUT_PREFIX + "outline_docs.json";

    /** Cap on documents surfaced to the mentor per turn — the corpus-breadth envelope (telescope, not dump). */
    static final int MAX_MENTOR_DOCUMENTS = 15;

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
    private final ChatMessageRepository chatMessageRepository;

    public OutlineDocumentContentSource(
            DocumentProjection projection, ObjectMapper objectMapper, ChatMessageRepository chatMessageRepository) {
        this.projection = projection;
        this.objectMapper = objectMapper;
        this.chatMessageRepository = chatMessageRepository;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
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
     * recency order ({@link de.tum.cit.aet.hephaestus.agent.documentation.DocumentProjection#documentsForWorkspace}) otherwise, so a query-less turn
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
}
