package de.tum.cit.aet.hephaestus.agent.handler;

import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionResultParser.ValidatedObservation;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Worst-first ordering for the measurements of one run, in the one place every stage that ranks them can
 * share it.
 *
 * <p><b>Why this exists at all.</b> Four stages used to carry their own copy of the same comparator, and
 * every copy tiebroke on detector-reported confidence, a field that did not discriminate observations and
 * has since been removed.
 * What replaces it has to be a property of the observation we can check rather than one it reports.
 *
 * <p><b>The keys, in order.</b>
 *
 * <ol>
 *   <li><b>Severity</b>, where it applies. A positive observation carries none (ADR 0022), and a null
 *       band sorts after every real one, so problems always precede strengths.</li>
 *   <li><b>Evidence breadth</b>, descending — {@link #evidenceBreadth(JsonNode)}, the number of distinct
 *       places the observation's citations point at. This is the in-run form of recurrence: a defect quoted at
 *       four loci is spreading through the change, and one quoted at a single locus is a one-off, which is
 *       the same reasoning the catalogue already applies when it tells a practice to weigh a copy "already
 *       spreading to several places" at the upper end of its band. It is observed rather than felt, it
 *       cannot be inflated by a model that wants its observation to lead, and it means the same thing for
 *       every practice in the catalogue — a security observation and a naming observation both get louder by
 *       being true in more of the work.</li>
 *   <li><b>A stable identity</b>, so the order is total and a re-run of the same job reproduces it exactly
 *       rather than flapping with the repository's iteration order. Persisted rows use their id;
 *       observations not yet stored use practice + title.</li>
 * </ol>
 */
public final class ObservationOrder {

    private ObservationOrder() {}

    /**
     * Persisted observations, worst first: severity, then evidence breadth, then id.
     *
     * <p>Callers that group before ranking (per recipient, say) compose this after their grouping key
     * rather than re-deriving the tail.
     */
    public static Comparator<Observation> worstFirst() {
        return Comparator.comparingInt((Observation o) -> severityOrdinal(o.getSeverity()))
                .thenComparing(Comparator.comparingInt((Observation o) -> evidenceBreadth(o.getEvidence()))
                        .reversed())
                .thenComparing(o -> o.getId().toString());
    }

    /** This run's observations, worst first: severity, then evidence breadth, then practice + title. */
    public static Comparator<ValidatedObservation> worstFirstUnstored() {
        return Comparator.comparingInt((ValidatedObservation f) -> severityOrdinal(f.severity()))
                .thenComparing(Comparator.comparingInt((ValidatedObservation f) -> evidenceBreadth(f.evidence()))
                        .reversed())
                .thenComparing(ObservationOrder::identityKey);
    }

    /**
     * Strengths, best attested first: breadth, then practice + title.
     *
     * <p>Severity is deliberately absent rather than merely inert: it is null on every strength, so
     * including it would suggest a ranking dimension that does not exist here.
     */
    public static Comparator<ValidatedObservation> bestAttestedFirst() {
        return Comparator.comparingInt((ValidatedObservation f) -> evidenceBreadth(f.evidence()))
                .reversed()
                .thenComparing(ObservationOrder::identityKey);
    }

    /**
     * How many distinct places this observation's evidence points at.
     *
     * <p>Distinct, not counted: a model that quotes one line twice has shown us one locus, and paying it
     * for the repetition would hand it the same lever {@code confidence} used to be. An observation with no
     * parseable evidence scores 0 and therefore sorts last within its severity band, which is the right
     * answer — we cannot see what it rests on.
     */
    public static int evidenceBreadth(@Nullable JsonNode evidence) {
        JsonNode citations = evidence == null ? null : evidence.get("citations");
        if (citations == null || !citations.isArray()) {
            return 0;
        }
        Set<String> loci = new HashSet<>();
        for (JsonNode citation : citations) {
            String path = citation.path("path").asString("");
            if (path.isBlank()) {
                continue;
            }
            loci.add(path
                    + ':'
                    + citation.path("side").asString("")
                    + ':'
                    + citation.path("startLine").asInt(0)
                    + '-'
                    + citation.path("endLine").asInt(citation.path("startLine").asInt(0)));
        }
        return loci.size();
    }

    /**
     * Severity ordinal for sorting, treating a null band (a positive strength under ADR 0022) as the
     * least severe so problems always sort ahead of strengths.
     */
    public static int severityOrdinal(@Nullable Severity severity) {
        return severity == null ? Integer.MAX_VALUE : severity.ordinal();
    }

    /**
     * The last, total key for an observation that has no persisted id yet. Practice and title together are
     * what a reader would use to tell two observations of one run apart; where even those tie, the two are
     * indistinguishable on the surface and their relative order cannot be observed.
     */
    private static String identityKey(ValidatedObservation observation) {
        return observation.practiceSlug() + '|' + observation.summary();
    }
}
