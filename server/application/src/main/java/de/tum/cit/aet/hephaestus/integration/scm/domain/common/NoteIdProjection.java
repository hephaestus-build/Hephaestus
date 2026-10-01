package de.tum.cit.aet.hephaestus.integration.scm.domain.common;

/** Local and upstream IDs needed to reconcile a note without loading its content. */
public interface NoteIdProjection {
    Long getId();

    Long getNativeId();
}
