package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public final class PracticeDefinitionValidator {

    private static final Pattern DETECTOR_VOCAB =
            Pattern.compile("\\b(?:PRESENT|ABSENT|GOOD|BAD|ASSESSED|NOT_APPLICABLE|UNDETERMINED)\\b");

    private final ArtifactSourceCatalogRegistry sourceCatalogs;
    private final PracticeSignalOptions signalOptions;

    public PracticeDefinitionValidator(
            ArtifactSourceCatalogRegistry sourceCatalogs, PracticeSignalOptions signalOptions) {
        this.sourceCatalogs = sourceCatalogs;
        this.signalOptions = signalOptions;
    }

    public void validate(PracticeDefinition definition) {
        boolean canRunAutomatedReview =
                definition.automatedReviewPolicy().automatedReview().canAttemptAutomatedReview();
        validateBindings(definition.bindings());
        if (!canRunAutomatedReview && definition.precomputeScript() != null) {
            throw new IllegalArgumentException("A practice Hephaestus cannot review cannot define a precompute script");
        }
        rejectDetectorVocabulary("Why it matters", definition.whyItMatters());
        rejectDetectorVocabulary("What good looks like", definition.whatGoodLooksLike());
        validateEvidence(definition.artifactKind(), definition);
    }

    /**
     * A practice is reviewed on one occasion, and may only bind to signals a registered domain declares.
     *
     * <p>The single-occasion rule is enforced here rather than in {@link PracticeDefinition} so that a
     * stored definition stays readable whatever it holds: this refuses new writes without making an
     * existing row unloadable, and the persisted shape stays a list so widening the rule again would be
     * a change to this method rather than a data migration.
     *
     * <p>The signal check is the boot cross-check that keeps a derived artifact kind honest, since a
     * misspelled signal would otherwise invent a kind nothing can raise and the practice would sit in
     * the catalog looking configured and never fire. A human-only practice is checked the same way: it
     * must still name an occasion, which is where its artifact kind comes from.
     */
    private void validateBindings(List<PracticeBinding> bindings) {
        // Ahead of the kind check, so two occasions on two kinds of work are answered with the thing to
        // do about them rather than with the kind mismatch that is a symptom of the same mistake.
        if (bindings.size() > 1) {
            throw new IllegalArgumentException(
                    "A practice is reviewed on one occasion. To read different evidence at a different moment, "
                            + "split this into two practices.");
        }
        ArtifactKind artifactKind = PracticeBinding.artifactKindOf(bindings);
        Set<SignalName> declared = signalOptions.eligibleFor(artifactKind);
        if (declared.isEmpty()) {
            throw new IllegalArgumentException("No registered domain declares signals for " + artifactKind);
        }
        Set<ActorRole> roles = signalOptions.rolesFor(artifactKind);
        for (PracticeBinding binding : bindings) {
            // An occasion may only be about a relation this kind of work can actually identify a person
            // in. Attributing a result to a role the artifact cannot resolve leaves an observation about
            // nobody — or, worse, one filed against whichever person the kind happens to name.
            if (!roles.contains(binding.subject())) {
                throw new IllegalArgumentException("This work type cannot identify a " + binding.subject()
                        + ", so a review of it cannot be about one");
            }
            for (SignalName signal : binding.signals()) {
                if (signalOptions.isManualRequest(signal)) {
                    throw new IllegalArgumentException(
                            "A review somebody asks for by hand already reviews every practice on this work type, "
                                    + "whatever state the work is in, so it is not an occasion to choose: remove "
                                    + signal);
                }
                if (!declared.contains(signal)) {
                    throw new IllegalArgumentException("Choose signals declared for the selected work type");
                }
            }
        }
    }

    private static void rejectDetectorVocabulary(String field, @Nullable String value) {
        if (value != null && DETECTOR_VOCAB.matcher(value).find()) {
            throw new IllegalArgumentException(
                    field + " is guidance for people and must not use detector result labels");
        }
    }

    /** Applicability predicates must use evidence capable of settling the predicate. */
    private void validateSubject(PracticeDefinition definition, Set<SourceKind> applicable) {
        var version = definition.automatedReviewPolicy().sourceContractVersion();
        for (PracticeBinding binding : definition.bindings()) {
            PracticeSubject subject = binding.appliesWhen();
            if (subject == null) {
                continue;
            }
            if (!definition.automatedReviewPolicy().automatedReview().canAttemptAutomatedReview()) {
                throw new IllegalArgumentException(
                        "A practice Hephaestus does not review cannot declare what it applies to; nothing would read it");
            }
            for (PracticeSubjectClause clause : subject.anyOf()) {
                SourceKind readFrom = clause.readsFrom();
                if (!applicable.contains(readFrom)) {
                    throw new IllegalArgumentException("This work type has no " + readFrom
                            + ", so \""
                            + clause.describe()
                            + "\" could never be decided about it");
                }
                if (!sourceCatalogs
                        .requireSource(version, readFrom)
                        .completenessPolicy()
                        .supportsComplete()) {
                    throw new IllegalArgumentException("Evidence source " + readFrom
                            + " can never be captured completely, so its silence can never establish that "
                            + "this practice does not apply");
                }
            }
        }
    }

    /**
     * A practice may only read evidence that could exist for the kind of thing it reviews. What each
     * source demands of its capture is the source contract's business, not checked here.
     */
    private void validateEvidence(ArtifactKind artifactKind, PracticeDefinition definition) {
        var version = definition.automatedReviewPolicy().sourceContractVersion();
        Set<SourceKind> applicable = sourceCatalogs.requireSourcesFor(version, artifactKind.value());
        validateSubject(definition, applicable);
        for (PracticeBinding binding : definition.bindings()) {
            for (PracticeEvidenceRequirement need : binding.needs()) {
                var contract = sourceCatalogs.requireSource(version, need.sourceKind());
                if (!applicable.contains(need.sourceKind())) {
                    throw new IllegalArgumentException("Evidence source is not available for the selected work type");
                }
                // An exhaustive claim over a source that can never report a complete capture refuses
                // every review it triggers. Caught at authoring time, because at review time
                // "permanently refusing" and "nobody has done this yet" produce the same report.
                if (need.stance().demandsCompleteCapture()
                        && !contract.completenessPolicy().supportsComplete()) {
                    throw new IllegalArgumentException(
                            "Evidence source " + need.sourceKind()
                                    + " can never be captured completely, so no claim about what is absent from it can rest on it");
                }
            }
        }
    }
}
