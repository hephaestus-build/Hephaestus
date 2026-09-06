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

export interface StageTimeouts {
	initialMs: number;
	retryMs: number;
	compositionMs: number;
}

/**
 * The retry's wall clock: whatever is left of the review budget when the initial pass ends, and never
 * less than the slice the split reserved for it. That reservation guards against an initial pass that
 * runs long; it is not a cap, because a pass that returned early has not spent the rest and the retry
 * is the last stage that can still close a practice nobody observed.
 *
 * <p>What is left of the process bounds it from above. The review budget is measured from the first
 * pass, the process budget from module load, and the watchdog's grace is all that covers the two
 * stretches inside neither: the SDK and model runtime the run builds before the first pass, and the
 * composition session it builds after the last one. So the retry stops early enough to leave
 * composition its own slice of what remains.
 */
export function deriveRetryWindow(
	timeouts: StageTimeouts,
	initialElapsedMs: number,
	remainingProcessMs: number,
) {
	for (const [name, value] of Object.entries({ ...timeouts, initialElapsedMs })) {
		if (!Number.isFinite(value) || value < 0) {
			throw new Error(`${name} must be a non-negative number, got: ${value}`);
		}
	}
	// remainingProcessMs is the one argument that may be negative: a process already past its budget
	// has a negative remainder, and the reservation below is what keeps that from arming a timer in
	// the past.
	const unspentReviewMs = timeouts.initialMs + timeouts.retryMs - initialElapsedMs;
	const beforeCompositionMs = remainingProcessMs - timeouts.compositionMs;
	return Math.max(timeouts.retryMs, Math.floor(Math.min(unspentReviewMs, beforeCompositionMs)));
}

/**
 * Arms the retry's hard stop on the window it derives, so the window the sessions are budgeted
 * against and the window the abort fires on are one number.
 */
export function armRetryWindow(
	timeouts: StageTimeouts,
	initialElapsedMs: number,
	remainingProcessMs: number,
	onExpire: () => void,
) {
	const windowMs = deriveRetryWindow(timeouts, initialElapsedMs, remainingProcessMs);
	return { windowMs, timer: setTimeout(onExpire, windowMs) };
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
