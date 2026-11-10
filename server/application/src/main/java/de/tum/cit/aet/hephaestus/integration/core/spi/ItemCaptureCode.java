package de.tum.cit.aet.hephaestus.integration.core.spi;

public enum ItemCaptureCode {
    ITEM_REMOVED,
    ITEM_NOT_VISIBLE,
    VERSION_UNAVAILABLE,
    SOURCE_NOT_READY,
    CONSENT_WITHHELD,
    CAPTURE_FAILED,
    /**
     * A server could not build a checkout that another server captured with the recorded commit, tree and checkout
     * digest. The work is unchanged; the copy is not available here.
     */
    CHECKOUT_UNVERIFIED,
    /**
     * An event review's occasion has no usable record of the work as it was: none was kept, or it expired, was erased
     * or exceeded the size limit. It never becomes available later, and the source never substitutes the current work.
     */
    OCCASION_EVIDENCE_UNAVAILABLE
}
