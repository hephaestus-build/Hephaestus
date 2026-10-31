package de.tum.cit.aet.hephaestus.productfeedback;

import java.util.Locale;

/**
 * Why a report is not public, in the order the routing gate checks: the first failing condition names it. It opens
 * every private issue and labels the routing metric.
 */
enum FilingReason {
    NONE("public"),
    OPTED_OUT("opted out"),
    SECURITY("security"),
    REDACTION("redaction"),
    ABOUT_PERSON("about a person"),
    LOW_CONFIDENCE("low confidence"),
    ABUSE("abuse"),
    NO_DRAFT("no draft"),
    /** A destination changed while the report waited; it goes to the admin email. */
    DESTINATION_CHANGED("destination changed"),
    /** GitHub refused the repository or the write; the report waits in Needs action. */
    DESTINATION_UNAVAILABLE("destination unavailable"),
    GITHUB_REFUSED("GitHub refused it");

    private final String words;

    FilingReason(String words) {
        this.words = words;
    }

    String words() {
        return words;
    }

    String tag() {
        return name().toLowerCase(Locale.ROOT);
    }
}
