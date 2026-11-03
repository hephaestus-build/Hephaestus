// What a practice session may spend, and when it should record what it owes.
// Pure rules, so a test can call them; pi-runner.ts reads the workspace at module scope.

/** Model calls and output tokens: what a session may spend, or what it has spent. */
export interface Work {
	modelCalls: number;
	outputTokens: number;
}

/**
 * Whether the turn should record now: two model calls left, or no more output tokens left than writing
 * what it still owes takes at the pace the session has shown.
 */
export function shouldRecordNow(
	used: Work,
	budget: Work,
	unrecorded: number,
	tokensPerProblem: number,
): boolean {
	return (
		budget.modelCalls - used.modelCalls <= 2 ||
		budget.outputTokens - used.outputTokens <= Math.max(0, unrecorded) * tokensPerProblem
	);
}

/** Whether the turn has spent its budget: it ends after the model call in flight, never within it. */
export function spent(work: Work, budget: Work): boolean {
	return work.modelCalls >= budget.modelCalls || work.outputTokens >= budget.outputTokens;
}

export function missingSlugs(all: readonly string[], observed: readonly string[]): string[] {
	const seen = new Set(observed);
	return all.filter((slug) => !seen.has(slug));
}
