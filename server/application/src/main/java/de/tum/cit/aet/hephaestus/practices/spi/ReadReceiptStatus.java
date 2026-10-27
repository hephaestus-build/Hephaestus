package de.tum.cit.aet.hephaestus.practices.spi;

public enum ReadReceiptStatus {
    COMPLETE,
    TRUNCATED,
    FAILED,
    BINARY,
    ABSENT,
    /** A search for the receipt's pattern ran over the scope at its path to the end; it carries no digest or lines. */
    SEARCHED;
}
