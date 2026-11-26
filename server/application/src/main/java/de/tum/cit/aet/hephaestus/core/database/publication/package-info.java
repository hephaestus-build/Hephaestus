/**
 * The store of Spring Modulith's event publication registry. Core owns the {@code event_publication} table, so the
 * reads that the redelivery and its gauges need beyond Modulith's own live here.
 */
@org.springframework.modulith.NamedInterface("publication")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.core.database.publication;
