package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.Map;

/** The Outline instance and team whose content was available to a conversation turn. */
public interface OutlinePersonCopyScope {
    record Source(String origin, String team) {}

    Map<Long, List<Source>> sources(PersonScope person);

    boolean onlyOutline(PersonScope person);

    /** A frozen selection must cover every currently matching Outline document. */
    void verify(PersonScope person);
}
