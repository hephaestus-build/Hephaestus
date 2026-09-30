package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactCatalog;
import de.tum.cit.aet.hephaestus.integration.core.spi.ArtifactDescriptor;
import de.tum.cit.aet.hephaestus.integration.core.spi.Signal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * Which artifacts a practice can be written against, and which signals it may start a review on —
 * derived entirely from the registered {@link ArtifactDescriptor}s, so a new domain becomes bindable
 * without an edit in this package.
 */
@Component
public class PracticeSignalOptions {

    private final ArtifactCatalog artifacts;

    public PracticeSignalOptions(ArtifactCatalog artifacts) {
        this.artifacts = artifacts;
    }

    /** The kinds an author may write a practice against: the reviewable ones, in a stable order. */
    public List<ArtifactKind> authorableKinds() {
        return artifacts.all().stream()
                .filter(ArtifactDescriptor::reviewable)
                .map(ArtifactDescriptor::kind)
                .sorted(Comparator.comparing(ArtifactKind::value))
                .toList();
    }

    /**
     * The occasions a practice on this kind may bind to, in the order its descriptor declares them.
     *
     * <p>A review somebody asks for by hand is not among them. It reviews every practice on the kind
     * whatever state the work is in, so binding to it decides nothing, while a practice holding only it
     * would look configured and never fire on its own.
     */
    public List<SignalOption> bindableOptionsFor(ArtifactKind kind) {
        return declaredOptions(kind)
                .filter(signal -> !signal.requestedByHand())
                .map(SignalOption::of)
                .toList();
    }

    /** The occasion a person raises by asking for a review of this kind, if the kind admits one. */
    public Optional<SignalOption> manualRequestOptionFor(ArtifactKind kind) {
        return declaredOptions(kind)
                .filter(Signal::requestedByHand)
                .map(SignalOption::of)
                .findFirst();
    }

    /** The signal a person's explicit "review this now" raises for this kind, if the kind admits one. */
    public Optional<SignalName> manualRequestSignalFor(ArtifactKind kind) {
        return manualRequestOptionFor(kind).map(SignalOption::signal);
    }

    /**
     * Whether this signal is the one a person raises by asking for a review of this kind of work.
     * Derived from the descriptor rather than the signal's spelling — the detection gate and the catalog
     * injector both rely on this agreeing with theirs.
     */
    public boolean isManualRequest(SignalName signal) {
        return manualRequestSignalFor(signal.artifactKind())
                .filter(signal::equals)
                .isPresent();
    }

    /** The signal's words for an author, as its kind's descriptor declares them. */
    public String displayNameOf(SignalName signal) {
        return artifacts.signalDisplayName(signal);
    }

    public Set<SignalName> eligibleFor(ArtifactKind kind) {
        return bindableOptionsFor(kind).stream().map(SignalOption::signal).collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The relations this kind of work can identify a person in — the roles an occasion on it may name as
     * its subject. Read off the descriptor, which is the ceiling; a kind whose descriptor names no role
     * has nobody to address what a review of it finds, so it can carry no occasion either.
     */
    public Set<ActorRole> rolesFor(ArtifactKind kind) {
        return artifacts.descriptorFor(kind).map(ArtifactDescriptor::roles).orElseGet(Set::of);
    }

    /**
     * Whether any ingested event is declared to raise this signal. False is a meaningful answer, not a
     * gap: a signal raised entirely inside Hephaestus (a settled conversation, a hand-requested review)
     * has no ingestion event to declare.
     */
    public boolean producedByIngestion(SignalName signal) {
        return artifacts
                .descriptorFor(signal.artifactKind())
                .flatMap(descriptor -> descriptor.signal(signal))
                .filter(declared -> !declared.producedBy().isEmpty())
                .isPresent();
    }

    private Stream<Signal> declaredOptions(ArtifactKind kind) {
        return artifacts.descriptorFor(kind).map(ArtifactDescriptor::signals).orElseGet(List::of).stream();
    }

    /** One authoring choice: the signal stored, the label shown, and whether a new practice starts on it. */
    public record SignalOption(SignalName signal, String displayName, boolean recommended) {
        static SignalOption of(Signal signal) {
            return new SignalOption(signal.name(), signal.displayName(), signal.recommendedForAuthoring());
        }
    }
}
