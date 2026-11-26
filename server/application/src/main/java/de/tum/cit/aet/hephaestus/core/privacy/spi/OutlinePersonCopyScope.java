package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.Map;

/** The Outline instance and team whose content was available to a conversation turn. */
public interface OutlinePersonCopyScope {
    record Source(String origin, String team) {}

    record Selection(Map<Long, List<Source>> sources, boolean complete) {}

    Selection selectSources(PersonScope person);

    boolean onlyOutline(PersonScope person);
}
