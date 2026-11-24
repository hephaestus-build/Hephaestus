package de.tum.cit.aet.hephaestus.core.privacy;

/** A durable copy of a journal entry, outside the database's recovery boundary. */
interface PrivacyJournalObjectStore {

    /** Whether this adapter has verified conditional writes and stored checksums. */
    boolean verified();

    /** Returns true only when the entry's exact bytes are stored under its stable object key. */
    boolean store(String relativeKey, byte[] body);
}
