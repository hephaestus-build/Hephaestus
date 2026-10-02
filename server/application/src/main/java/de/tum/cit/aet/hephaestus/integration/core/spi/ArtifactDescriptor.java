package de.tum.cit.aet.hephaestus.integration.core.spi;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * What one family of reviewable artifacts <em>is</em> — one bean per kind, owned by the module that owns
 * the domain rather than by any vendor, so a practice can name {@code scm.pull_request.merged} once and
 * have it hold for every provider.
 *
 * <p>The descriptor is the <em>ceiling</em>: every signal, role, and lane the kind can ever have. What a
 * particular vendor actually delivers of that ceiling is declared separately in
 * {@link IntegrationManifest.ReviewContribution}.
 */
public interface ArtifactDescriptor {
    /** Unique across the application context. */
    ArtifactKind kind();

    String displayName();

    /** Every signal this domain can raise, whatever the vendor; must be non-empty. */
    List<Signal> signals();

    /**
     * The relations this artifact can identify a person in; a kind that names none has nowhere to send
     * what it finds.
     */
    Set<ActorRole> roles();

    /**
     * The lanes feedback about this artifact can land in. Empty is legal: an artifact can supply evidence
     * without ever being a subject.
     */
    Set<FeedbackLane> lanes();

    /**
     * Whether a review can be run <em>about</em> this artifact, as opposed to it only supplying evidence
     * for another. A reviewable kind must have a {@link ReviewContextBuilder}, since a review with no way
     * to assemble its subject can only fail.
     */
    boolean reviewable();

    /** A reviewable kind must name at least one limitation; startup validates the contract. */
    default ReviewCapabilities reviewCapabilities() {
        return ReviewCapabilities.NONE;
    }

    default Optional<Signal> signal(SignalName name) {
        return signals().stream().filter(signal -> signal.name().equals(name)).findFirst();
    }
}
