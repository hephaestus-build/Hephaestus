import type { ObservationDetail, PracticeStanding, TrendSupport } from "@/api/types.gen";

const NOTE = "A review that you requested is evidence only and does not change your standing.";

/**
 * The sentence for a standing that reads "Needs attention" while the newest review of the practice
 * is one the reader requested and found no problem. It needs live reviews behind the standing: one
 * read from requested reviews alone does move with them. `trendSupport.opportunities` counts the
 * live work that the trend reads.
 *
 * @param runs the practice's own reviews, newest first
 */
export function requestedReviewNote(
	practice: Pick<PracticeStanding, "standing"> & {
		trendSupport?: Pick<TrendSupport, "opportunities">;
	},
	runs: readonly { observations: readonly Pick<ObservationDetail, "origin" | "outcome">[] }[],
): string | undefined {
	const newest = runs[0]?.observations ?? [];
	const requestedAndClean =
		newest.length > 0 &&
		newest.every((observation) => observation.origin === "MANUAL" && observation.outcome === "MET");
	const liveJudged = (practice.trendSupport?.opportunities ?? 0) > 0;
	return practice.standing === "DEVELOPING" && liveJudged && requestedAndClean ? NOTE : undefined;
}
