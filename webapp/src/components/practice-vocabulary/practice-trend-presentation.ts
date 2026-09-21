import type { TrendSupport } from "@/api/types.gen";
import { count as counted, spell } from "@/components/common/feedback-text";
import { capitalise } from "@/lib/text";

import {
	PRACTICE_GROUP_STANDING_DEFS,
	type PracticeGroupStandingValue,
	type StandingScope,
} from "./practice-group-standing-defs";
import type { TrendDirection } from "./practice-trend-defs";
import type { StandingCounts } from "./PracticeGroupStandingRing";
import { statusValues } from "./status-def";

/** "four pieces of reviewed work", under the number rule of `feedback-text`. */
function reviewedWork(count: number, digits?: boolean): string {
	return `${counted(count, "piece", "pieces", digits)} of reviewed work`;
}

/** A trend is read for the same subject as its standing. */
export type TrendScope = StandingScope;

/**
 * What a practice's standing rests on: the newest stretch of reviewed work the trend compares,
 * which is the window the standing is read from. Nothing to name when no work reached it.
 */
export function formatStandingBasis(support: TrendSupport): string | undefined {
	const current = support.currentOpportunities;
	if (current === 0) return undefined;
	return current === 1
		? "Based on your latest piece of reviewed work."
		: `Based on your latest ${reviewedWork(current)}.`;
}

/** Each standing as a predicate of "n practices", so the basis reads as one sentence. */
const STANDING_PREDICATES: Record<PracticeGroupStandingValue, { one: string; many: string }> = {
	DEVELOPING: { one: "needs attention", many: "need attention" },
	MIXED: { one: "shows mixed feedback", many: "show mixed feedback" },
	STRENGTH: { one: "is going well", many: "are going well" },
	NO_OPPORTUNITY: { one: "has nothing to report", many: "have nothing to report" },
	NOT_OBSERVED: { one: "is not observed yet", many: "are not observed yet" },
};

/**
 * What a group's standing rests on: its practices counted by standing, in the registry's order —
 * "Of five practices, two need attention, one shows mixed feedback and two are going well." — the
 * same counts the ring beside the title draws. Nothing to name for a group with no practices.
 */
export function formatGroupStandingBasis(counts: StandingCounts): string | undefined {
	const present = statusValues(PRACTICE_GROUP_STANDING_DEFS).flatMap((standing) => {
		const n = counts[standing] ?? 0;
		return n > 0 ? [{ standing, n }] : [];
	});
	const total = present.reduce((sum, { n }) => sum + n, 0);
	if (total === 0) return undefined;
	const digits = total >= 10;
	const parts = present.map(({ standing, n }) => {
		const { one, many } = STANDING_PREDICATES[standing];
		return counted(n, one, many, digits);
	});
	const last = parts.pop();
	const joined = parts.length > 0 ? `${parts.join(", ")} and ${last}` : last;
	return `Of ${counted(total, "practice", "practices", digits)}, ${joined}.`;
}

export function formatTrendProvenance(
	support: TrendSupport,
	direction: TrendDirection,
	scope: TrendScope,
): string {
	const current = support.currentOpportunities;
	const previous = support.previousOpportunities;
	if (current + previous === 0) return "No reviewed work is available yet.";

	const span = support.calendarSpanDays;
	const spanSentence = span ? ` Evidence spans ${counted(span, "day", "days")}.` : "";

	if (direction === "INSUFFICIENT_EVIDENCE") {
		const missing = support.opportunitiesUntilComparable;
		const needed =
			missing > 0
				? ` ${capitalise(spell(missing))} more with something to judge ${missing === 1 ? "is" : "are"} needed before a direction can be shown.`
				: "";
		return `Based on ${reviewedWork(current + previous)}.${needed}${spanSentence}`;
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
		return `Across ${reviewedWork(current + previous)} in this group.${coverage}${spanSentence}`;
	}

	if (previous === 0) {
		return `Based on ${reviewedWork(current)}.${spanSentence}`;
	}
	// One clause with two counts: both digits as soon as either reaches ten.
	const digits = Math.max(current, previous) >= 10;
	return `Compared your latest ${reviewedWork(current, digits)} with the ${spell(previous, digits)} before ${
		previous === 1 ? "it" : "them"
	}.${spanSentence}`;
}
