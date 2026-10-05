import type { TrendSupport } from "@/api/types.gen";
import { andList, capitalise } from "@/lib/text";

import { count as counted, spell } from "./feedback-text";
import {
	isSettledStanding,
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
	standingDefs,
	type StandingScope,
} from "./practice-group-standing-defs";
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

/** Below this many decided pieces of work, a settled standing is an early read (product vocabulary). */
export const EARLY_READ_BELOW = 3;

/** The server's count of decided pieces a standing is read from; none when no live review counted. */
export function standingWork(support: TrendSupport | undefined): number | undefined {
	const current = support?.currentOpportunities;
	return current !== undefined && current > 0 ? current : undefined;
}

/** "from 3 pieces of work". */
export function formatStandingWork(work: number): string {
	return `from ${counted(work, "piece", "pieces", true)} of work`;
}

/** The registry's sentence with its count, or the early-read sentence below {@link EARLY_READ_BELOW}. */
export function explainStanding(
	standing: PracticeGroupStandingValue,
	scope: StandingScope,
	support: TrendSupport | undefined,
): string {
	const { description } = standingDefs(scope)[standing];
	const work = isSettledStanding(standing) ? standingWork(support) : undefined;
	if (work === undefined) {
		return description;
	}
	const pieces = `${counted(work, "piece", "pieces")} of work`;
	return work < EARLY_READ_BELOW
		? `An early read from ${pieces}.`
		: `${description} Read from ${pieces}.`;
}

/**
 * How a settled practice standing weighs its work. Nothing for one piece, which has nothing to
 * weigh against.
 */
export function formatStandingBasis(support: TrendSupport): string | undefined {
	const current = support.currentOpportunities;
	if (current === 0) {
		return "Read from a review that you asked for or a review of your past work. Neither moves a trend.";
	}
	return current === 1 ? undefined : "Your latest work counts most, and older work counts less.";
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
	return `Of ${counted(total, "practice", "practices", digits)}, ${andList.format(parts)}.`;
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
		return "No new work has been reviewed yet.";
	}

	const span = support.calendarSpanDays;
	const spanSentence =
		span !== undefined && span > 0 ? ` Evidence spans ${counted(span, "day", "days")}.` : "";

	if (direction === "INSUFFICIENT_EVIDENCE") {
		const missing = support.opportunitiesUntilComparable;
		const needed =
			missing > 0
				? ` A direction needs ${spell(missing)} more ${missing === 1 ? "piece" : "pieces"} of reviewed work with something to judge.`
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
