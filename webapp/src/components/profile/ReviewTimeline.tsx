import type { ReactNode } from "react";
import type { ResultList } from "@/api/types.gen";

import { ReviewCard } from "./ReviewCard";
import type { FeedbackResponseControls, ReviewEntry } from "./reviews";
import { TimelineItemSkeleton } from "./TimelineItem";

export interface ReviewTimelineProps {
	reviews: ReviewEntry[];
	/** The reader's response to each piece of feedback, handed to every card. */
	responses?: FeedbackResponseControls;
	/** Route-owned, lazy detail for one observation. */
	renderObservation?: (result: ResultList) => ReactNode;
	/** Off on a practice's own level, where every row is that practice and no row repeats it. */
	showPracticeName?: boolean;
	/**
	 * Whether a result arrives open; without it every one does, a feed the reader skims for what it
	 * found. A practice's own level opens one row by its rule (`practice-profile/observation-filter.ts`).
	 */
	openOnArrival?: (result: ResultList) => boolean;
	/** The observation a link asked for, which its row marks as selected. */
	selectedResultId?: string;
	/** True while the search narrows the history: every observation shown matched it and wears the bar. */
	marksSearchMatches?: boolean;
	/** True while earlier reviews exist below the fold: the rail then ends in a dashed tail. */
	continues?: boolean;
	/** Placeholder cards after the last review while the next page loads; the list is then busy. */
	loadingMore?: number;
}

export function ReviewTimeline({
	reviews,
	responses,
	renderObservation,
	showPracticeName = true,
	openOnArrival,
	selectedResultId,
	marksSearchMatches = false,
	continues = false,
	loadingMore = 0,
}: ReviewTimelineProps) {
	return (
		<ol
			className="flex w-full min-w-0 flex-col"
			aria-label="Reviews"
			aria-busy={loadingMore > 0 || undefined}
		>
			{reviews.map((review, index) => (
				<ReviewCard
					key={review.review.id}
					review={review}
					responses={responses}
					renderObservation={renderObservation}
					showPracticeName={showPracticeName}
					openOnArrival={openOnArrival}
					selectedResultId={selectedResultId}
					marksSearchMatches={marksSearchMatches}
					tailContinues={continues && index === reviews.length - 1}
				/>
			))}
			{Array.from({ length: loadingMore }, (_, index) => (
				<TimelineItemSkeleton key={index} />
			))}
		</ol>
	);
}
