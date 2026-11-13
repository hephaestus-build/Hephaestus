package de.tum.cit.aet.hephaestus.integration.outline.manifest;

import de.tum.cit.aet.hephaestus.integration.core.spi.Capability;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationManifest;
import de.tum.cit.aet.hephaestus.integration.docs.item.DocumentEvents;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-kind capability declaration for Outline. */
@Component
public class OutlineManifest implements IntegrationManifest {

    private final boolean outlineEnabled;

    public OutlineManifest(@Value("${hephaestus.integration.outline.enabled:false}") boolean outlineEnabled) {
        this.outlineEnabled = outlineEnabled;
    }

    @Override
    public boolean enabled() {
        return outlineEnabled;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.OUTLINE;
    }

    @Override
    public String displayName() {
        return "Outline";
    }

    @Override
    public Set<Capability> declaredCapabilities() {
        // Outline change notifications ride the unified /webhooks/{kind} provider inbox (ADR 0023 §3):
        // a signature-verified delivery is published to the durable `outline` stream and consumed to
        // trigger a whole-workspace reconcile.
        return Set.of(Capability.WEBHOOK_INGEST);
    }

    /**
     * No delivery lanes: Outline's API could take a document comment, but no {@code SummaryChannel}
     * implements it, so feedback lands on the Hephaestus surface instead.
     */
    @Override
    public ReviewContribution reviewContribution() {
        return new ReviewContribution(
                Set.of(DocumentEvents.DOCUMENT),
                Map.of(
                        DocumentEvents.DOCUMENT,
                        Set.of(DocumentEvents.PUBLISHED, DocumentEvents.UPDATED, DocumentEvents.ARCHIVED)),
                Map.of());
    }
}
