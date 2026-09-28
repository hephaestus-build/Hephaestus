import type { ReviewRunSummary } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import {
	RESULT_PROCESSING_DEFS,
	REVIEW_STATUS_DEFS,
} from "@/components/practice-vocabulary/review-status-defs";

import { reviewLevel } from "./review-levels";
import { feedbackSlots, observationSlots, slotsTotal } from "./review-outcomes";
import { ReviewArtifactLabel } from "./ReviewArtifact";
import { FeedbackCountsSummary, ReviewCountStrip } from "./ReviewBadges";
import { ReviewRow, ReviewRowLink, ReviewRowMeta } from "./ReviewRow";

export interface ReviewRunRowProps {
	review: ReviewRunSummary;
}

/**
 * Named after the work, because a review has no name an operator knows — it has a UUID. The leading
 * icon is the review's status; the one qualifier is a failure to process what it produced, which the
 * status alone would report as a review that simply completed.
 */
export function ReviewRunRow({ review }: ReviewRunRowProps) {
	const entry = reviewLevel(review.id);
	return (
		<ReviewRow
			status={REVIEW_STATUS_DEFS[review.status]}
			title={<ReviewRowLink entry={entry}>{review.target.title}</ReviewRowLink>}
			meta={
				<>
					<ReviewRowMeta
						items={[
							<ReviewArtifactLabel key="work" reviewedWork={review.target.reviewedWork} />,
							// See `ObservationRow`: no hover target under a stretched row link.
							<RelativeTime key="created" value={review.createdAt} tooltip={false} />,
						]}
					/>
					<RunOutputSummary review={review} />
				</>
			}
			chips={
				review.resultProcessing === "FAILED"
					? [{ key: "processing", node: <StatusBadge def={RESULT_PROCESSING_DEFS.FAILED} /> }]
					: undefined
			}
		/>
	);
}

function hasObservationOutput(review: ReviewRunSummary) {
	return slotsTotal(observationSlots(review.observations)) > 0;
}

function hasFeedbackOutput(review: ReviewRunSummary) {
	return slotsTotal(feedbackSlots(review.feedback)) > 0;
}

/**
 * A run still going has an empty tally that means "not yet", and one that stopped has an empty tally
 * that means "never". A strip of zeroes for both would make the first read as a finished review that
 * found nothing, so neither gets one.
 */
function RunOutputSummary({ review }: { review: ReviewRunSummary }) {
	if (review.status === "COMPLETED" || hasObservationOutput(review) || hasFeedbackOutput(review)) {
		return (
			<>
				<ReviewCountStrip label="Observations" slots={observationSlots(review.observations)} />
				<p>
					<FeedbackCountsSummary counts={review.feedback} prefix="Feedback:" />
				</p>
			</>
		);
	}
	if (review.status === "QUEUED" || review.status === "RUNNING") {
		return <p>Results appear as it finishes.</p>;
	}
	return <p>It produced nothing before it stopped.</p>;
}
