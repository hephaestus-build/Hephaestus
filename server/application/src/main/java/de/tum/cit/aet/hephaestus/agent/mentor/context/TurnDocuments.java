package de.tum.cit.aet.hephaestus.agent.mentor.context;

import java.util.Map;
import java.util.Optional;

/** The context documents of one turn attempt, as its sandbox stages them. Each is JSON with times in the developer's zone. */
public interface TurnDocuments {

    /** The fixed documents, by their folder path, built once per attempt. */
    Map<String, byte[]> built();

    /** An item document by its folder path, read now from its owning source; empty when none owns it or may show it. */
    Optional<byte[]> item(String path);

    /** When {@link #built()} was built, in the developer's zone, for {@code INDEX.md}. */
    String builtAt();
}
