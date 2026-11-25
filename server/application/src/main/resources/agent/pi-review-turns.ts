// What a practice session may spend, and when it should record what it owes.
// Pure rules, so a test can call them; pi-runner.ts reads the workspace at module scope.

/**
 * Model calls, output tokens and input tokens: what a session may spend, or what it has spent. Input tokens are what
 * each call read anew: its uncached input and its cache writes, not the cache reads of a prompt already sent.
 */
export interface Work {
	modelCalls: number;
	outputTokens: number;
	inputTokens: number;
}

/**
 * Whether the turn should record now: two model calls left, no more output tokens left than writing what it
 * still owes takes at the pace the session has shown, or no more input tokens left than two calls of the size of
 * its last whole prompt. A call whose cache the model evicted reads its whole prompt anew, so the reserve assumes it.
 */
export function shouldRecordNow(
	used: Work,
	budget: Work,
	unrecorded: number,
	tokensPerProblem: number,
	lastPromptTokens: number,
): boolean {
	return (
		budget.modelCalls - used.modelCalls <= 2 ||
		budget.outputTokens - used.outputTokens <= Math.max(0, unrecorded) * tokensPerProblem ||
		budget.inputTokens - used.inputTokens <= 2 * lastPromptTokens
	);
}

/** What a turn has spent, in the dimensions of its budget. */
export function workOf(turn: { calls: number; outputTokens: number; inputTokens: number }): Work {
	return { modelCalls: turn.calls, outputTokens: turn.outputTokens, inputTokens: turn.inputTokens };
}

/** Whether the turn has spent its budget: it ends after the model call in flight, never within it. */
export function spent(work: Work, budget: Work): boolean {
	return (
		work.modelCalls >= budget.modelCalls ||
		work.outputTokens >= budget.outputTokens ||
		work.inputTokens >= budget.inputTokens
	);
}

export function missingSlugs(all: readonly string[], observed: readonly string[]): string[] {
	const seen = new Set(observed);
	return all.filter((slug) => !seen.has(slug));
}
