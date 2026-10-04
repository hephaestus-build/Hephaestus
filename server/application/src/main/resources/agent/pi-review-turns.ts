// How one review session spends its practices and its work: practices go to the model in turns, a
// turn per catalog group (chunked when a group is large), and each turn may spend model calls and output
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

export interface ReviewWindows {
	/** When measuring must hand in, so admission and composition still land before the run is stopped. */
	measureMs: number;
	/** Kept for composition when it was requested; zero otherwise. */
	compositionMs: number;
}

/** The part of the safety ceiling kept for composition. */
const COMPOSITION_SHARE = 0.15;

/**
 * How the run's safety ceiling is split, so measuring
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

/** What starts a practice turn's own part of its prompt; what comes before it is the opening, if any, and the heading. */
export const TURN_PRACTICES_MARKER = "Evaluate these practices:";

/** What a finished turn's prompt keeps of its own part. */
export const FINISHED_TURN_NOTE =
	"Its practices are recorded; their criteria and the output of this turn's reads are cleared. Read a line again if a later question needs it.";

/** What a long output of a finished turn's call is replaced with. */
export const CLEARED_OUTPUT =
	"Output cleared after its turn; read it again if a later question needs it.";

/** An output longer than this is the bulk of a finished turn, not its reasoning. */
const KEPT_OUTPUT_CHARS = 1200;

/** One message of the model's context, as the session holds it: the entry it came from, its role and its text. */
export interface ContextMessage {
	entryId: string;
	role: string;
	text: string;
}

/** A replacement for one entry's content in every later model call. */
export interface ContextEdit {
	targetId: string;
	content: string;
}

/**
 * What the finished turns leave for the turns after them, once the context has outgrown its budget. Their
 * practices are recorded, so their criteria and the raw output of their reads only make every later call longer:
 * each prompt keeps the opening it carried and its heading, and each long tool output is cleared. The model's own
 * messages — what it read, concluded and recorded — stay, so later turns still build on them.
 *
 * An edit changes the context every later call begins with, so a provider's prompt cache serves none of what
 * follows it; below the budget the context only grows by appending and stays cached. Past it, every finished
 * turn not yet edited is edited at once, so one cache break buys the most room. A prompt already edited no
 * longer holds the marker, so no turn is edited twice.
 *
 * @param budgetChars the context size, in characters, past which the finished turns are cleared
 */
export function finishedTurnEdits(
	context: readonly ContextMessage[],
	budgetChars: number,
): ContextEdit[] {
	const size = context.reduce((total, message) => total + message.text.length, 0);
	const start = context.findIndex(
		(message) => message.role === "user" && message.text.includes(TURN_PRACTICES_MARKER),
	);
	if (size <= budgetChars || start === -1) {
		return [];
	}
	return context.slice(start).flatMap((message): ContextEdit[] => {
		if (message.role === "user" && message.text.includes(TURN_PRACTICES_MARKER)) {
			return [
				{
					targetId: message.entryId,
					content: `${message.text.slice(0, message.text.indexOf(TURN_PRACTICES_MARKER))}${FINISHED_TURN_NOTE}`,
				},
			];
		}
		return message.role === "toolResult" && message.text.length > KEPT_OUTPUT_CHARS
			? [{ targetId: message.entryId, content: CLEARED_OUTPUT }]
			: [];
	});
}
