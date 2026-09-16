import { Link } from "@tanstack/react-router";
import { WorkflowIcon } from "lucide-react";

import type { AgentJob, Practice, ReviewFeedback, ReviewObservation } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import {
	REVIEW_STATUS_DEFS,
	RESULT_PROCESSING_DEFS,
} from "@/components/practice-vocabulary/review-status-defs";
import { StatusBadge } from "@/components/practice-vocabulary/StatusBadge";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";

import type { RunsSearch } from "./review-search";
import { ReviewArtifactLink } from "./ReviewArtifact";
import { ReviewBreadcrumbs } from "./ReviewBreadcrumbs";
import { ReviewDetailHeader } from "./ReviewDetailHeader";
import { ReviewOutputSections, type ReviewSectionState } from "./ReviewOutputSections";
import { ReviewRunActions } from "./ReviewRunActions";
import { ReviewRunCard } from "./ReviewRunCard";
import { ReviewRunNotices } from "./ReviewRunNotices";

export interface ReviewRunDetailPageProps {
	workspaceSlug: string;
	jobId: string;
	/** The list's search, carried back into the breadcrumb so "Reviews" returns the reader to the
	 * filtered page they came from. */
	search: RunsSearch;
	job: AgentJob | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry: () => void;
	/**
	 * Already-resolved output. A `pending` section is a review still in flight — this screen is told
	 * that as a state, not as a fact about how often anything is re-asked for.
	 */
	observations: ReviewSectionState<ReviewObservation>;
	feedback: ReviewSectionState<ReviewFeedback>;
	/**
	 * The workspace's practices, which each observation row's practice link shows as a hover card.
	 * Optional: the card is the only thing that needs them, and nothing it holds is load-bearing.
	 */
	practices?: Practice[];
	onCancel: () => void;
	cancelPending: boolean;
	onRetryResultProcessing: () => void;
	retryResultProcessingPending: boolean;
}

/** A section that has finished loading and holds nothing, as opposed to one still waiting. */
function isEmptyResult(state: ReviewSectionState<unknown>): boolean {
	return state.status === "ready" && state.items.length === 0;
}

export function ReviewRunDetailPage({
	workspaceSlug,
	jobId,
	search,
	job,
	isLoading,
	error,
	onRetry,
	observations,
	feedback,
	practices,
	onCancel,
	cancelPending,
	onRetryResultProcessing,
	retryResultProcessingPending,
}: ReviewRunDetailPageProps) {
	const breadcrumbs = (
		<ReviewBreadcrumbs
			workspaceSlug={workspaceSlug}
			section={{
				label: "Reviews",
				// The whole search goes back, not a hand-listed subset: "Reviews" has to return the
				// reader to the filtered page they came from, and a filter added to the list would
				// otherwise be dropped here without anything failing.
				link: (
					<Link
						to="/w/$workspaceSlug/admin/practices/reviews"
						params={{ workspaceSlug }}
						search={search}
					/>
				),
			}}
		/>
	);

	if (isLoading)
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				{/* The shape the run resolves into, not a centred spinner in a fixed-height box: that box
				    is a guaranteed jump, because nothing about 16rem matches what arrives. */}
				<div className="space-y-6" aria-hidden>
					<div className="space-y-2">
						<Skeleton className="h-7 w-full max-w-md" />
						<Skeleton className="h-4 w-full max-w-sm" />
					</div>
					<div className="grid gap-3 sm:grid-cols-3">
						{Array.from({ length: 3 }, (_, index) => (
							<Skeleton key={index} className="h-16" />
						))}
					</div>
					<Skeleton className="h-40 w-full" />
				</div>
			</article>
		);
	if (error != null || !job) {
		return (
			<article className="max-w-4xl min-w-0 space-y-8">
				{breadcrumbs}
				<QueryErrorAlert error={error} title="Couldn't load this review" onRetry={onRetry} />
			</article>
		);
	}
	const reviewEndedEarly =
		job.status === "FAILED" || job.status === "TIMED_OUT" || job.status === "CANCELLED";
	const endedWithoutOutput =
		reviewEndedEarly && isEmptyResult(observations) && isEmptyResult(feedback);

	return (
		<article className="max-w-4xl min-w-0 space-y-8">
			{breadcrumbs}
			<ReviewDetailHeader
				chips={
					<>
						<StatusBadge def={REVIEW_STATUS_DEFS[job.status]} />
						{job.deliveryStatus && <StatusBadge def={RESULT_PROCESSING_DEFS[job.deliveryStatus]} />}
					</>
				}
				title={job.target.title}
				provenance={
					<div className="space-y-1">
						<ReviewArtifactLink artifact={job.target} className="text-sm" />
						<p className="text-sm text-muted-foreground">
							{job.startedAt ? "Started " : "Created "}
							<RelativeTime value={job.startedAt ?? job.createdAt} />
						</p>
					</div>
				}
				actions={
					<ReviewRunActions
						job={job}
						isCancelling={cancelPending}
						isRetrying={retryResultProcessingPending}
						onCancel={onCancel}
						onRetry={onRetryResultProcessing}
					/>
				}
			/>
			<div className="space-y-4">
				<ReviewRunNotices
					job={job}
					outputMayBeIncomplete={reviewEndedEarly && !endedWithoutOutput}
				/>
			</div>

			{endedWithoutOutput ? (
				<Empty variant="outlined">
					<EmptyHeader>
						<EmptyMedia variant="icon">
							<WorkflowIcon />
						</EmptyMedia>
						<EmptyTitle>Review couldn't be completed</EmptyTitle>
						<EmptyDescription>
							This review ended before it produced observations or feedback.
						</EmptyDescription>
					</EmptyHeader>
				</Empty>
			) : (
				<ReviewOutputSections
					workspaceSlug={workspaceSlug}
					scope={{ agentJobId: jobId }}
					outcome={job.reviewOutcome}
					feedback={feedback}
					observations={observations}
					practices={practices}
				/>
			)}

			<ReviewRunCard job={job} />
		</article>
	);
}
