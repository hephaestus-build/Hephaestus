package de.tum.cit.aet.hephaestus.core.privacy.spi;

import java.util.List;
import java.util.UUID;

/** The release associations that still identify the person in the live research store. */
public interface ResearchRecoverySubjects {
    List<UUID> releasesFor(long accountId);
}
