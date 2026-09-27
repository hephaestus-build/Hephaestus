package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.ContentSource;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest.MentorChatRequest;
import de.tum.cit.aet.hephaestus.core.exception.EntityNotFoundException;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.practices.ReviewClaimCurrentness;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackObservationRepository.FeedbackObservationVisibility;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackRepository.RecipientFeedbackRow;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackWithdrawalRepository;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationVisibilityPolicy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
@RequiredArgsConstructor
public class DeliveredFeedbackContentSource implements ContentSource {

    public static final String OUTPUT_KEY = OUTPUT_PREFIX + "delivered_feedback.json";

    private static final int LOOKBACK_DAYS = 90;
    private static final int MAX_FEEDBACK = 30;
    private static final int MAX_PAGES = 5;

    private final UserRepository userRepository;
    private final FeedbackRepository feedbackRepository;
    private final FeedbackObservationRepository feedbackObservationRepository;
    private final FeedbackWithdrawalRepository withdrawalRepository;
    private final ConversationConsentGate conversationConsentGate;
    private final ObservationVisibilityPolicy visibilityPolicy;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(ContextRequest request) {
        return request instanceof MentorChatRequest;
    }

    @Override
    public boolean required() {
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public void contribute(ContextRequest request, Map<String, byte[]> files) {
        MentorChatRequest req = (MentorChatRequest) request;
        ObjectNode payload = buildPayload(req.workspaceId(), req.developerId());
        try {
            files.put(OUTPUT_KEY, objectMapper.writeValueAsBytes(payload));
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize delivered feedback context", e);
        }
    }

    public ObjectNode buildPayload(Long workspaceId, Long developerId) {
        User user = userRepository
                .findById(developerId)
                .orElseThrow(() -> new EntityNotFoundException("User", developerId.toString()));
        Instant preparedAt = Instant.now();
        Instant since = preparedAt.minus(LOOKBACK_DAYS, ChronoUnit.DAYS);

        // A recent sample, not a history: rows this conversation may not use are skipped rather than counted
        // against the cap, but a turn reads at most MAX_PAGES pages however many of them it skips.
        List<Usable> sample = new ArrayList<>();
        @Nullable RecipientFeedbackRow last = null;
        for (int page = 0; page < MAX_PAGES && sample.size() < MAX_FEEDBACK; page++) {
            List<RecipientFeedbackRow> rows = feedbackRepository.findRecentReceivableForRecipient(
                    workspaceId,
                    developerId,
                    since,
                    last == null ? null : last.getCreatedAt(),
                    last == null ? null : last.getId(),
                    PageRequest.of(0, MAX_FEEDBACK));
            sample.addAll(authorized(workspaceId, rows));
            if (rows.size() < MAX_FEEDBACK) {
                break;
            }
            last = rows.getLast();
        }
        sample = sample.subList(0, Math.min(sample.size(), MAX_FEEDBACK));

        ObjectNode root = objectMapper.createObjectNode();
        // Untrusted-content quarantine: only when a Slack-derived (attacker-controllable) row survives the gate does
        // this file carry the envelope — a PR/issue-only payload stays byte-identical (no _meta).
        if (sample.stream()
                .anyMatch(usable ->
                        ArtifactKinds.CONVERSATION_THREAD.equals(usable.row().getArtifactKind()))) {
            conversationConsentGate.writeUntrustedEnvelope(root);
        }
        root.putObject("user").put("login", user.getLogin()).put("name", user.getName());
        root.set("coverage", objectMapper.valueToTree(Coverage.of(preparedAt)));

        ArrayNode delivered = root.putArray("deliveredFeedback");
        ArrayNode states = root.putArray("feedbackStates");
        for (Usable usable : sample) {
            RecipientFeedbackRow row = usable.row();
            // A withdrawn card keeps its record but not its words: an admin took them back as wrong.
            String body = usable.withdrawn() ? null : deliveredText(row);
            if (body != null) {
                describe(delivered.addObject(), row).put("body", body);
            }
            ObjectNode state = describe(states.addObject(), row)
                    .put("status", status(row))
                    .put("evidenceCurrentness", usable.evidence().name())
                    .put("createdAt", row.getCreatedAt().toString());
            if (usable.withdrawn()) {
                state.put("withdrawn", true);
            }
        }
        return root;
    }

    /**
     * The bounds of {@code feedbackStates}. Apart from {@code preparedAt} it is constant, so it reveals nothing about
     * records outside the scope, including whether any exist.
     */
    record Coverage(
            Scope scope, Instant preparedAt, int lookbackDays, int maxEntries, List<OutsideScope> outsideScope) {

        static Coverage of(Instant preparedAt) {
            return new Coverage(
                    Scope.CONVERSATION_AUTHORIZED_RECIPIENT_RECORDS,
                    preparedAt,
                    LOOKBACK_DAYS,
                    MAX_FEEDBACK,
                    List.of(OutsideScope.values()));
        }

        enum Scope {
            CONVERSATION_AUTHORIZED_RECIPIENT_RECORDS,
        }

        enum OutsideScope {
            PROPOSALS,
            REVIEWER_DECISIONS,
            WITHHELD,
            REPLACED,
            EVIDENCE_NOT_USABLE_IN_CONVERSATION,
            CONVERSATION_CONSENT_NOT_ACTIVE,
        }
    }

    /**
     * A row this conversation may use, whether every review behind it is still current, and whether a workspace
     * admin withdrew it from the practice page.
     */
    private record Usable(RecipientFeedbackRow row, ReviewClaimCurrentness evidence, boolean withdrawn) {}

    /**
     * The rows whose every bound observation may still be shown to the developer, as on their practice page, and
     * whose conversation, if any, still has consent; a row bound to no observation is withheld. Evidence the work
     * or the practice has since moved past stays: a later change does not alter what became of the feedback.
     */
    private List<Usable> authorized(Long workspaceId, List<RecipientFeedbackRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<FeedbackObservationVisibility> bindings = feedbackObservationRepository.findForVisibility(
                workspaceId, rows.stream().map(RecipientFeedbackRow::getId).toList());
        Set<UUID> visible = visibilityPolicy.permitsShown(
                workspaceId,
                bindings.stream()
                        .map(FeedbackObservationVisibility::getObservation)
                        .toList(),
                SourceUsePurpose.CONVERSATIONAL_MENTORING);
        Map<UUID, ReviewClaimCurrentness> shown = FeedbackObservationVisibility.shown(bindings, visible);
        Set<Long> activeThreadIds = conversationConsentGate.activeThreadIds(
                workspaceId,
                rows.stream()
                        .filter(row -> ArtifactKinds.CONVERSATION_THREAD.equals(row.getArtifactKind()))
                        .map(RecipientFeedbackRow::getArtifactId)
                        .filter(Objects::nonNull)
                        .toList());
        Set<UUID> withdrawn = withdrawalRepository.withdrawnAmong(workspaceId, shown.keySet());
        return rows.stream()
                .filter(row -> shown.containsKey(row.getId()))
                .filter(row -> !ArtifactKinds.CONVERSATION_THREAD.equals(row.getArtifactKind())
                        || (row.getArtifactId() != null && activeThreadIds.contains(row.getArtifactId())))
                .map(row -> new Usable(
                        row, Objects.requireNonNull(shown.get(row.getId())), withdrawn.contains(row.getId())))
                .toList();
    }

    /**
     * The rendered words a delivered unit put in front of the developer, or {@code null}. A conversational unit's
     * body is the composer's private notes to the mentor, whatever its format, never the words the mentor spoke —
     * those live in the chat transcript — so no chat body is staged as something the developer was told.
     */
    private static @Nullable String deliveredText(RecipientFeedbackRow row) {
        String body = row.getBody();
        return row.getChannel() == FeedbackChannel.IN_CHAT || body == null || body.isBlank() ? null : body;
    }

    private static ObjectNode describe(ObjectNode node, RecipientFeedbackRow row) {
        node.put("feedbackId", row.getId().toString());
        node.put("surface", row.getChannel().name());
        if (row.getArtifactKind() != null) {
            node.put("artifactKind", row.getArtifactKind().value());
        }
        if (row.getArtifactId() != null) {
            node.put("artifactId", row.getArtifactId());
        }
        if (row.getDeliveredAt() != null) {
            node.put("deliveredAt", row.getDeliveredAt().toString());
        }
        return node;
    }

    /** The recipient-facing name of what {@link FeedbackRepository#findRecentReceivableForRecipient} returned. */
    private static String status(RecipientFeedbackRow row) {
        return switch (row.getDeliveryState()) {
            case DELIVERED -> "DELIVERED";
            case PARTIALLY_DELIVERED -> "PARTIALLY_DELIVERED";
            case PARTIALLY_FAILED -> "PARTIALLY_FAILED";
            case FAILED -> "DELIVERY_FAILED";
            case PREPARED -> "PREPARED";
            default -> throw new IllegalStateException("Not a receivable state: " + row.getDeliveryState());
        };
    }
}
