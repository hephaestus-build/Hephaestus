package de.tum.cit.aet.hephaestus.conversation;

import de.tum.cit.aet.hephaestus.practices.spi.ConversationThreadLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Where Hephaestus messages sit, for links from outside the conversation. */
@Component
@RequiredArgsConstructor
class ConversationThreadLookupAdapter implements ConversationThreadLookup {

    private final ConversationEntryRepository entries;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Place> places(long workspaceId, Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return entries.findUnredactedPlaces(workspaceId, messageIds).stream()
                .collect(Collectors.toMap(
                        ConversationEntryRepository.EntryPlace::getEntryId,
                        place -> new Place(place.getParentEntryId())));
    }
}
