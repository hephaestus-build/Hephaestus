import type { ProfileReviewRun } from "@/api/types.gen";
import { formatWeekdayDay } from "@/lib/dates";

/** One heading of the reviews list and the reviews under it. */
export interface ReviewRunDay {
	label: string;
	runs: ProfileReviewRun[];
}

/**
 * The reviews under a heading per day, in the order they arrived. The day is named outright rather
 * than as "Today": a page left open crosses midnight.
 */
export function groupReviewRunsByDay(runs: ProfileReviewRun[], now: Date): ReviewRunDay[] {
	const days: ReviewRunDay[] = [];
	for (const run of runs) {
		const label = formatWeekdayDay(run.reviewedAt, now);
		const last = days.at(-1);
		if (last?.label === label) {
			last.runs.push(run);
		} else {
			days.push({ label, runs: [run] });
		}
	}
	return days;
}

const workOf = (run: ProfileReviewRun) => `${run.reviewedWork.kind} ${run.reviewedWork.id}`;

/**
 * Each review's position among the reviews of its own work, counting from the oldest, by review id.
 * The runs arrive newest first, and must be every review there is: a page or a filter would count
 * short.
 */
export function runPositionsOnWork(runs: ProfileReviewRun[]): Map<string, number> {
	const remaining = new Map<string, number>();
	for (const run of runs) {
		remaining.set(workOf(run), (remaining.get(workOf(run)) ?? 0) + 1);
	}
	const positions = new Map<string, number>();
	for (const run of runs) {
		const position = remaining.get(workOf(run)) ?? 0;
		positions.set(run.reviewId, position);
		remaining.set(workOf(run), position - 1);
	}
	return positions;
}

const ORDINAL_RULES = new Intl.PluralRules("en-US", { type: "ordinal" });
const ORDINAL_SUFFIXES: Record<Intl.LDMLPluralRule, string> = {
	zero: "th",
	one: "st",
	two: "nd",
	few: "rd",
	many: "th",
	other: "th",
};

/**
 * "2nd review". The first review of a piece of work is not tagged: every work has one, so the tag
 * would single out none.
 */
export function reviewOrdinalLabel(position: number): string | undefined {
	return position <= 1
		? undefined
		: `${position}${ORDINAL_SUFFIXES[ORDINAL_RULES.select(position)]} review`;
}
