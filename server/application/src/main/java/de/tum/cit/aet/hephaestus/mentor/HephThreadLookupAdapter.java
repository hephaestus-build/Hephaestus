package de.tum.cit.aet.hephaestus.mentor;

import de.tum.cit.aet.hephaestus.practices.spi.HephThreadLookup;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Where Heph messages sit, for links from outside the conversation. */
@Component
@RequiredArgsConstructor
class HephThreadLookupAdapter implements HephThreadLookup {

    private final MentorEntryRepository entries;

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Place> places(long workspaceId, Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) {
            return Map.of();
        }
        return entries.findUnredactedPlaces(workspaceId, messageIds).stream()
                .collect(Collectors.toMap(
                        MentorEntryRepository.EntryPlace::getEntryId, place -> new Place(place.getParentEntryId())));
    }
}
