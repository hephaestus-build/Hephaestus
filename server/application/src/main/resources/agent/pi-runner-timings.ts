export function deriveTimeouts(agentBudgetMs: number, compositionEnabled = false) {
	if (!Number.isFinite(agentBudgetMs) || agentBudgetMs <= 0) {
		throw new Error(`agentBudgetMs must be a positive number, got: ${agentBudgetMs}`);
	}

	const compositionMs = compositionEnabled ? Math.floor(agentBudgetMs * 0.15) : 0;
	const reviewBudgetMs = agentBudgetMs - compositionMs;
	const initialMs = Math.floor(reviewBudgetMs * 0.85);
	return {
		initialMs,
		retryMs: reviewBudgetMs - initialMs,
		compositionMs,
	};
}

/**
 * How long the shared reconnaissance may take before the groups start without it.
 *
 * <p>It is one model turn over the whole change, so it is bounded below by what a turn needs rather
 * than by a share of the review: a budget that expires before the first answer buys nothing and costs
 * every group the shared reading. The cap keeps a large review from spending its observers' time here.
 * The reconnaissance is paid for out of the first pass, so the floor is bounded by a quarter of it:
 * a deadline longer than the pass that funds it can never expire, and the whole review would run out
 * of time before any group heard that it was starting alone.
 */
export function deriveReconBudget(initialMs: number) {
	if (!Number.isFinite(initialMs) || initialMs <= 0) {
		throw new Error(`initialMs must be a positive number, got: ${initialMs}`);
	}
	const floorMs = Math.min(120_000, Math.floor(initialMs * 0.25));
	return Math.min(240_000, Math.max(floorMs, Math.floor(initialMs * 0.1)));
}

export function deriveTurnTiming(remainingMs: number, remainingTurns: number) {
	if (!Number.isFinite(remainingMs) || remainingMs < 0) {
		throw new Error(`remainingMs must be a non-negative number, got: ${remainingMs}`);
	}
	if (!Number.isInteger(remainingTurns) || remainingTurns <= 0) {
		throw new Error(`remainingTurns must be a positive integer, got: ${remainingTurns}`);
	}

	const fairShareMs = Math.floor(remainingMs / remainingTurns);
	return {
		fairShareMs,
		softNudgeMs: Math.floor(fairShareMs * 0.6),
	};
}

export function deriveWorkstreamBudget(
	remainingMs: number,
	activeSlots: number,
	remainingWorkstreams: number,
) {
	if (!Number.isFinite(remainingMs) || remainingMs < 0) {
		throw new Error(`remainingMs must be a non-negative number, got: ${remainingMs}`);
	}
	if (!Number.isInteger(activeSlots) || activeSlots <= 0) {
		throw new Error(`activeSlots must be a positive integer, got: ${activeSlots}`);
	}
	if (!Number.isInteger(remainingWorkstreams) || remainingWorkstreams <= 0) {
		throw new Error(
			`remainingWorkstreams must be a positive integer, got: ${remainingWorkstreams}`,
		);
	}
	return Math.max(1, Math.floor((remainingMs * activeSlots) / remainingWorkstreams));
}
