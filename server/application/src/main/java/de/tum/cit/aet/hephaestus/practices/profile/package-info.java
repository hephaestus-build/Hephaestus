/**
 * The practice profile's overview: what held, what changed and which work was reviewed since the
 * developer's latest review run, as structured events the page phrases itself.
 *
 * <p>Read-only and self-scoped like the standings it is derived from: the developer is the caller, never a
 * parameter. Nothing here is stored; every standing "before" is recomputed by reading the same evidence as of
 * the previous run.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices.profile;
