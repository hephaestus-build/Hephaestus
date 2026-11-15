package de.tum.cit.aet.hephaestus.core.database;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * The size of the volume that holds the database.
 *
 * @param volumeSize Size of the bundled PostgreSQL's volume. The chart sets it; empty reports no volume capacity.
 */
@ConfigurationProperties(prefix = "hephaestus.database")
public record DatabaseVolumeProperties(@Nullable DataSize volumeSize) {}
