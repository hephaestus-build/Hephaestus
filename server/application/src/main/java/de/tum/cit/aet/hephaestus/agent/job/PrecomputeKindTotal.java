package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;

/**
 * The precompute calls that one attempt made with one kind of model, over every practice. This is the
 * unit that the ledger bills and that the proxy prices and caps: pricing each practice's share alone
 * would round, and clamp to the minimum cost, once per practice.
 */
public record PrecomputeKindTotal(ModelKind modelKind, long calls, long inputTokens, long outputTokens) {}
