// How one review session spends its practices and its work: practices go to the model in turns, a
// turn per catalog group (chunked when a group is large), and each turn may spend model calls and output
// tokens in proportion to the practices it carries — its own budget, whatever the other turns spent.
// Time bounds nothing here but a run that stops responding. Pure rules, so a test can call them;
// pi-runner.ts reads the workspace at module scope.

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

export interface ReviewWindows {
	/** When measuring must hand in, so admission and composition still land before the run is stopped. */
	measureMs: number;
	/** Kept for composition when it was requested; zero otherwise. */
	compositionMs: number;
}

/** The part of the safety ceiling kept for composition. */
const COMPOSITION_SHARE = 0.15;

/**
 * How the run's safety ceiling — the time after which a stuck run is stopped — is split, so measuring
 * hands in early enough for admission and composition. Not a budget: work is bounded by {@link turnBudget}.
 */
export function deriveWindows(budgetMs: number, compositionRequested: boolean): ReviewWindows {
	if (!Number.isFinite(budgetMs) || budgetMs <= 0) {
		throw new Error(`budgetMs must be a positive number, got: ${budgetMs}`);
	}
	const compositionMs = compositionRequested ? Math.floor(budgetMs * COMPOSITION_SHARE) : 0;
	return { measureMs: budgetMs - compositionMs, compositionMs };
}

/** Model calls and output tokens: what a turn may spend, or what it has spent. */
export interface Work {
	modelCalls: number;
	outputTokens: number;
}

/**
 * A turn's budget: one unit per practice it carries, and never less than three units, since a turn
 * of one practice still reads before it records.
 */
export function turnBudget(practices: number, perPractice: Work): Work {
	if (!Number.isInteger(practices) || practices < 1) {
		throw new Error(`practices must be a positive integer, got: ${practices}`);
	}
	const units = Math.max(3, practices);
	return {
		modelCalls: perPractice.modelCalls * units,
		outputTokens: perPractice.outputTokens * units,
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
	tokensPerObservation: number,
): boolean {
	return (
		budget.modelCalls - used.modelCalls <= 2 ||
		budget.outputTokens - used.outputTokens <= Math.max(0, unrecorded) * tokensPerObservation
	);
}

/** Whether the turn has spent its budget: it ends after the model call in flight, never within it. */
export function spent(work: Work, budget: Work): boolean {
	return work.modelCalls >= budget.modelCalls || work.outputTokens >= budget.outputTokens;
}

/** The practices with no recorded observation, in the order the review asked about them. */
export function missingSlugs(all: readonly string[], observed: readonly string[]): string[] {
	const seen = new Set(observed);
	return all.filter((slug) => !seen.has(slug));
}
