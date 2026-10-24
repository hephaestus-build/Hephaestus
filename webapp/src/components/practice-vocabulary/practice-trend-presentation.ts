import type { TrendSupport } from "@/api/types.gen";
import { itemKindNoun, type ItemProvider } from "@/lib/item-kinds";
import { capitalise } from "@/lib/text";

import { count as counted, spell } from "./feedback-text";
import {
	isSettledStanding,
	type PracticeGroupStandingValue,
	standingDefs,
	type StandingScope,
} from "./practice-group-standing-defs";
import type { TrendDirection } from "./practice-trend-defs";

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
 * How far back a trend reads, in days: the server's look-back (`PracticeTrendService.LOOKBACK_DAYS`).
 */
export const TREND_LOOKBACK_DAYS = 90;

/**
 * What every trend is, said before its own evidence: the latest reviewed work against the work
 * before it. A chip's words alone ("Not enough to compare yet") do not say they are a trend.
 */
export const TREND_EXPLAINED = `The trend compares your latest reviewed work with the work before it, in the last ${TREND_LOOKBACK_DAYS} days.`;

/** Below this many decided pieces of work, a settled standing is an early read (product vocabulary). */
export const EARLY_READ_BELOW = 3;

/**
 * How far back the profile reads a standing, in days: the server's look-back
 * (`PracticeStandingService.LOOKBACK_DAYS`), which bounds the reviewed work a standing counts.
 */
export const STANDING_LOOKBACK_DAYS = 90;

/**
 * The server's count of the reviewed work a standing is read from: each practice's newest decided
 * pieces, once each; none when no live review counted any.
 */
export function standingWork(support: TrendSupport | undefined): number | undefined {
	const work = support?.standingWork;
	return work !== undefined && work > 0 ? work : undefined;
}

/** "the only", "the two", "all four": every piece of work reviewed, as one count. */
function allWork(work: number): string {
	if (work === 1) {
		return "the only";
	}
	return work < EARLY_READ_BELOW ? `the ${spell(work)}` : `all ${spell(work)}`;
}

/** Work of several kinds, or of a kind the wire does not name, as one noun. */
const ANY_WORK = { one: "piece of work", many: "pieces of work" };

export function explainStanding(
	standing: PracticeGroupStandingValue,
	scope: StandingScope,
	support: TrendSupport | undefined,
	provider?: ItemProvider,
): string {
	const { description } = standingDefs(scope)[standing];
	const work = isSettledStanding(standing) ? standingWork(support) : undefined;
	if (work === undefined || support === undefined) {
		return description;
	}
	const reviewed = Math.max(support.reviewedWork, work);
	const kind = support.reviewedWorkKind;
	const noun = (n: number) => {
		if (kind !== null) {
			return itemKindNoun(kind, n, provider);
		}
		return n === 1 ? ANY_WORK.one : ANY_WORK.many;
	};
	const subject = scope === "group" ? "this group" : "this practice";
	const window = `in the last ${STANDING_LOOKBACK_DAYS} days`;

	if (reviewed === work) {
		const all = allWork(work);
		const read = `${all} ${noun(work)} reviewed for ${subject} ${window}.`;
		return work < EARLY_READ_BELOW
			? `An early read from ${read}`
			: `${description} Read from ${read}`;
	}

	// Two counts in one sentence pair: both digits as soon as either reaches ten.
	const digits = reviewed >= 10;
	const latest =
		scope === "group"
			? `the last reviewed ${noun(2)} of each practice here, ${spell(work, digits)} in all`
			: `your last ${spell(work, digits)} reviewed ${noun(work)}`;
	const total = `In the last ${STANDING_LOOKBACK_DAYS} days, ${spell(reviewed, digits)} ${noun(reviewed)} ${
		reviewed === 1 ? "was" : "were"
	} reviewed for ${subject}.`;
	return work < EARLY_READ_BELOW
		? `An early read from ${latest}. ${total}`
		: `${description} Read from ${latest}. ${total}`;
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
		span !== null && span > 0 ? ` Evidence spans ${counted(span, "day", "days")}.` : "";

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
		const digits = comparable !== null && eligible !== null && Math.max(comparable, eligible) >= 10;
		const coverage =
			comparable !== null && eligible !== null
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
