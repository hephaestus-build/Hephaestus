package de.tum.cit.aet.hephaestus.practices.curated;

import java.io.Serial;

public class StaleCuratedEntryException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StaleCuratedEntryException(String subject) {
        super(subject + " changed since it was loaded.");
    }
}
