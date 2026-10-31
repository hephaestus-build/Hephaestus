package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.framework.ItemDescriptorRegistry;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PracticeItemOptions {
    private final ItemDescriptorRegistry items;

    public ItemDescriptor require(ItemKind kind) {
        return items.descriptorFor(kind)
                .orElseThrow(() -> new IllegalArgumentException("Choose a supported item kind."));
    }

    /** How an occasion of {@code name} reaches its readiness edge; a name the kind does not declare is immediate. */
    public OccasionKind occasion(ItemKind kind, ItemEventName name) {
        return require(kind).events().stream()
                .filter(event -> event.name().equals(name))
                .map(ItemEventDefinition::occasion)
                .findFirst()
                .orElse(OccasionKind.IMMEDIATE);
    }

    public Collection<ItemDescriptor> all() {
        return items.all();
    }
}
