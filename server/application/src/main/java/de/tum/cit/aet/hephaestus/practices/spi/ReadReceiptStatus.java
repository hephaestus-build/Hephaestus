package de.tum.cit.aet.hephaestus.practices.spi;

public enum ReadReceiptStatus {
    COMPLETE,
    TRUNCATED,
    FAILED,
    BINARY,
    ABSENT,
    /** A search ran over the scope at the receipt's path to the end; it carries no digest or lines. */
    SEARCHED;
}
