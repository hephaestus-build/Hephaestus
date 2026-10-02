package de.tum.cit.aet.hephaestus.integration.outline.domain.signal;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.EventTypeKey;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackLane;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewCapabilities;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewLimitation;
import de.tum.cit.aet.hephaestus.integration.core.spi.ReviewStateDimension;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import de.tum.cit.aet.hephaestus.integration.outline.webhook.OutlineWebhookMessageHandler;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * What can happen to a written document, and which of those a practice may be reviewed on.
 *
 * <p>Registered unconditionally, not gated on {@code hephaestus.integration.outline.enabled}: what a
 * document is, is a fact about the domain, not the deployment — gating it on the flag would make the
 * vocabulary itself configuration, and a practice bound to a document would stop parsing when an
 * operator turned the integration off.
 */
@Component
public class DocumentArtifactDescriptor implements ArtifactDescriptor {

    /**
     * Every Outline event collapses onto this one key, so it — not the Outline event name — is the
     * provenance unit the framework checks a handler for. Which event raises which signal is stated only
     * in {@link DocsSignals#forOutlineEvent(String)}, to avoid a second mapping here that could drift.
     */
    private static final EventTypeKey OUTLINE_DOCUMENT =
            new EventTypeKey(IntegrationKind.OUTLINE, OutlineWebhookMessageHandler.EVENT_TYPE);

    private static final List<Signal> SIGNALS = List.of(
            declare(DocsSignals.DOCUMENT_PUBLISHED, "Published", true),
            declare(DocsSignals.DOCUMENT_UPDATED, "Content changed", true),
            declare(DocsSignals.DOCUMENT_ARCHIVED, "Archived", false));

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
                        List.of(
                                new ReviewStateDimension.Value("ACTIVE", "Active"),
                                new ReviewStateDimension.Value("ARCHIVED", "Archived")),
                        Set.of("ACTIVE", "ARCHIVED"))),
                Set.of(),
                Set.of());
    }

    @Override
    public ArtifactKind kind() {
        return DocsSignals.DOCUMENT;
    }

    @Override
    public String displayName() {
        return "Document";
    }

    @Override
    public List<Signal> signals() {
        return SIGNALS;
    }

    /**
     * Outline's revision history names everyone who has edited a document, but "has edited" is not a
     * role a review can attribute an observation to: deriving one would put a co-editor's name on a
     * judgement about somebody else's writing.
     */
    @Override
    public Set<ActorRole> roles() {
        return Set.of(ActorRole.AUTHOR);
    }

    @Override
    public Set<FeedbackLane> lanes() {
        return Set.of(FeedbackLane.IN_APP);
    }

    @Override
    public boolean reviewable() {
        return true;
    }

    private static Signal declare(SignalName name, String displayName, boolean recommended) {
        return new Signal(name, displayName, Set.of(OUTLINE_DOCUMENT), DocsSignals.revisionScheme(name), recommended);
    }
}
