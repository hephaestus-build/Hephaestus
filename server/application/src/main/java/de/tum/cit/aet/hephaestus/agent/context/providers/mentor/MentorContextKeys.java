package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.providers.OutlineDocumentContentSource;
import java.util.Set;

/**
 * The context documents that {@code fetch_context} may read. The Heph host's list
 * ({@code heph-host-protocol.ts#FETCH_CONTEXT_ALLOWED}) is a hand-written mirror: a new provider is added to both.
 */
public final class MentorContextKeys {

    public static final Set<String> ALLOWED_OUTPUT_KEYS = Set.of(
            UserContentSource.OUTPUT_KEY,
            WorkspaceContentSource.OUTPUT_KEY,
            PracticeCatalogContentSource.OUTPUT_KEY,
            ObservationHistoryContentSource.OUTPUT_KEY,
            DeliveredFeedbackContentSource.OUTPUT_KEY,
            RecentAuthoredWorkContentSource.OUTPUT_KEY,
            ActivityContentSource.OUTPUT_KEY,
            PracticeProfileContentSource.OUTPUT_KEY,
            MergeReadinessContentSource.OUTPUT_KEY,
            ReviewAttemptsContentSource.OUTPUT_KEY,
            SlackConversationContentSource.OUTPUT_KEY,
            PreparedConversationFeedbackContentSource.OUTPUT_KEY,
            WeeklyDigestContentSource.OUTPUT_KEY,
            OutlineDocumentContentSource.OUTPUT_KEY);

    /**
     * The Heph host's {@code FETCH_CONTEXT_MAX_CHARS}: beyond it the host slices the JSON mid-document. Both count
     * UTF-16 units of compact JSON, and Jackson escapes no character more tersely than {@code JSON.stringify}, so a
     * document whose {@code writeValueAsString} fits reaches the model whole.
     */
    public static final int FETCH_CONTEXT_MAX_CHARS = 200_000;

    private MentorContextKeys() {}
}
