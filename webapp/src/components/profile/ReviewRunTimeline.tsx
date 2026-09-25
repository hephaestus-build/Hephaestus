import type { PracticeGroupReviewRun } from "@/api/types.gen";

import type { ObservationControls } from "./review-runs";
import { ReviewRunCard } from "./ReviewRunCard";

export interface ReviewRunTimelineProps {
	runs: PracticeGroupReviewRun[];
	/** The reader's response to an observation, handed to every card. */
	observations?: ObservationControls;
	/** Off on a practice's own level, where every row is that practice and no row repeats it. */
	showPracticeName?: boolean;
	/**
	 * Which observations arrive open. `"all"` is a feed the reader skims for what it found;
	 * `"newest"` opens the newest run's first row alone and leaves every earlier one to a press,
	 * which is what a practice's own level wants — its rows are all the same practice, so the
	 * latest is the one the reader came for and the rest are its history.
	 */
	initiallyOpen?: "all" | "newest";
	/** True while earlier runs exist below the fold: the rail then ends in a dashed tail. */
	continues?: boolean;
}

export function ReviewRunTimeline({
	runs,
	observations,
	showPracticeName = true,
	initiallyOpen = "all",
	continues = false,
}: ReviewRunTimelineProps) {
	return (
		<ol className="flex min-w-0 flex-col" aria-label="Review runs">
			{runs.map((run, index) => (
				<ReviewRunCard
					key={run.reviewId}
					run={run}
					observations={observations}
					showPracticeName={showPracticeName}
					initiallyOpen={cardInitiallyOpen(initiallyOpen, index)}
					tailContinues={continues && index === runs.length - 1}
				/>
			))}
		</ol>
	);
}

/** Every card open, or only the newest card's first observation. */
function cardInitiallyOpen(
	initiallyOpen: "all" | "newest",
	index: number,
): "all" | "first" | "none" {
	if (initiallyOpen === "all") {
		return "all";
	}
	return index === 0 ? "first" : "none";
}
