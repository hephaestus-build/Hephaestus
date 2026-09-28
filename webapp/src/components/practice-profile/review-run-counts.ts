import type { ProfileReviewRun } from "@/api/types.gen";

/**
 * The numbers one review is described by, in one home, because a row, the run's own head and its
 * practice table each say one of them and three numbers that do not explain each other are worse
 * than one. Two facts, and every phrase below is built from them:
 *
 * - **listed** is every practice the run's table lists: the ones it reached, the ones it skipped and
 *   the ones it never got to. It comes from the run's activity rather than the run row, so it is
 *   absent until that has loaded.
 * - **reached** is what the review evaluated, and it is what the four observation counts add up to:
 *   held, to improve, did not apply and undecided. So reached is never larger than listed.
 */
export interface ReviewRunCounts {
	/** Practices whose subject held up in this work. */
	held: number;
	/** Practices this work fell short of. */
	toImprove: number;
	/** Practices whose subject did not occur in this work. */
	didNotApply: number;
	/** Practices that looked and could not settle the question either way. */
	undecided: number;
	/** held + toImprove + didNotApply + undecided, as the run itself recorded it. */
	reached: number | undefined;
	/** Every practice the run's table lists, once the activity that lists them is in. */
	listed: number | undefined;
}

export function reviewRunCounts(run: ProfileReviewRun, listed?: number): ReviewRunCounts {
	const { strengths, problems, notApplicable, undetermined } = run.observations;
	return {
		held: strengths,
		toImprove: problems,
		didNotApply: notApplicable,
		undecided: undetermined,
		reached: run.practicesEvaluated,
		listed,
	};
}

/**
 * What a row says under the practices it named: "5 held, 9 did not apply, 16 reached". A nought is
 * dropped, unlike the operator console's fixed-width strip, because this is a sentence about
 * somebody's own work and "0 held" is a message where there is none. What slipped is not counted
 * here: the line above the row already names those practices.
 */
export function reachedPhrase(counts: ReviewRunCounts): string {
	const parts: string[] = [];
	if (counts.held > 0) {
		parts.push(`${counts.held} held`);
	}
	if (counts.didNotApply > 0) {
		parts.push(`${counts.didNotApply} did not apply`);
	}
	if (counts.undecided > 0) {
		parts.push(`${counts.undecided} undecided`);
	}
	if (counts.reached !== undefined) {
		parts.push(`${counts.reached} reached`);
	}
	return parts.join(", ");
}

/**
 * What the run's head says about its coverage: "16 of 21 practices reached", the same two numbers
 * the table underneath counts its rows by. Nothing while either is missing, since half the ratio is
 * a number the reader cannot place.
 */
export function reachedOfListedLabel(counts: ReviewRunCounts): string | undefined {
	if (counts.reached === undefined || counts.listed === undefined) {
		return undefined;
	}
	return `${counts.reached} of ${counts.listed} practices reached`;
}
