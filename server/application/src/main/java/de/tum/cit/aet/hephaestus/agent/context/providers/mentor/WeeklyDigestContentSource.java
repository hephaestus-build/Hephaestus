package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import com.fasterxml.jackson.annotation.JsonInclude;
import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.evidence.capture.ContentSource;
import de.tum.cit.aet.hephaestus.evidence.capture.MentorContextRequest;
import de.tum.cit.aet.hephaestus.mentor.MentorConversation;
import de.tum.cit.aet.hephaestus.mentor.MentorConversationService;
import de.tum.cit.aet.hephaestus.mentor.MentorEntry;
import de.tum.cit.aet.hephaestus.mentor.MentorEntryService;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestFacts;
import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The weekly digest a thread replies to, read now, so Heph can go through it with the developer. The digest comes
 * from the turn's own thread parent on the server, never from an id the model names: a thread not forked at a
 * weekly digest gets an empty document. It holds the same facts the app's card shows, through the same rules, plus
 * the results and problems behind each piece of feedback that still shows, for {@code link_result} and
 * {@code link_problem}.
 */
@Component
@ConditionalOnServerRole
@RequiredArgsConstructor
public class WeeklyDigestContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "weekly_digest.json";

    private final MentorConversationService conversations;
    private final MentorEntryService entries;
    private final WeeklyDigestFacts facts;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(MentorContextRequest request) {
        return true;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(MentorContextRequest request, Map<String, byte[]> files) {
        files.put(
                OUTPUT_KEY,
                objectMapper.writeValueAsBytes(
                        buildPayload(request.workspaceId(), request.developerId(), request.threadId())));
    }

    /** The document Heph reads: the digest as the app shows it, with the results and problems behind each piece. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Document(WeeklyDigestFacts.@Nullable Rendered weeklyDigest) {}

    public Document buildPayload(long workspaceId, long accountId, UUID conversationId) {
        return new Document(digestOf(workspaceId, accountId, conversationId)
                .map(digest ->
                        facts.render(workspaceId, accountId, List.of(digest)).getFirst())
                .orElse(null));
    }

    /** The stored digest the account's thread replies to, if it is a thread on one. */
    private Optional<WeeklyDigestPayload> digestOf(long workspaceId, long accountId, UUID conversationId) {
        MentorConversation conversation = conversations.require(workspaceId, conversationId);
        UUID parentEntryId = conversation.getParentEntryId();
        if (parentEntryId == null || conversation.getAccountId() != accountId) {
            return Optional.empty();
        }
        return entries.find(workspaceId, parentEntryId)
                .filter(entry -> entry.getKind() == MentorEntry.Kind.DIGEST && !entry.isRedacted())
                .flatMap(entry -> WeeklyDigestPayload.fromJson(entry.getPayload(), objectMapper));
    }
}
