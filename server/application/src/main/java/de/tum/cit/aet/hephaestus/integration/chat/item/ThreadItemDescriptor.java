package de.tum.cit.aet.hephaestus.integration.chat.item;

import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersionScheme;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Thread metadata remains available when no chat provider is connected. */
@Component
public class ThreadItemDescriptor implements ItemDescriptor {
    @Override
    public ItemKind kind() {
        return ThreadEvents.THREAD;
    }

    @Override
    public String displayName() {
        return "Conversation thread";
    }

    @Override
    public List<ItemEventDefinition> events() {
        return List.of(new ItemEventDefinition(
                ThreadEvents.SETTLED,
                "Discussion settled",
                Set.of(),
                ItemVersionScheme.CONTENT_DIGEST,
                true,
                true,
                OccasionKind.CONTINUING));
    }

    @Override
    public Set<AboutRole> roles() {
        return Set.of(AboutRole.AUTHOR);
    }

    @Override
    public Set<CopyCapability> copyCapabilities() {
        return Set.of(CopyCapability.CONVERSATION, CopyCapability.PRACTICE_PAGE);
    }

    @Override
    public boolean reviewable() {
        return true;
    }

    @Override
    public Set<ItemKind> memberKinds() {
        return Set.of();
    }

    @Override
    public String recordSchemaResource() {
        return "contracts/items/chat.thread.schema.json";
    }

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "PRIVATE_CONTEXT_NOT_OBSERVED",
                        "The captured thread does not include decisions or context shared outside the conversation.")),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
