import type { TrendSupport } from "@/api/types.gen";

import type { TREND } from "./vocabulary";

export type TrendDirection = keyof typeof TREND;

function reviewedWork(count: number): string {
	return `${count} ${count === 1 ? "piece" : "pieces"} of reviewed work`;
}

/**
 * Where a direction comes from, in one or two sentences: how much reviewed work it rests on, what was
 * compared, and over how long. It never claims a comparison the server did not make. It follows the
 * web's `practice-trend-presentation.ts`, except that what is still needed is "relevant reviewed work"
 * rather than work "with something to judge", which read as a verdict on the developer.
 */
export function trendProvenance(
	support: TrendSupport,
	direction: TrendDirection,
	scope: "practice" | "group",
): string {
	const current = support.currentOpportunities;
	const previous = support.previousOpportunities;
	if (support.opportunities === 0) {
		return "No reviewed work is available yet.";
	}

	const span = support.calendarSpanDays;
	const spanSentence =
		span !== undefined && span > 0 ? ` Evidence spans ${span} ${span === 1 ? "day" : "days"}.` : "";

	if (direction === "INSUFFICIENT_EVIDENCE") {
		const missing = support.opportunitiesUntilComparable;
		const needed =
			missing > 0
				? ` ${missing} more ${missing === 1 ? "piece" : "pieces"} of relevant reviewed work ${missing === 1 ? "is" : "are"} needed before a direction can be shown.`
				: "";
		return `Based on ${reviewedWork(support.opportunities)}.${needed}${spanSentence}`;
	}

	if (scope === "group") {
		const comparable = support.comparablePractices;
		const eligible = support.eligiblePractices;
		const coverage =
			comparable !== undefined && eligible !== undefined
				? ` ${comparable} of ${eligible} ${eligible === 1 ? "practice" : "practices"} here had enough evidence to compare.`
				: "";
		return `Across ${reviewedWork(support.opportunities)} in this group.${coverage}${spanSentence}`;
	}

	if (previous === 0) {
		return `Based on ${reviewedWork(current)}.${spanSentence}`;
	}
	return `Compared your latest ${reviewedWork(current)} with the ${previous} before ${
		previous === 1 ? "it" : "them"
	}.${spanSentence}`;
}
