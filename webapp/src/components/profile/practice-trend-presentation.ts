import type { TrendSupport } from "@/api/types.gen";
import type { TrendDirection } from "@/components/practice-vocabulary/practice-trend-defs";

function reviewedWork(count: number): string {
	return `${count} ${count === 1 ? "piece" : "pieces"} of reviewed work`;
}
export type TrendScope = "practice" | "group";
export function formatTrendProvenance(
	support: TrendSupport,
	direction: TrendDirection,
	scope: TrendScope,
): string {
	const current = support.currentOpportunities;
	const previous = support.previousOpportunities;
	if (current + previous === 0) {
		return "No reviewed work is available yet.";
	}

	const span = support.calendarSpanDays;
	const spanSentence =
		span !== undefined && span > 0 ? ` Evidence spans ${span} ${span === 1 ? "day" : "days"}.` : "";

	if (direction === "INSUFFICIENT_EVIDENCE") {
		const missing = support.opportunitiesUntilComparable;
		const needed =
			missing > 0
				? ` ${missing} more with something to judge ${missing === 1 ? "is" : "are"} needed before a direction can be shown.`
				: "";
		return `Based on ${reviewedWork(current + previous)}.${needed}${spanSentence}`;
	}

	if (scope === "group") {
		const comparable = support.comparablePractices;
		const eligible = support.eligiblePractices;
		const coverage =
			comparable !== undefined && eligible !== undefined
				? ` ${comparable} of ${eligible} ${eligible === 1 ? "practice" : "practices"} here had enough evidence to compare.`
				: "";
		return `Across ${reviewedWork(current + previous)} in this group.${coverage}${spanSentence}`;
	}

	if (previous === 0) {
		return `Based on ${reviewedWork(current)}.${spanSentence}`;
	}
	return `Compared your latest ${reviewedWork(current)} with the ${previous} before ${
		previous === 1 ? "it" : "them"
	}.${spanSentence}`;
}
