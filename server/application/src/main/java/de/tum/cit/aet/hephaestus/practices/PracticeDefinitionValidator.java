package de.tum.cit.aet.hephaestus.practices;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
public final class PracticeDefinitionValidator {

    private static final Pattern DETECTOR_VOCAB =
            Pattern.compile("\\b(?:PRESENT|ABSENT|GOOD|BAD|POSITIVE|NEGATIVE|ASSESSED|NOT_APPLICABLE|UNDETERMINED)\\b");

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
            throw new IllegalArgumentException("The chosen moments do not belong to a kind of work Hephaestus reviews. "
                    + "Choose a kind of work, then the moments it offers.");
        }
        Set<ActorRole> roles = signalOptions.rolesFor(artifactKind);
        for (PracticeBinding binding : bindings) {
            // An occasion may only be about a relation this kind of work can actually identify a person
            // in. Attributing a result to a role the artifact cannot resolve leaves an observation about
            // nobody — or, worse, one filed against whichever person the kind happens to name.
            if (!roles.contains(binding.subject())) {
                throw new IllegalArgumentException("This kind of work does not record “" + roleLabel(binding.subject())
                        + "”, so a review of it cannot be about them. Choose from the people listed under “Person "
                        + "this practice judges”.");
            }
            for (SignalName signal : binding.signals()) {
                if (signalOptions.isManualRequest(signal)) {
                    throw new IllegalArgumentException("Remove “" + signalOptions.displayNameOf(signal)
                            + "”. A review somebody asks for by hand already reviews every practice on this work "
                            + "type, whatever state the work is in, so it is not a moment to choose.");
                }
                if (!declared.contains(signal)) {
                    // Every declared signal but the hand-asked one is bindable, so this one is undeclared and
                    // has no words to name it by.
                    throw new IllegalArgumentException("One of the chosen moments is not one this kind of work offers. "
                            + "Choose from the moments listed for it.");
                }
            }
        }
    }

    /**
     * A source's display name, or empty for one the catalogue does not declare: that is the author's input,
     * not a stale row, so it is answered in the message rather than logged.
     */
    private Optional<String> named(SourceKind source) {
        return sourceCatalogs.current().source(source).map(ArtifactSourceContract::displayName);
    }

    /**
     * A source the catalogue does not declare has no label, so the id the author gave stands alone, and the
     * message says where the sources this kind of work has are listed.
     */
    private static String unknownSource(SourceKind source) {
        return source.value() + " is not an evidence source Hephaestus knows. Choose from the sources listed under "
                + "“Reads” in “When this practice is reviewed”.";
    }

    /** The editor's label for each relation, as its “Person this practice judges” list shows it. */
    private static String roleLabel(ActorRole role) {
        return switch (role) {
            case AUTHOR -> "Author";
            case ASSIGNEE -> "Assignee";
            case REVIEWER -> "Reviewer";
            case MERGER -> "Whoever merged it";
        };
    }

    private static void rejectDetectorVocabulary(String field, @Nullable String value) {
        if (value == null) {
            return;
        }
        Matcher label = DETECTOR_VOCAB.matcher(value);
        if (label.find()) {
            throw new IllegalArgumentException(field + " is guidance for people. Remove the review result label “"
                    + label.group() + "” and say it in plain words.");
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
                    // The gate is typed as JSON, so the source is named by its label and its id together.
                    throw new IllegalArgumentException(named(readFrom)
                            .map(source -> "This kind of work has no “" + source + "” (" + readFrom.value()
                                    + "), so a condition that reads it could never be decided. Choose a "
                                    + "condition this kind of work can answer, or remove it.")
                            .orElseGet(() -> unknownSource(readFrom)));
                }
                ArtifactSourceContract source = sourceCatalogs.requireSource(version, readFrom);
                if (!source.completenessPolicy().supportsComplete()) {
                    throw new IllegalArgumentException("“" + source.displayName() + "” (" + readFrom.value()
                            + ") can never be captured completely, so finding nothing in it cannot show that this "
                            + "practice does not apply. Choose a condition that reads other evidence.");
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
                if (!applicable.contains(need.sourceKind())) {
                    throw new IllegalArgumentException(named(need.sourceKind())
                            .map(source -> "“" + source
                                    + "” is not available for this kind of work. Turn it off, or choose "
                                    + "evidence this kind of work has.")
                            .orElseGet(() -> unknownSource(need.sourceKind())));
                }
                var contract = sourceCatalogs.requireSource(version, need.sourceKind());
                // An exhaustive claim over a source that can never report a complete capture refuses
                // every review it triggers. Caught at authoring time, because at review time
                // "permanently refusing" and "nobody has done this yet" produce the same report.
                if (need.stance().demandsCompleteCapture()
                        && !contract.completenessPolicy().supportsComplete()) {
                    throw new IllegalArgumentException("“" + contract.displayName()
                            + "” can never be captured completely, so a review cannot claim something is absent "
                            + "from it. Untick “May claim something is absent” for this source.");
                }
            }
        }
    }
}
