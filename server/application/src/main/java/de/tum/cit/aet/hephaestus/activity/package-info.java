/**
 * Append-only activity event log used for gamification and leaderboard aggregation.
 *
 * <p>The module owns {@code activity_event}.
 * XP is computed at write time and aggregated from stored values — see {@link de.tum.cit.aet.hephaestus.leaderboard}
 * for the read side.
 *
 * <p>Events are historical facts and remain when their upstream target is later deleted — ADR 0024
 * describes which reads honour a drift tombstone.
 *
 * <p>Distinct bounded context from {@link de.tum.cit.aet.hephaestus.practices} (code-health
 * analysis), even though both consume {@code ScmDomainEvent}s.
 *
 * <p>Cross-module callers reach the write path through
 * {@link de.tum.cit.aet.hephaestus.activity.spi.ActivityRecorder} (the {@code spi}
 * NamedInterface), not through {@code ActivityEventService} directly.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Activity Event Log")
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.activity;
