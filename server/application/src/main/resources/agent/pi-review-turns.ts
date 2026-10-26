// How a review schedules its practices and work: each catalog group (chunked when large) shares a
// turn budget across its fresh practice sessions. Each turn may spend model calls and output
// tokens in proportion to the practices it carries — its own budget, whatever the other turns spent.
// The whole-run safety ceiling still applies, so later practices may not be reached.
// Pure rules, so a test can call them; pi-runner.ts reads the workspace at module scope.

export interface TurnPractice {
	slug: string;
	group?: string;
}

export interface ReviewTurn {
	id: string;
	slugs: string[];
}

/**
 * The practices of a review as turns, in index order: one turn per catalog group, a group larger than
 * `maxPerTurn` split in index order, practices without a group in a turn of their own.
 */
export function planTurns(practices: readonly TurnPractice[], maxPerTurn = 6): ReviewTurn[] {
	if (!Number.isInteger(maxPerTurn) || maxPerTurn < 1) {
		throw new Error(`maxPerTurn must be a positive integer, got: ${maxPerTurn}`);
	}
	const seen = new Set<string>();
	const byGroup = new Map<string, string[]>();
	for (const practice of practices) {
		if (!practice.slug.trim()) {
			throw new Error("a practice needs a slug");
		}
		if (seen.has(practice.slug)) {
			throw new Error(`duplicate practice slug: ${practice.slug}`);
		}
		seen.add(practice.slug);
		const group = practice.group?.trim();
		const key = group === undefined || group === "" ? practice.slug : group;
		byGroup.set(key, [...(byGroup.get(key) ?? []), practice.slug]);
	}
	const turns: ReviewTurn[] = [];
	for (const [group, slugs] of byGroup) {
		for (let start = 0; start < slugs.length; start += maxPerTurn) {
			const chunk = slugs.slice(start, start + maxPerTurn);
			const suffix = slugs.length > maxPerTurn ? `-${start / maxPerTurn + 1}` : "";
			turns.push({ id: `${group}${suffix}`, slugs: chunk });
		}
	}
	return turns;
}

/** Model calls and output tokens: what a turn may spend, or what it has spent. */
export interface Work {
	modelCalls: number;
	outputTokens: number;
}

export function turnBudget(practices: number, perPractice: Work): Work {
	if (!Number.isInteger(practices) || practices < 1) {
		throw new Error(`practices must be a positive integer, got: ${practices}`);
	}
	const feedback = Math.max(3, practices);
	return {
		modelCalls: perPractice.modelCalls * feedback,
		outputTokens: perPractice.outputTokens * feedback,
	};
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

/** Reserve disjoint shares before sessions start, so no practice can spend a sibling's work. */
export function partitionBudget(budget: Work, practices: number, position: number): Work {
	const share = (value: number) =>
		Math.floor(value / practices) + (position < value % practices ? 1 : 0);
	return { modelCalls: share(budget.modelCalls), outputTokens: share(budget.outputTokens) };
}
