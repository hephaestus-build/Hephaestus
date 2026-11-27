/**
 * Lasting advice for one developer about one practice, on their private Practice Profile (ADR 0160).
 *
 * <p>Advice holds references to observations, never copies. Each transaction that deletes an observation first removes
 * the text of every advice revision that has it as a source ({@link AdviceRedaction}).
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices.advice;
