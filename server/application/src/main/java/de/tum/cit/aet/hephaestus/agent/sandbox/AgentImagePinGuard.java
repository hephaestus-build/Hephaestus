package de.tum.cit.aet.hephaestus.agent.sandbox;

import de.tum.cit.aet.hephaestus.agent.runtime.AgentImageProperties;
import de.tum.cit.aet.hephaestus.core.release.ImageReference;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!specs & !cds-training")
@ConditionalOnProperty(prefix = "hephaestus.agent.image", name = "require-digest", havingValue = "true")
public class AgentImagePinGuard {

    public AgentImagePinGuard(AgentImageProperties properties) {
        requireDigest(properties.reference(), "hephaestus.agent.image.reference");
    }

    public static void requireDigest(@Nullable String reference, String setting) {
        if (reference == null || !ImageReference.isDigestPinned(reference)) {
            throw new IllegalStateException(setting + " must be digest-pinned (ending in @sha256:<64 lowercase hex>) "
                    + "when hephaestus.agent.image.require-digest=true. Got: "
                    + Objects.requireNonNullElse(reference, "<not set>")
                    + ". See docs/admin/release-image-lock.md.");
        }
    }
}
