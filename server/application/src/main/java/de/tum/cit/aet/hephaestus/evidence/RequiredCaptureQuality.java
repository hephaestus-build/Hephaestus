package de.tum.cit.aet.hephaestus.evidence;

/**
 * What a practice is asking for when it requires this source — a fact about the source, not about the
 * practice that names it.
 *
 * <p>Stated once per source, not once per practice: {@code EvidencePolicyRedundancyTest} measures that every
 * shipped practice agrees on the answer for a given source, rather than assuming it.
 *
 * <p>One ordered enum, not two independent booleans: the fourth cell of the cross-product — "non-empty but
 * possibly partial" — asserts something no source can support, since a partial capture that contains
 * something cannot rule out that what it omitted was the part that mattered.
 */
public enum RequiredCaptureQuality {
    /** The capture must have succeeded. Whatever it holds, including nothing, is reviewable. */
    ANY_CAPTURE,

    /**
     * The capture must cover the whole selected scope. A partial one cannot support a judgement about
     * the thing as a whole, and the model cannot see that it is reading a fragment.
     */
    COMPLETE,

    /**
     * Complete, and containing something. Use this only when the source contract requires content
     * to support review. A complete empty capture remains distinct from a failed or missing capture.
     */
    COMPLETE_AND_NON_EMPTY;

    public boolean demandsComplete() {
        return this != ANY_CAPTURE;
    }

    public boolean demandsContent() {
        return this == COMPLETE_AND_NON_EMPTY;
    }
}
