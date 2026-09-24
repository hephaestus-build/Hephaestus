// How one review session spends its practices and its time: practices go to the model in turns, a
// turn per catalog group (chunked when a group is large), and each turn gets a fair share of what is
// left. Pure rules, so a test can call them; pi-runner.ts reads the workspace at module scope.

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
	/** What the measuring turns may spend in total. */
	measureMs: number;
	/** Reserved for the composition turn; zero when no composition was requested. */
	compositionMs: number;
}

/** Composition gets a fixed reserve of the budget when it was requested; measuring gets the rest. */
export function deriveWindows(budgetMs: number, compositionRequested: boolean): ReviewWindows {
	if (!Number.isFinite(budgetMs) || budgetMs <= 0) {
		throw new Error(`budgetMs must be a positive number, got: ${budgetMs}`);
	}
	const compositionMs = compositionRequested ? Math.floor(budgetMs * 0.15) : 0;
	return { measureMs: budgetMs - compositionMs, compositionMs };
}

export interface TurnShare {
	/** When the turn is aborted. */
	hardMs: number;
	/** When the turn is asked to record what it has and stop exploring. */
	softMs: number;
}

/** A turn's fair share of what is left: the remainder divided by the turns still to run. */
export function turnShare(remainingMs: number, remainingTurns: number): TurnShare {
	if (!Number.isInteger(remainingTurns) || remainingTurns < 1) {
		throw new Error(`remainingTurns must be a positive integer, got: ${remainingTurns}`);
	}
	const hardMs = Math.max(0, Math.floor(remainingMs / remainingTurns));
	return { hardMs, softMs: Math.floor(hardMs * 0.7) };
}

/** The practices with no recorded observation, in the order the review asked about them. */
export function missingSlugs(all: readonly string[], observed: readonly string[]): string[] {
	const seen = new Set(observed);
	return all.filter((slug) => !seen.has(slug));
}
