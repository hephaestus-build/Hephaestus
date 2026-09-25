/**
 * The practice profile's overview: what held, what changed and which work was reviewed since the run before the
 * developer's latest review run, as structured events the page phrases itself.
 *
 * <p>Read-only and self-scoped like the standings it is derived from: the developer is the caller, never a
 * parameter. Nothing here is stored. The window is {@link
 * de.tum.cit.aet.hephaestus.practices.profile.OverviewWindow}; its rules are in
 * {@code docs/contributor/practice-review-glossary.mdx} § Practice profile overview.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices.profile;
