import type { ProfileReviewRun } from "@/api/types.gen";
import { asDate, formatWeekdayDay } from "@/lib/dates";
import { hasText } from "@/lib/text";

/** One heading of the runs list and the runs under it, in the order the list draws them. */
export interface ReviewRunDay {
	/** "Monday, 21 September", "Sunday, 21 September 2025", or "Not dated". */
	label: string;
	runs: ProfileReviewRun[];
}

/** The heading over a run whose moment the wire could not name; it claims no day. */
export const NOT_DATED = "Not dated";

/**
 * What the heading over a run says: the day itself, weekday and date, with the year only once the
 * year has turned, since "21 September" in September needs no 2026 after it. Never "Today" or
 * "Yesterday": a page left open crosses midnight and those two words then name the wrong day, and a
 * reader comparing two reviews wants the date they can carry to the work rather than a word that
 * only holds while they are looking. The row underneath carries the time alone, so a column of runs
 * reads as a column of times rather than repeating a date its heading already gives.
 */
export function reviewRunDayLabel(at: Date, now: Date): string {
	return formatWeekdayDay(at, now);
}

/**
 * The runs under their headings, keeping the order they arrived in, the wire's newest first, so a
 * heading never repeats and the list reads straight down. A run whose date the wire could not name
 * stays in the list under a heading that claims no day, rather than under one that asserts a date
 * the run does not have.
 */
export function groupReviewRunsByDay(runs: ProfileReviewRun[], now: Date): ReviewRunDay[] {
	const days: ReviewRunDay[] = [];
	for (const run of runs) {
		const at = asDate(run.reviewedAt);
		const label = at ? reviewRunDayLabel(at, now) : NOT_DATED;
		const last = days.at(-1);
		if (last?.label === label) {
			last.runs.push(run);
		} else {
			days.push({ label, runs: [run] });
		}
	}
	return days;
}

/** True when the run recorded nothing about the reader and no feedback of its reached them. */
export function reviewRunFoundNothing(run: ProfileReviewRun): boolean {
	const { strengths, problems, notApplicable, undetermined } = run.observations;
	return strengths + problems + notApplicable + undetermined === 0 && run.feedbackDelivered === 0;
}

/** Which run of its own work a run is, and how many runs that work has had. */
export interface RunPosition {
	/** 1 for the oldest run on the work, counting up to the newest. */
	position: number;
	total: number;
}

/**
 * Which run of its work each run is, by review id. Run 1 is the oldest, so the label reads the way
 * the work's own history does, and a piece of work reviewed once reads "Run 1 of 1": one review is
 * a fact about the work too.
 *
 * Only ever computed from a list that holds every run there is. A page of the list, or a filtered
 * one, would make the total a number we cannot stand behind, which is what {@link holdsEveryRun}
 * decides before anybody asks.
 */
export function runPositionsOnWork(runs: ProfileReviewRun[]): Map<string, RunPosition> {
	const byWork = new Map<string, ProfileReviewRun[]>();
	for (const run of runs) {
		const key = `${run.reviewedWork.kind} ${run.reviewedWork.id}`;
		const found = byWork.get(key);
		if (found) {
			found.push(run);
		} else {
			byWork.set(key, [run]);
		}
	}
	const positions = new Map<string, RunPosition>();
	for (const onWork of byWork.values()) {
		const total = onWork.length;
		// The list arrives newest first, so the oldest of them is the last.
		for (const [index, run] of onWork.entries()) {
			positions.set(run.reviewId, { position: total - index, total });
		}
	}
	return positions;
}

/**
 * What a row and the run's own head call a position, beside the work they are about: "2nd review",
 * "3rd review". The first review of a piece of work says nothing — every work has one, so a tag on
 * it would mark every row and single out none — and the tag then means what a reader scanning the
 * column wants to know, that this work has been round before.
 */
export function reviewOrdinalLabel(at: RunPosition): string | undefined {
	return at.position <= 1 ? undefined : `${ordinal(at.position)} review`;
}

/** English ordinals: 11th, 12th and 13th take "th" although they end in 1, 2 and 3. */
function ordinal(value: number): string {
	const teens = value % 100;
	if (teens >= 11 && teens <= 13) {
		return `${value}th`;
	}
	const suffixes = ["th", "st", "nd", "rd"];
	return `${value}${suffixes[value % 10] ?? "th"}`;
}

/**
 * Whether these runs are every run there is: loaded, failed at nothing, narrowed by nothing and
 * with no further page behind them. The one home of that rule, because two surfaces count runs from
 * it and a count either of them cannot stand behind is worse than no count.
 */
export function holdsEveryRun(list: {
	isLoading: boolean;
	error?: unknown;
	hasMore?: boolean;
	kind?: string;
	since?: unknown;
}): boolean {
	return (
		!list.isLoading &&
		list.error == null &&
		list.hasMore !== true &&
		!hasText(list.kind) &&
		list.since === undefined
	);
}
