import type { PracticeEvidenceOutcome } from "@/api/types.gen";

export type EvidenceBlocker = PracticeEvidenceOutcome["blockersObserved"][number];

export interface OutcomeSpec {
	practiceSlug: string;
	/** Reviews that got as far as asking this practice's requirements. */
	considered: number;
	/** How many of those the requirements turned away. Defaults to none. */
	skipped?: number;
	/** Counted per source, so one skipped review can appear in several and they need not sum. */
	blockers?: EvidenceBlocker[];
}

/**
 * The wire carries `consideredReviews` and `reviewedCount`, so a hand-built outcome can claim more
 * reviews ran than were considered and look plausible. A spec declares the reviews *skipped*, the
 * rest is derived, and an impossible fixture throws rather than rendering.
 */
export function outcome({
	practiceSlug,
	considered,
	skipped = 0,
	blockers = [],
}: OutcomeSpec): PracticeEvidenceOutcome {
	if (skipped > considered) {
		throw new Error(
			`${practiceSlug}: ${skipped} skipped out of ${considered} considered is not a state the server can report.`,
		);
	}
	return {
		practiceSlug,
		consideredReviews: considered,
		reviewedCount: considered - skipped,
		blockersObserved: blockers,
	};
}
