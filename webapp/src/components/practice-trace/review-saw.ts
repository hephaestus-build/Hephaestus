import type { ReviewRef, ReviewResultCounts } from "@/api/types.gen";
import { count, spell } from "@/components/practice-vocabulary/feedback-text";

export interface ReviewSaw {
	status: ReviewRef["status"];
	results: ReviewResultCounts;
	practicesReached?: number;
	notMetPractices: string[];
}

const NAMED_PRACTICES = 2;

export function reviewLead(saw: ReviewSaw): string {
	const names = saw.notMetPractices;
	if (names.length > 0) {
		const rest = names.length - NAMED_PRACTICES;
		const named = names.slice(0, NAMED_PRACTICES).join(", ");
		return rest > 0 ? `${named} +${rest} more` : named;
	}
	switch (saw.status) {
		case "FAILED":
		case "CANCELLED": {
			return "Stopped before it finished";
		}
		case "RUNNING": {
			return "Still running";
		}
		case "QUEUED": {
			return "Waiting to start";
		}
		case "COMPLETE":
		case "PARTIAL": {
			if (saw.results.couldNotDetermine > 0) {
				return "Some practices could not be determined";
			}
			// A partial review may have run to the end, so it names its scope, not a stop.
			return saw.status === "PARTIAL"
				? "Nothing to improve in what it reached"
				: "Nothing to improve";
		}
	}
}

export function reviewReach(saw: ReviewSaw): string {
	const { met, doesNotApply, couldNotDetermine } = saw.results;
	const reached = saw.practicesReached;
	const outcomes = [
		{ n: met, verb: "met" },
		{ n: doesNotApply, verb: "did not apply" },
		{ n: couldNotDetermine, verb: "could not be determined" },
	].filter((outcome) => outcome.n > 0);
	if (outcomes.length === 0 && reached === undefined) {
		return saw.status === "RUNNING" || saw.status === "QUEUED"
			? "Observations appear as it finishes"
			: "";
	}
	const digits = [reached ?? 0, ...outcomes.map((outcome) => outcome.n)].some((n) => n >= 10);
	const decided = outcomes
		.map(
			(outcome, index) =>
				`${index === 0 ? count(outcome.n, "practice", "practices", digits) : spell(outcome.n, digits)} ${outcome.verb}`,
		)
		.join(", ");
	const reach =
		reached === undefined ? "" : `${count(reached, "practice", "practices", digits)} reached`;
	return [decided, reach].filter((clause) => clause !== "").join(" · ");
}
