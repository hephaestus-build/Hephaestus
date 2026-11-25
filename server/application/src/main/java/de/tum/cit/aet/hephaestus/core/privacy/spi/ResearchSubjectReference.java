package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.regex.Pattern;

/** The source grant and released key of a subject, without any released data. */
public record ResearchSubjectReference(long accountId, String rowKey, long grantId) {
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_-]{1,255}");

    public ResearchSubjectReference {
        if (accountId <= 0 || grantId <= 0 || !KEY.matcher(rowKey).matches()) {
            throw new IllegalArgumentException("Invalid research subject reference");
        }
    }
}
