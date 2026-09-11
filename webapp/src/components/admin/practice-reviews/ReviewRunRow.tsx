import { Link } from "@tanstack/react-router";

import type { ReviewRunSummary } from "@/api/types.gen";
import { RelativeTime } from "@/components/common/RelativeTime";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";

import type { RunsSearch } from "./review-search";
import { ReviewArtifactLabel } from "./ReviewArtifact";
import { feedbackCountSlots, observationCountSlots, ReviewCountStrip } from "./ReviewBadges";
import { ReviewRow, ReviewRowMeta } from "./ReviewRow";

export interface ReviewRunRowProps {
	workspaceSlug: string;
	review: ReviewRunSummary;
	/** Carried into the detail link so a reader returns to the list they left, filters intact. */
	search: RunsSearch;
}

/** Named after the work, because a review has no name an operator knows — it has a UUID. */
export function ReviewRunRow({ workspaceSlug, review, search }: ReviewRunRowProps) {
	return (
		<ReviewRow
			status={REVIEW_STATUS_DEFS[review.status]}
			title={
				<Link
					to="/w/$workspaceSlug/admin/practices/reviews/$jobId"
					params={{ workspaceSlug, jobId: review.id }}
					// The detail route validates with this same schema, so the whole search carries and
					// the reader comes back to the list they left, filters intact.
					search={search}
				>
					{review.target.title}
				</Link>
			}
			meta={
				<>
					<ReviewRowMeta
						items={[
							<ReviewArtifactLabel key="work" artifact={review.target} />,
							// See `ObservationRow`: no hover target under a stretched row link.
							<RelativeTime key="created" value={review.createdAt} tooltip={false} />,
						]}
					/>
					<RunOutputSummary review={review} />
				</>
			}
			chips={[
				{
					key: "status",
					width: "lg:w-40",
					node: <StatusBadge def={REVIEW_STATUS_DEFS[review.status]} />,
				},
			]}
		/>
	);
}

function hasObservationOutput(review: ReviewRunSummary) {
	const { strengths, problems, notApplicable, undetermined } = review.observations;
	return strengths + problems + notApplicable + undetermined > 0;
}

function hasFeedbackOutput(review: ReviewRunSummary) {
	const { delivered, failed, prepared, superseded, suppressed } = review.feedback;
	return delivered + failed + prepared + superseded + suppressed > 0;
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
				<ReviewCountStrip label="Observations" slots={observationCountSlots(review.observations)} />
				<ReviewCountStrip label="Feedback" slots={feedbackCountSlots(review.feedback)} />
			</>
		);
	}
	if (review.status === "QUEUED" || review.status === "RUNNING") {
		return <p>Results appear as it finishes.</p>;
	}
	return <p>It produced nothing before it stopped.</p>;
}
