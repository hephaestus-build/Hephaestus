package de.tum.cit.aet.hephaestus.practices.result;

import de.tum.cit.aet.hephaestus.practices.spi.Currentness;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * A freshness read: the currentness of each claim, from the source fingerprints that its capture recorded and the work
 * that this server stores now. The practice review glossary § Currentness defines both terms.
 */
interface FreshnessRead {
    Map<UUID, Currentness> currentness(long workspaceId, Collection<ResultFreshness.Claim> claims);
}
