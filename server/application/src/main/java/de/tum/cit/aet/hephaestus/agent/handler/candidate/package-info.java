/**
 * The candidate a practice script returns for one practice, as admission reads it: {@link CandidateParser} applies
 * the rules of {@code parseCandidate} in {@code docker/agents/practice-scripts/lib/core/contract.ts}, in the same order and
 * with the same {@link CandidateRefusal} codes, so that both sides refuse one value for one reason. Admission then
 * verifies every citation against the captured sources; the parser trusts nothing it reads.
 */
@NullMarked
package de.tum.cit.aet.hephaestus.agent.handler.candidate;

import org.jspecify.annotations.NullMarked;
