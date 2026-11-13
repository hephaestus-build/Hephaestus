package de.tum.cit.aet.hephaestus.integration.outline.capture;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentProjection;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentProjection.ProjectedDocument;
import de.tum.cit.aet.hephaestus.mentor.spi.MentorMessageQuery;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Document bodies and author names supplied through {@link DocumentProjection} are untrusted data, never instructions.
 * The server builds the context of a Heph turn, so this source exists wherever capture runs.
 */
@Component
@ConditionalOnServerRole
@ConditionalOnProperty(name = "hephaestus.integration.outline.enabled", havingValue = "true", matchIfMissing = false)
public class DocumentCaptureSource implements ContentSource {

    public static final String OUTPUT_KEY = ContentSource.OUTPUT_PREFIX + "outline_docs.json";

    static final int MAX_MENTOR_DOCUMENTS = 15;

    static final int MAX_QUERY_TERMS = 24;

    // URL slugs and hosts add noise to document-search terms.
    private static final Pattern URL_NOISE = Pattern.compile("https?://\\S+");

    private static final Pattern NON_TERM = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    static final int MENTOR_BODY_CHARS = 4_000;

    private final DocumentProjection projection;
    private final ObjectMapper objectMapper;
    private final MentorMessageQuery mentorMessages;

    public DocumentCaptureSource(
            DocumentProjection projection, ObjectMapper objectMapper, MentorMessageQuery mentorMessages) {
        this.projection = projection;
        this.objectMapper = objectMapper;
        this.mentorMessages = mentorMessages;
    }

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    /** Documentation is enrichment: a missing corpus or resolution failure degrades to writing nothing. */
    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        contributeMentor(request, files);
    }

    private void contributeMentor(MentorContextRequest request, Map<String, byte[]> files) {
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
            if (doc.url() != null) {
                node.put("url", doc.url());
            }
            node.put("body", excerptBody(doc));
            String full = doc.bodyMarkdown();
            if (!doc.deleted() && full != null && full.length() > MENTOR_BODY_CHARS) {
                node.put("bodyTruncated", true);
                node.put("bodyChars", full.length());
            }
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
            if (doc.createdByAccountId() != null) {
                node.put("author_account_id", doc.createdByAccountId().toString());
            }
            if (doc.updatedByName() != null) {
                node.put("last_edited_by", doc.updatedByName());
            }
            if (doc.updatedByAccountId() != null) {
                node.put("last_edited_by_account_id", doc.updatedByAccountId().toString());
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
                    if (collaborator.accountId() != null) {
                        entry.put("account_id", collaborator.accountId().toString());
                    }
                }
            }
            emitted++;
        }
        try {
            var root = objectMapper.createObjectNode();
            root.set("documents", array);
            root.put("hasMore", documents.size() > MAX_MENTOR_DOCUMENTS);
            files.put(OUTPUT_KEY, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize Outline documents context", e);
        }
    }

    // Fall back to recency when no query matches; documentation enrichment must not require a matching user message.
    private List<ProjectedDocument> rankedMentorDocuments(MentorContextRequest request) {
        String query = deriveQueryText(null, currentUserMessageText(request));
        if (!query.isBlank()) {
            List<ProjectedDocument> ranked =
                    projection.searchDocuments(request.workspaceId(), query, MAX_MENTOR_DOCUMENTS + 1);
            if (!ranked.isEmpty()) {
                return ranked;
            }
        }
        return projection.documentsForWorkspace(request.workspaceId());
    }

    @Nullable
    private String currentUserMessageText(MentorContextRequest request) {
        if (request.stimulusEntryId() == null) {
            return null;
        }
        return mentorMessages
                .textForMessage(request.workspaceId(), request.developerId(), request.stimulusEntryId())
                .orElse(null);
    }

    /**
     * Distinct keywords from the document title + body, {@code OR}-joined for {@code websearch_to_tsquery}:
     * unquoted websearch terms are ANDed, which over-constrains any real item text to zero matches, so
     * relevance has to come from {@code ts_rank} over OR'd terms instead. URLs and sub-3-char tokens carry
     * no search terms and are dropped; empty when nothing survives.
     */
    static String deriveQueryText(@Nullable String title, @Nullable String body) {
        String raw = (title == null ? "" : title) + " " + (body == null ? "" : body);
        String cleaned = URL_NOISE.matcher(raw).replaceAll(" ");
        Set<String> terms = new LinkedHashSet<>();
        for (String token : NON_TERM.split(cleaned, -1)) {
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
