import type { CleanWork, ReviewedWorkOutcome } from "./PracticeFeedbackCard";

/**
 * The card's timeline is a window on the supplied reviews of this developer's work, at most this
 * many, the evidence and the clean work alike: a glance at the pattern, not a log of every place it
 * was seen. The older pieces fold into one short "…" stretch at the start of the line.
 */
export const TIMELINE_WORK_LIMIT = 5;

/** One place on the timeline, in the order it is drawn: a piece of work, what the review made of it, and when. */
export interface TimelinePiece extends ReviewedWorkOutcome {
	key: string;
}

/**
 * "2 clean in a row · 1 more resolves it": the card's meter in words that cannot be read as "2 of
 * the last 3", which the group table's pattern label repeats. A full run says only how long it is; an empty one, how long it has to be.
 */
export function cleanProgress({
	cleanCount,
	cleanNeeded,
}: {
	cleanCount: number;
	cleanNeeded: number;
}): string {
	if (cleanCount >= cleanNeeded) {
		return `${cleanCount} clean in a row`;
	}
	const more = cleanNeeded - cleanCount;
	const resolves = more === 1 ? "resolves" : "resolve";
	return cleanCount === 0
		? `None clean yet · ${more} in a row ${resolves} it`
		: `${cleanCount} clean in a row · ${more} more ${resolves} it`;
}

const byDate = (a: { date: Date }, b: { date: Date }) => a.date.getTime() - b.date.getTime();

/**
 * The supplied evidence and clean work, ordered by their recorded review timestamps. The window
 * keeps the newest supplied records, at most `limit`; evidence need not predate the card.
 */
export function timelinePieces(
	reviewedWork: ReviewedWorkOutcome[],
	cleanWork: CleanWork[],
	limit: number = TIMELINE_WORK_LIMIT,
): TimelinePiece[] {
	return [
		// The same thread can be reviewed twice on different days.
		...reviewedWork.map((work) => ({
			...work,
			key: `${work.ref.kind} ${work.ref.id} ${work.date.getTime()}`,
		})),
		...cleanWork.map((clean) => ({
			...clean,
			key: `${clean.ref.kind} ${clean.ref.id} clean`,
			outcome: "MET" as const,
		})),
	]
		.sort(byDate)
		.slice(-limit);
}
