package de.tum.cit.aet.hephaestus.core.privacy.spi;

/** Implemented by stores that fence transient copies in the transaction that first admits an erasure. */
public interface PersonDataErasureAdmission {
    void erasureAdmitted(PersonDataSelection selection, PersonScope frozenScope);
}
