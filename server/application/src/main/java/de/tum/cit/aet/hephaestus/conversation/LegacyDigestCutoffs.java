package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.practices.weeklydigest.WeeklyDigestWindow;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.JsonNodeFactory;

/** Calendar cutoffs for queued stimuli written by a replica that only recorded the digest's week and zone. */
@Component
@RequiredArgsConstructor
public class LegacyDigestCutoffs {
    private final ConversationEntryRepository entries;

    /** Supplies UTC instants keyed by stimulus ID, so SQL never has to interpret a zone. */
    public String read(Collection<Long> workspaceIds, Collection<UUID> conversationIds, boolean allConversations) {
        if (workspaceIds.isEmpty() || (!allConversations && conversationIds.isEmpty())) return "{}";
        var cutoffs = JsonNodeFactory.instance.objectNode();
        for (var window : entries.findLegacyDigestWindows(workspaceIds, conversationIds, allConversations)) {
            try {
                var week = new WeeklyDigestWindow(LocalDate.parse(window.getWeekStart()), ZoneId.of(window.getZone()));
                cutoffs.put(window.getId().toString(), week.contactCutoff().toString());
            } catch (DateTimeException | IllegalArgumentException malformed) {
                // A malformed window has no cutoff to compare.
            }
        }
        return cutoffs.toString();
    }
}
