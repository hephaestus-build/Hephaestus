package de.tum.cit.aet.hephaestus.practices;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.core.signal.ArtifactKind;
import de.tum.cit.aet.hephaestus.integration.core.signal.SignalName;
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The occasion on which a practice is reviewed, and the evidence that review needs.
 *
 * <p>A practice declares exactly one of these — {@code PracticeDefinitionValidator} refuses a second.
 * One occasion may name several signals, which is how a practice judged all the way along a piece of
 * work is written; a practice that must read <em>different</em> evidence at a different moment is a
 * second practice, which is what the shipped catalogue does and what keeps one row, one autonomy setting
 * and one feedback history describing one habit.
 *
 * <p>The evidence therefore travels with the occasion rather than being named once and shared across
 * practices: shared evidence made adding a source to one practice a mutation invisible in that
 * practice's diff. The list shape survives the single-occasion rule so that relaxing the rule stays a
 * validator change rather than a data migration.
 *
 * @param signals  the signals that occasion this review; at least one, and all of one artifact kind
 * @param onDrafts whether a draft artifact occasions this review; defaults to false since most
 *                 practices judge work that has been handed over
 * @param subject whose conduct is assessed; defaults to {@link ActorRole#AUTHOR}
 */
@Schema(description = "An occasion that starts a review, and the evidence that review reads")
public record PracticeBinding(
        @NonNull
        @NotEmpty(message = "A binding must name at least one signal")
        @Schema(description = "Signals that occasion this review, e.g. scm.pull_request.merged")
        List<SignalName> signals,

        @NonNull
        @NotNull
        @Schema(description = "Sources a review occasioned this way reads, each with the stance it takes")
        List<@Valid PracticeEvidenceRequirement> needs,

        @Schema(description = "Whether an artifact still marked draft occasions this review; omit for false")
        boolean onDrafts,

        @Schema(description = "Whose conduct this review judges; omit for AUTHOR")
        ActorRole subject,

        @Valid
        @Schema(description = "What must be in the work for this practice to apply; omit to always apply")
        @Nullable
        PracticeSubject appliesWhen) {
    /** Applies contract defaults for optional binding fields. */
    @JsonCreator
    static PracticeBinding fromJson(
            @JsonProperty("signals") List<SignalName> signals,
            @JsonProperty("needs") List<PracticeEvidenceRequirement> needs,
            @JsonProperty("onDrafts") @Nullable Boolean onDrafts,
            @JsonProperty("subject") @Nullable ActorRole subject,
            @JsonProperty("appliesWhen") @Nullable PracticeSubject appliesWhen) {
        return new PracticeBinding(
                signals,
                needs,
                Boolean.TRUE.equals(onDrafts),
                subject == null ? ActorRole.AUTHOR : subject,
                appliesWhen);
    }

    public PracticeBinding {
        subject = subject == null ? ActorRole.AUTHOR : subject;
        // Sorted and de-duplicated: both lists are digested into the review-rule fingerprint, so the
        // same binding written in a different order must not read as a second rule.
        signals = List.copyOf(new LinkedHashSet<>(Objects.requireNonNull(signals, "signals").stream()
                .sorted(Comparator.comparing(SignalName::value))
                .toList()));
        if (signals.isEmpty()) {
            throw new IllegalArgumentException("A binding must name at least one signal");
        }
        ArtifactKind kind = signals.getFirst().artifactKind();
        for (SignalName signal : signals) {
            if (!kind.equals(signal.artifactKind())) {
                throw new IllegalArgumentException(
                        "One binding cannot mix artifact kinds: " + kind + " and " + signal.artifactKind());
            }
        }
        needs = Objects.requireNonNull(needs, "needs").stream()
                .sorted(Comparator.comparing(need -> need.sourceKind().value()))
                .toList();
        Set<String> seen = new HashSet<>();
        for (PracticeEvidenceRequirement need : needs) {
            if (!seen.add(need.sourceKind().value())) {
                throw new IllegalArgumentException("needs contains duplicate source " + need.sourceKind());
            }
        }
    }

    public PracticeBinding(List<SignalName> signals, List<PracticeEvidenceRequirement> needs, boolean onDrafts) {
        this(signals, needs, onDrafts, ActorRole.AUTHOR, null);
    }

    public PracticeBinding(
            List<SignalName> signals, List<PracticeEvidenceRequirement> needs, boolean onDrafts, ActorRole subject) {
        this(signals, needs, onDrafts, subject, null);
    }

    /** A binding on one signal that reads the evidence the artifact kind's default names. */
    public static PracticeBinding on(SignalName signal, List<PracticeEvidenceRequirement> needs) {
        return new PracticeBinding(List.of(signal), needs, false, ActorRole.AUTHOR, null);
    }

    /** Returns the common applicability predicate, or null when bindings are unconditional or disagree. */
    public static @Nullable PracticeSubject subjectFor(List<PracticeBinding> bindings, @Nullable SignalName signal) {
        PracticeSubject found = null;
        for (PracticeBinding binding : bindings) {
            if (signal != null && !binding.matches(signal)) {
                continue;
            }
            if (binding.appliesWhen() == null) {
                return null;
            }
            if (found != null && !found.equals(binding.appliesWhen())) {
                return null;
            }
            found = binding.appliesWhen();
        }
        return found;
    }

    /**
     * Whose conduct a review of these bindings, occasioned by {@code signal}, judges — the fact that
     * decides which person an observation is filed against.
     *
     * <p>A {@code null} signal means nobody named an occasion (a review asked for by hand, or a replay
     * with no ledger row), so every binding applies; the single-occasion rule makes that one binding in
     * practice. Where several occasions somehow disagree the answer is the one that is NOT
     * {@link ActorRole#AUTHOR}, because attributing a reviewer's conduct to the author is the failure
     * this method exists to prevent and the reverse merely withholds.
     */
    public static ActorRole subjectRoleOf(List<PracticeBinding> bindings, @Nullable SignalName signal) {
        for (PracticeBinding binding : bindings) {
            if ((signal == null || binding.matches(signal)) && binding.subject() != ActorRole.AUTHOR) {
                return binding.subject();
            }
        }
        return ActorRole.AUTHOR;
    }

    /**
     * The kind of artifact this binding is about, read off its signals' shared prefix.
     *
     * <p>Never serialized: a second statement of it is a second thing to disagree with the signals.
     */
    @JsonIgnore
    public ArtifactKind artifactKind() {
        return signals.getFirst().artifactKind();
    }

    /** Whether this signal occasions this review. */
    public boolean matches(SignalName signal) {
        return signals.contains(signal);
    }

    /**
     * Whether this binding is about this kind of work at all, whatever occasioned the review. Used for a
     * manually requested review: it names no narrower occasion, so matching its signal would find
     * nothing.
     */
    public boolean appliesTo(ArtifactKind kind) {
        return artifactKind().equals(kind);
    }

    /**
     * Whether an artifact in the given draft state occasions this review: a non-draft one always does,
     * a draft one only where the author said so.
     */
    public boolean occasionedBy(SignalName signal, boolean draft) {
        return matches(signal) && (!draft || onDrafts);
    }

    /**
     * The evidence a review of these practices occasioned by {@code signal} reads. A {@code null} signal
     * means nobody named an occasion (an explicit ask, or a replay with no ledger row), so every binding
     * contributes.
     */
    public static List<PracticeEvidenceRequirement> needsFor(
            List<PracticeBinding> bindings, @Nullable SignalName signal) {
        Set<PracticeEvidenceRequirement> union = new LinkedHashSet<>();
        for (PracticeBinding binding : bindings) {
            if (signal == null || binding.matches(signal)) {
                union.addAll(binding.needs());
            }
        }
        return union.stream()
                .sorted(Comparator.comparing(need -> need.sourceKind().value()))
                .toList();
    }

    /** The one artifact kind every binding in the list is about. */
    public static ArtifactKind artifactKindOf(List<PracticeBinding> bindings) {
        if (bindings.isEmpty()) {
            throw new IllegalArgumentException("A practice must declare at least one binding");
        }
        ArtifactKind kind = bindings.getFirst().artifactKind();
        for (PracticeBinding binding : bindings) {
            if (!kind.equals(binding.artifactKind())) {
                throw new IllegalArgumentException("A practice reviews one kind of artifact; bindings name " + kind
                        + " and " + binding.artifactKind());
            }
        }
        return kind;
    }

    /** Every signal named by any binding, in order. */
    public static List<SignalName> signalsOf(List<PracticeBinding> bindings) {
        return bindings.stream()
                .flatMap(binding -> binding.signals().stream())
                .distinct()
                .toList();
    }
}
