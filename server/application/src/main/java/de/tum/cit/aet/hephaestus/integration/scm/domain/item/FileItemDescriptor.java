package de.tum.cit.aet.hephaestus.integration.scm.domain.item;

import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.AboutRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.CopyCapability;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemEventDefinition;
import de.tum.cit.aet.hephaestus.integration.core.spi.ItemKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.capture.RepositoryTreeContentSource;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class FileItemDescriptor implements ItemDescriptor {
    @Override
    public ItemKind kind() {
        return ScmItemEvents.FILE;
    }

    @Override
    public String displayName() {
        return "File";
    }

    @Override
    public List<ItemEventDefinition> events() {
        return List.of();
    }

    @Override
    public Set<AboutRole> roles() {
        return Set.of();
    }

    @Override
    public Set<CopyCapability> copyCapabilities() {
        return Set.of();
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
        return "contracts/items/scm/file.schema.json";
    }

    @Override
    public SourceKind recordSource() {
        return RepositoryTreeContentSource.KIND;
    }

    @Override
    public ReviewCapabilities reviewCapabilities() {
        return new ReviewCapabilities(
                List.of(new ReviewLimitation(
                        "RUNTIME_BEHAVIOR_NOT_OBSERVED",
                        "Repository evidence does not establish behavior in a deployed runtime.")),
                List.of(),
                Set.of(),
                Set.of(),
                Set.of(ScmItemEvents.FILE));
    }
}
