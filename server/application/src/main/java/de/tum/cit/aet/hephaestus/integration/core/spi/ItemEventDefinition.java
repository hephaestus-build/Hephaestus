package de.tum.cit.aet.hephaestus.integration.core.spi;

import java.util.Set;

public record ItemEventDefinition(
        ItemEventName name,
        String displayName,
        Set<EventTypeKey> producedBy,
        ItemVersionScheme versionScheme,
        boolean recommendedForAuthoring,
        boolean bindableForAuthoring,
        OccasionKind occasion) {
    public ItemEventDefinition {
        producedBy = Set.copyOf(producedBy);
    }
}
