package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.core.release.ImageReference;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!specs & !cds-training")
@ConditionalOnProperty(prefix = "hephaestus.agent.image", name = "require-digest", havingValue = "true")
public class AgentImagePinGuard {

    public AgentImagePinGuard(AgentImageProperties properties) {
        // Nullable: nothing supplies a reference when neither the release pin nor the derivation
        // resolves one, and bean ordering does not guarantee AgentImageReferenceGuard runs first.
        String reference = properties.reference();
        if (reference == null || !ImageReference.isDigestPinned(reference)) {
            throw new IllegalStateException(
                    "hephaestus.agent.image.reference must be digest-pinned (ending in @sha256:<64 lowercase hex>) "
                            + "when hephaestus.agent.image.require-digest=true. Got: "
                            + Objects.requireNonNullElse(reference, "<not set>")
                            + ". See docs/admin/release-image-lock.md.");
        }
    }
}
