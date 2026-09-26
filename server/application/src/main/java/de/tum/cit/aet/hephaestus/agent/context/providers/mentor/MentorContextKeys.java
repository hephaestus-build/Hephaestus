package de.tum.cit.aet.hephaestus.agent.context.providers.mentor;

import de.tum.cit.aet.hephaestus.agent.context.providers.OutlineDocumentContentSource;
import java.util.Set;

/**
 * Single source of truth for mentor context output keys. The Java {@code fetch_context}
 * whitelist + the runner's JS whitelist ({@code pi-mentor-protocol.ts#FETCH_CONTEXT_ALLOWED})
 * must agree; the Java side derives from the provider constants so a new provider auto-extends
 * the set, but the JS side needs a manual mirror — keep the full workspace-relative keys identical.
 */
public final class MentorContextKeys {

    public static final Set<String> ALLOWED_OUTPUT_KEYS = Set.of(
            UserContentSource.OUTPUT_KEY,
            WorkspaceContentSource.OUTPUT_KEY,
            PracticeCatalogContentSource.OUTPUT_KEY,
            ObservationHistoryContentSource.OUTPUT_KEY,
            DeliveredFeedbackContentSource.OUTPUT_KEY,
            RecentAuthoredWorkContentSource.OUTPUT_KEY,
            MergeReadinessContentSource.OUTPUT_KEY,
            SlackConversationContentSource.OUTPUT_KEY,
            PreparedConversationFeedbackContentSource.OUTPUT_KEY,
            CurrentThreadHistoryContentSource.OUTPUT_KEY,
            OutlineDocumentContentSource.OUTPUT_KEY);

    private MentorContextKeys() {}
}
