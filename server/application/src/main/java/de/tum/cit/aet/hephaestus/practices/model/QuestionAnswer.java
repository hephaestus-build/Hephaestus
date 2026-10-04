package de.tum.cit.aet.hephaestus.practices.model;

/**
 * An answer to one practice question. A rule tests YES or NO; UNDETERMINED is an answer only a reviewer that names
 * the evidence that would settle it may give, and the judgment keeps an outcome only when every resolution agrees.
 */
public enum QuestionAnswer {
    YES,
    NO,
    UNDETERMINED;

    public boolean isDefinite() {
        return this != UNDETERMINED;
    }
}
