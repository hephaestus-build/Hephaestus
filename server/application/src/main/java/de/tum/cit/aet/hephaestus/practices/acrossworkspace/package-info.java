/**
 * Practices across the workspace: how the workspace's developers with a standing split across the practice groups,
 * counted in developers and never naming one. The reader is a marker on a split and a value on a tile; their own
 * learning stays in the practice profile.
 *
 * <p>A new audience in the sense of ADR 0047, decided in its own ADR. Read-only and self-scoped: the reader is the
 * caller, every other developer appears only inside a count, and what a count may say is decided in exactly one
 * place, {@link de.tum.cit.aet.hephaestus.practices.acrossworkspace.CohortPrivacyPolicy}.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices.acrossworkspace;
