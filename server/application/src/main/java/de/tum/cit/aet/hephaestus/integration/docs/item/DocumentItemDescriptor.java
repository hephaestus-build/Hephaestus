package de.tum.cit.aet.hephaestus.integration.docs.item;

import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemVersionScheme;
import de.tum.cit.aet.hephaestus.integration.core.spi.OccasionKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateValue;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Documents support local and conversational feedback without provider writes. */
@Component
public class DocumentItemDescriptor implements ItemDescriptor {
    @Override
    public ItemKind kind() {
        return DocumentEvents.DOCUMENT;
    }

    @Override
    public String displayName() {
        return "Document";
    }

    @Override
    public List<ItemEventDefinition> events() {
        return List.of(
                new ItemEventDefinition(
                        DocumentEvents.PUBLISHED,
                        "Published",
                        Set.of(new EventTypeKey(IntegrationKind.OUTLINE, "document")),
                        ItemVersionScheme.CONTENT_DIGEST,
                        true,
                        true,
                        OccasionKind.MILESTONE),
                new ItemEventDefinition(
                        DocumentEvents.UPDATED,
                        "Content changed",
                        Set.of(new EventTypeKey(IntegrationKind.OUTLINE, "document")),
                        ItemVersionScheme.CONTENT_DIGEST,
                        true,
                        true,
                        OccasionKind.CONTINUING),
                new ItemEventDefinition(
                        DocumentEvents.ARCHIVED,
                        "Archived",
                        Set.of(new EventTypeKey(IntegrationKind.OUTLINE, "document")),
                        ItemVersionScheme.TERMINAL_STATE,
                        false,
                        true,
                        OccasionKind.IMMEDIATE));
    }

    @Override
    public Set<AboutRole> roles() {
        return Set.of(AboutRole.AUTHOR);
    }

    @Override
    public Set<CopyCapability> copyCapabilities() {
        return Set.of(CopyCapability.PRACTICE_PAGE, CopyCapability.CONVERSATION);
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
        return "contracts/items/docs.document.schema.json";
    }

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(
                        new ReviewLimitation(
                                "DESCRIBED_SYSTEM_NOT_OBSERVED",
                                "Document evidence does not establish whether the system it describes behaves as written."),
                        new ReviewLimitation(
                                "READERSHIP_NOT_OBSERVED",
                                "The mirrored document does not establish whether anyone read it or relied on it.")),
                List.of(new ReviewStateDimension(
                        "state",
                        "State",
                        List.of(new ReviewStateValue("ACTIVE", "Active"), new ReviewStateValue("ARCHIVED", "Archived")),
                        Set.of("ACTIVE", "ARCHIVED"))),
                Set.of(),
                Set.of(),
                Set.of());
    }
}
