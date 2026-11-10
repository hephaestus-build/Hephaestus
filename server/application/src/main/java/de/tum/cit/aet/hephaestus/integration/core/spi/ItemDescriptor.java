package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import java.util.List;
import java.util.Set;

public interface ItemDescriptor {
    ItemKind kind();

    String displayName();

    List<ItemEventDefinition> events();

    Set<AboutRole> roles();

    Set<CopyCapability> copyCapabilities();

    boolean reviewable();

    Set<ItemKind> memberKinds();

    String recordSchemaResource();

    /**
     * The source whose capture holds this kind's record. Every practice's inputs carry the record, so every capture of
     * an item reads this source, whatever its practices declare.
     */
    SourceKind recordSource();

    ReviewCapabilities reviewCapabilities();
}
