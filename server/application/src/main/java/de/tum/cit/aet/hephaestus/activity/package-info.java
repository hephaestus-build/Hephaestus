/**
 * Activity: the activity ledger ({@code activity_event}) and, in {@code activity.overview}, the read model built
 * on it. Other modules record through {@link de.tum.cit.aet.hephaestus.activity.spi.ActivityRecorder}; ADR 0024
 * describes which reads honour a drift tombstone.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Activity")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.activity;
