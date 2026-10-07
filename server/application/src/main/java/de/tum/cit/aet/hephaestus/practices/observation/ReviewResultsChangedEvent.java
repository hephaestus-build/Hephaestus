package de.tum.cit.aet.hephaestus.practices.observation;

/**
 * Published inside the transaction that records a review's observations, or invalidates or restores one: what a
 * standing reads in the workspace may have changed. Readers that keep counts of the workspace act on it once the
 * transaction commits.
 *
 * @param retracted whether a result was taken back, as an admin's invalidation takes one back: a count that still
 *     holds it may no longer be read, where a count that only lacks a new result may be read until it is recounted
 */
public record ReviewResultsChangedEvent(long workspaceId, boolean retracted) {}
