package de.tum.cit.aet.hephaestus.integration.submission;

/** What the server can say about where the content came from. */
public enum TeamSourceHow {
    /** The member sent these bytes. It is no proof that a source holds them. */
    MEMBER_SUPPLIED,
    /** The server read them with the submitting member's own access. */
    READ_AS_MEMBER
}
