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

/** Shared reconnaissance uses at most a quarter of a short review and four minutes of a long one. */
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
 * The retry's wall clock: what the initial pass did not spend, and never less than the slice the
 * split reserved for it. That floor is the point of the reservation — an initial pass that ran long
 * is exactly when a practice is still unobserved, and the retry is the last stage that can close
 * one. Taking the unspent time alone would hand that case zero.
 *
 * <p>The process deadline is the only hard cap, and composition is subtracted from it first: the
 * review budget is measured from the first pass and the process budget from module load, so the
 * stretches inside neither — building the runtime before, composing after — come out of what is
 * left. A window of zero means there is genuinely nothing left to spend.
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
	if (!Number.isFinite(remainingProcessMs)) {
		throw new TypeError(`remainingProcessMs must be finite, got: ${remainingProcessMs}`);
	}
	const unspentReviewMs = timeouts.initialMs + timeouts.retryMs - initialElapsedMs;
	const beforeCompositionMs = remainingProcessMs - timeouts.compositionMs;
	const wantedMs = Math.max(unspentReviewMs, timeouts.retryMs);
	return Math.max(0, Math.floor(Math.min(wantedMs, beforeCompositionMs)));
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
	if (windowMs === 0) onExpire();
	return { windowMs, timer: windowMs > 0 ? setTimeout(onExpire, windowMs) : undefined };
}

export function deriveCompositionWindow(compositionMs: number, remainingProcessMs: number): number {
	if (
		!Number.isFinite(compositionMs) ||
		compositionMs < 0 ||
		!Number.isFinite(remainingProcessMs)
	) {
		throw new Error("compositionMs must be non-negative and both budgets must be finite");
	}
	return Math.max(0, Math.floor(Math.min(compositionMs, remainingProcessMs)));
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
