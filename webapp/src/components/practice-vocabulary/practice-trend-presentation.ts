import type { TrendSupport } from "@/api/types.gen";
import { itemKindNoun, type ItemProvider } from "@/lib/item-kinds";

import { count as counted, spell } from "./feedback-text";
import {
	isSettledStanding,
	type PracticeGroupStandingValue,
	standingDefs,
	type StandingScope,
} from "./practice-group-standing-defs";
import { TREND_BUNDLE, type TrendDirection } from "./practice-trend-defs";

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
 * How far back the profile reads a trend and a standing, in days: the server's one look-back
 * (`PracticeTrendService.LOOKBACK_DAYS`, which `OverviewWindow.LOOKBACK` takes for the standing).
 */
export const LOOKBACK_DAYS = 90;

/**
 * How a practice's trend compares, in one plain sentence after its direction: the last bundle of
 * reviewed work against the same amount just before it, the baseline, which moves with the work
 * (`TrendProperties` `bundleSize`). A chip's words alone ("No baseline yet") do not say they are a
 * trend.
 */
export const TREND_EXPLAINED = `Compares your last ${TREND_BUNDLE} reviewed pieces of work with the ${TREND_BUNDLE} before them.`;

/**
 * The same for a group, without a number: the server pools the group's practices, each over its own
 * work (`GroupTrendAggregator`).
 */
export const GROUP_TREND_EXPLAINED =
	"Compares, practice by practice, your recent reviewed work in this group with the work before it.";

/**
 * The comparison sentence a trend's tooltip ends with: the practice's own counts where the trend has
 * them and they differ from the default bundle, and what is still missing where there is no
 * direction yet (nothing where that is unknown). No day spans and no practice coverage: the tooltip stays two short sentences.
 */
export function trendComparison(
	direction: TrendDirection,
	scope: StandingScope,
	support: TrendSupport | undefined,
): string {
	const missing = support?.opportunitiesUntilComparable ?? 0;
	if (direction === "INSUFFICIENT_EVIDENCE") {
		// The direction sentence already says what a trend needs; only a known gap adds to it.
		return missing > 0
			? `Needs ${spell(missing)} more reviewed ${missing === 1 ? "piece" : "pieces"} of work.`
			: "";
	}
	if (scope === "group") {
		return GROUP_TREND_EXPLAINED;
	}
	const current = support?.currentOpportunities ?? 0;
	const previous = support?.previousOpportunities ?? 0;
	if (current === 0 || previous === 0) {
		return TREND_EXPLAINED;
	}
	const digits = Math.max(current, previous) >= 10;
	return `Compares your last ${counted(current, "reviewed piece", "reviewed pieces", digits)} of work with the ${spell(previous, digits)} before ${
		previous === 1 ? "it" : "them"
	}.`;
}

/** Below this many decided pieces of work, a settled standing is an early read (product vocabulary). */
export const EARLY_READ_BELOW = 3;

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
	const window = `in the last ${LOOKBACK_DAYS} days`;

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
	const total = `In the last ${LOOKBACK_DAYS} days, ${spell(reviewed, digits)} ${noun(reviewed)} ${
		reviewed === 1 ? "was" : "were"
	} reviewed for ${subject}.`;
	return work < EARLY_READ_BELOW
		? `An early read from ${latest}. ${total}`
		: `${description} Read from ${latest}. ${total}`;
}
