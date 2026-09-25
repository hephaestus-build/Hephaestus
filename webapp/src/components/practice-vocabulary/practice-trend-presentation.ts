import type { TrendSupport } from "@/api/types.gen";
import { capitalise } from "@/lib/text";

import { count as counted, spell } from "./feedback-text";
import { PRACTICE_GROUP_STANDING_DEFS, type StandingScope } from "./practice-group-standing-defs";
import type { TrendDirection } from "./practice-trend-defs";
import { type StandingCounts, summarizeStandingCounts } from "./standing-counts";

/** "four pieces of reviewed work", under the number rule of `feedback-text`. */
function reviewedWork(count: number, digits?: boolean): string {
	return `${counted(count, "piece", "pieces", digits)} of reviewed work`;
}

/**
 * The direction a surface shows: one with no evidence behind it, or none at all, is "not enough to
 * compare yet", so a chip and the sentence beside it can never say different things.
 */
export function shownTrendDirection(
	direction: TrendDirection | undefined,
	support: TrendSupport | undefined,
): TrendDirection {
	return direction !== undefined && support !== undefined ? direction : "INSUFFICIENT_EVIDENCE";
}

/**
 * What a practice's standing rests on: the newest stretch of reviewed work the trend compares,
 * which is the window the standing is read from. Nothing to name when no work reached it.
 */
export function formatStandingBasis(support: TrendSupport): string | undefined {
	const current = support.currentOpportunities;
	if (current === 0) {
		return undefined;
	}
	return current === 1
		? "Based on your latest piece of reviewed work."
		: `Based on your latest ${reviewedWork(current)}.`;
}

/**
 * What a group's standing rests on: its practices counted by standing, in the registry's order —
 * "Of five practices, two need attention, one shows mixed feedback and two are going well." — the
 * same counts the ring beside the title draws. Nothing to name for a group with no practices.
 */
export function formatGroupStandingBasis(counts: StandingCounts): string | undefined {
	const present = summarizeStandingCounts(counts);
	const total = present.reduce((sum, { count }) => sum + count, 0);
	if (total === 0) {
		return undefined;
	}
	const digits = total >= 10;
	const parts = present.map(({ standing, count }) => {
		const { one, many } = PRACTICE_GROUP_STANDING_DEFS[standing].predicate;
		return counted(count, one, many, digits);
	});
	const last = parts.pop();
	const joined = parts.length > 0 ? `${parts.join(", ")} and ${last}` : last;
	return `Of ${counted(total, "practice", "practices", digits)}, ${joined}.`;
}

export function formatTrendProvenance(
	support: TrendSupport,
	direction: TrendDirection,
	scope: StandingScope,
): string {
	const current = support.currentOpportunities;
	const previous = support.previousOpportunities;
	// Not the sum of the bundles: a group's practices can bundle one piece of work two ways, and the
	// wire already counts it once.
	const { opportunities } = support;
	if (opportunities === 0) {
		return "No reviewed work is available yet.";
	}

	const span = support.calendarSpanDays;
	const spanSentence =
		span !== undefined && span > 0 ? ` Evidence spans ${counted(span, "day", "days")}.` : "";

	if (direction === "INSUFFICIENT_EVIDENCE") {
		const missing = support.opportunitiesUntilComparable;
		const needed =
			missing > 0
				? ` ${capitalise(spell(missing))} more with something to judge ${missing === 1 ? "is" : "are"} needed before a direction can be shown.`
				: "";
		return `Based on ${reviewedWork(opportunities)}.${needed}${spanSentence}`;
	}

	if (scope === "group") {
		const comparable = support.comparablePractices;
		const eligible = support.eligiblePractices;
		// One clause with two counts: both digits as soon as either reaches ten.
		const digits =
			comparable !== undefined && eligible !== undefined && Math.max(comparable, eligible) >= 10;
		const coverage =
			comparable !== undefined && eligible !== undefined
				? ` ${capitalise(spell(comparable, digits))} of ${counted(eligible, "practice", "practices", digits)} here had enough evidence to compare.`
				: "";
		return `Across ${reviewedWork(opportunities)} in this group.${coverage}${spanSentence}`;
	}

	if (previous === 0) {
		return `Based on ${reviewedWork(current)}.${spanSentence}`;
	}
	const digits = Math.max(current, previous) >= 10;
	return `Compared your latest ${reviewedWork(current, digits)} with the ${spell(previous, digits)} before ${
		previous === 1 ? "it" : "them"
	}.${spanSentence}`;
}
