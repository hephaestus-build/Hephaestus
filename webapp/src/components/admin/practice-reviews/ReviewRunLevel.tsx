import { Link } from "@tanstack/react-router";
import { WorkflowIcon } from "lucide-react";

import type {
	AgentJob,
	Practice,
	ReviewFeedback,
	ReviewObservation,
	ReviewPrecompute,
} from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { RelativeTime } from "@/components/common/RelativeTime";
import { StatusBadge } from "@/components/common/StatusBadge";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import { Section } from "@/components/layout/Section";
import {
	RESULT_PROCESSING_DEFS,
	reviewStatusDef,
} from "@/components/practice-vocabulary/review-status-defs";
import { DrawerBody, DrawerFooter } from "@/components/ui/drawer";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Skeleton } from "@/components/ui/skeleton";

import { isCancellable, isResultProcessingRetryable } from "./job-utils";
import type { ReviewSectionState } from "./review-states";
import { ReviewArtifactLink } from "./ReviewArtifact";
import { ReviewLevelHeader } from "./ReviewLevelHeader";
import { ReviewOutputSections } from "./ReviewOutputSections";
import { ReviewPrecomputeTable } from "./ReviewPrecomputeTable";
import { ReviewRunActions } from "./ReviewRunActions";
import { ReviewRunCard } from "./ReviewRunCard";
import { ReviewRunNotices } from "./ReviewRunNotices";

export interface ReviewRunLevelProps {
	workspaceSlug: string;
	nested?: boolean;
	path: LevelPath;
	job: PanelState<{ job: AgentJob }>;
	/**
	 * Already-resolved output. A `pending` section is a review still in flight — this level is told
	 * that as a state, not as a fact about how often anything is re-asked for.
	 */
	observations: ReviewSectionState<ReviewObservation>;
	feedback: ReviewSectionState<ReviewFeedback>;
	/** What each practice's precompute script did before the review; the section shows only with one. */
	precompute: PanelState<{ scripts: ReviewPrecompute[] }>;
	/** The workspace's practices, for the hover card on each observation's practice. */
	practices: Practice[] | undefined;
	onCancel: () => void;
	cancelPending: boolean;
	onRetryResultProcessing: () => void;
	retryResultProcessingPending: boolean;
}

/** A section that has finished loading and holds nothing, as opposed to one still waiting. */
function isEmptyResult(state: ReviewSectionState<unknown>): boolean {
	return state.status === "ready" && state.items.length === 0;
}

/**
 * One practice review: what it reviewed, how it ended, what it observed and what feedback it
 * composed, what the precompute scripts did before it, then how it ran. Its only actions — cancel a running review, retry the processing of its
 * results — are the footer, and only when one applies.
 */
export function ReviewRunLevel({
	workspaceSlug,
	nested,
	path,
	job: jobState,
	observations,
	feedback,
	precompute,
	practices,
	onCancel,
	cancelPending,
	onRetryResultProcessing,
	retryResultProcessingPending,
}: ReviewRunLevelProps) {
	const job = jobState.status === "ready" ? jobState.job : undefined;
	const header = (
		<ReviewLevelHeader
			nested={nested}
			path={path}
			kind="review"
			loading={jobState.status === "loading"}
			chips={
				job && (
					<>
						<StatusBadge def={reviewStatusDef(job)} />
						{job.deliveryStatus && <StatusBadge def={RESULT_PROCESSING_DEFS[job.deliveryStatus]} />}
					</>
				)
			}
			title={job?.target.title}
			description={
				job && (
					<>
						<ReviewArtifactLink reviewedWork={job.target.reviewedWork} />
						<p>
							{job.startedAt ? "Started " : "Requested "}
							<RelativeTime value={job.startedAt ?? job.createdAt} />
						</p>
					</>
				)
			}
		/>
	);

	if (jobState.status !== "ready") {
		return (
			<>
				{header}
				<DrawerBody className="pt-2">
					{jobState.status === "error" ? (
						<QueryErrorAlert
							error={jobState.error}
							title="We could not load this review"
							onRetry={jobState.onRetry}
						/>
					) : (
						<ReviewRunSkeleton />
					)}
				</DrawerBody>
			</>
		);
	}

	const { job: run } = jobState;
	const reviewEndedEarly =
		run.status === "FAILED" || run.status === "TIMED_OUT" || run.status === "CANCELLED";
	const endedWithoutOutput =
		reviewEndedEarly && isEmptyResult(observations) && isEmptyResult(feedback);
	const hasActions = isCancellable(run.status) || isResultProcessingRetryable(run);

	return (
		<>
			{header}
			<DrawerBody className="flex flex-col gap-8 pt-2">
				<ReviewRunNotices
					job={run}
					practices={practices}
					outputMayBeIncomplete={reviewEndedEarly && !endedWithoutOutput}
				/>
				{endedWithoutOutput ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<WorkflowIcon />
							</EmptyMedia>
							<EmptyTitle>We could not complete this review</EmptyTitle>
							<EmptyDescription>
								This review ended before it produced observations or feedback.
							</EmptyDescription>
						</EmptyHeader>
					</Empty>
				) : (
					<ReviewOutputSections
						workspaceSlug={workspaceSlug}
						scope={{ agentJobId: run.id }}
						outcome={run.reviewOutcome}
						feedback={feedback}
						observations={observations}
						practices={practices}
					/>
				)}
				<PrecomputeScripts workspaceSlug={workspaceSlug} state={precompute} />
				<ReviewRunCard job={run} />
			</DrawerBody>
			{hasActions && (
				<DrawerFooter>
					<ReviewRunActions
						job={run}
						isCancelling={cancelPending}
						isRetrying={retryResultProcessingPending}
						onCancel={onCancel}
						onRetry={onRetryResultProcessing}
					/>
				</DrawerFooter>
			)}
		</>
	);
}

/**
 * Absent while it loads and when no script ran: a skeleton for a section that may not exist would
 * hold space that the run card then jumps into.
 */
function PrecomputeScripts({
	workspaceSlug,
	state,
}: {
	workspaceSlug: string;
	state: ReviewRunLevelProps["precompute"];
}) {
	if (state.status === "loading" || (state.status === "ready" && state.scripts.length === 0)) {
		return null;
	}
	return (
		<Section
			level={3}
			title="Precompute scripts"
			description="What each practice’s script did before this review."
		>
			{state.status === "error" ? (
				<QueryErrorAlert
					error={state.error}
					title="We could not load what the precompute scripts did"
					onRetry={state.onRetry}
				/>
			) : (
				<>
					<ReviewPrecomputeTable workspaceSlug={workspaceSlug} scripts={state.scripts} />
					<p className="text-xs text-muted-foreground">
						Spend is on{" "}
						<InlineLink
							render={<Link to="/w/$workspaceSlug/admin/usage" params={{ workspaceSlug }} />}
						>
							AI usage
						</InlineLink>
						.
					</p>
				</>
			)}
		</Section>
	);
}

/** The shape the run resolves into: its notices, two short lists and the run card. */
function ReviewRunSkeleton() {
	return (
		<div className="space-y-6" aria-hidden>
			<Skeleton className="h-5 w-full max-w-sm" />
			<div className="space-y-3">
				<Skeleton className="h-6 w-40" />
				<Skeleton className="h-32 w-full" />
			</div>
			<Skeleton className="h-40 w-full" />
		</div>
	);
}
