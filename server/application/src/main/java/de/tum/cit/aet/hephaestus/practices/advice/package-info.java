/**
 * Lasting advice for one developer about one practice, on their private Practice Profile (ADR 0160). Advice holds
 * references to observations, never copies. Nothing writes it until every path that deletes an observation also
 * removes the advice written from it.
 */
@org.jspecify.annotations.NullMarked
package de.tum.cit.aet.hephaestus.practices.advice;
