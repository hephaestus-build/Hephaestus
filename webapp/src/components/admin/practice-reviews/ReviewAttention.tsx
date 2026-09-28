import { CircleCheckIcon, ClipboardCheckIcon } from "lucide-react";
import { Fragment, type ReactNode } from "react";

import { cn } from "cn";
import type { ReviewFeedback } from "@/api/types.gen";
import { STALE } from "@/components/activity/activity-tones";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { statusToneClass } from "@/components/common/status-def";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Section } from "@/components/layout/Section";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { buttonVariants } from "@/components/ui/button";

import { FeedbackRow } from "./FeedbackResults";
import { feedbackLevel } from "./review-levels";
import type { ReviewListTarget } from "./review-outcomes";
import type { ReviewCountState, ReviewSectionState } from "./review-states";
import { ReviewListLink } from "./ReviewListLink";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";

interface AttentionGroup<TState> {
	state: TState;
	/** Every row the group counts. */
	list: ReviewListTarget;
}

export interface ReviewAttentionProps {
	workspaceSlug: string;
	/**
	 * Feedback awaiting approval, oldest first and whenever it was composed: a decision owed has no
	 * range, and the one waiting longest is the one to take next.
	 */
	approvals: AttentionGroup<ReviewSectionState<ReviewFeedback>>;
	/** Reviews that failed or timed out in the range. */
	failedReviews: AttentionGroup<ReviewCountState>;
	/**
	 * Reviews in the range whose results could not be processed or delivered: a review whose
	 * automatic delivery failed is one of these as well as having feedback that failed to deliver.
	 */
	unprocessedResults: AttentionGroup<ReviewCountState>;
	/** Feedback in the range whose delivery failed, in full or in part. */
	failedDeliveries: AttentionGroup<ReviewCountState>;
	/** "the last 30 days": what the failures were counted over, for their line and the all-clear. */
	rangeInSentence: string;
}

interface ProblemDef {
	key: "failedReviews" | "unprocessedResults" | "failedDeliveries";
	phrase: (count: number) => string;
	/** For the error alert: "Couldn't load failed reviews". */
	what: string;
}

const PROBLEMS: readonly ProblemDef[] = [
	{
		key: "failedReviews",
		phrase: (count) => `${count} ${count === 1 ? "review" : "reviews"} failed or timed out`,
		what: "failed reviews",
	},
	{
		key: "unprocessedResults",
		phrase: (count) =>
			`${count} ${count === 1 ? "review's" : "reviews'"} results could not be processed or delivered`,
		what: "reviews whose results could not be processed or delivered",
	},
	{
		key: "failedDeliveries",
		phrase: (count) => `${count} ${count === 1 ? "piece" : "pieces"} of feedback failed to deliver`,
		what: "feedback that failed to deliver",
	},
];

/**
 * Only what asks something of an admin. Decisions come first, as rows with a way to work through
 * them oldest first; what broke in the range follows as one line of totals, each opening its list,
 * because a failure is a reason to look rather than a row to act on here. With nothing owed, one
 * line says so, so an empty section never reads as broken — but only once every part has answered
 * for the range on screen.
 */
export function ReviewAttention({
	workspaceSlug,
	approvals,
	rangeInSentence,
	...problemGroups
}: ReviewAttentionProps) {
	const groups = [approvals, ...PROBLEMS.map(({ key }) => problemGroups[key])];
	const nothingOwed = groups.every(
		(group) => group.state.status === "ready" && group.state.total === 0,
	);
	// Another range's "nothing failed", standing in while this one loads, says nothing about this one.
	const staleProblems = PROBLEMS.some(({ key }) => {
		const { state } = problemGroups[key];
		return state.status === "ready" && state.stale;
	});
	let body: ReactNode;
	if (groups.some((group) => group.state.status === "loading") || (nothingOwed && staleProblems)) {
		body = <ReviewResultsSkeleton label="Loading what needs you" rows={2} />;
	} else if (nothingOwed) {
		body = (
			<p className="flex items-center gap-2 text-sm text-muted-foreground">
				<CircleCheckIcon
					aria-hidden
					className={cn("size-4 shrink-0", statusToneClass("success"))}
				/>
				No feedback awaits your approval, and nothing failed in {rangeInSentence}.
			</p>
		);
	} else {
		body = (
			<div className="space-y-6">
				<Approvals workspaceSlug={workspaceSlug} group={approvals} />
				<Problems
					workspaceSlug={workspaceSlug}
					groups={problemGroups}
					rangeInSentence={rangeInSentence}
				/>
			</div>
		);
	}
	return (
		<Section title="Needs you" size="lg">
			{body}
		</Section>
	);
}

function Approvals({
	workspaceSlug,
	group: { state, list },
}: {
	workspaceSlug: string;
	group: ReviewAttentionProps["approvals"];
}) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title="Couldn't load the feedback awaiting your approval"
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status !== "ready" || state.total === 0) {
		return null;
	}
	const [oldest] = state.items;
	const noun = state.total === 1 ? "piece of feedback" : "pieces of feedback";
	return (
		<Section
			level={3}
			size="sm"
			title={
				<>
					Awaiting your approval{" "}
					<span className="text-muted-foreground tabular-nums">{state.total}</span>
				</>
			}
			actions={
				state.total > state.items.length ? (
					<InlineLink
						tone="count"
						className="text-sm"
						render={<ReviewListLink workspaceSlug={workspaceSlug} destination={list} />}
					>
						See all {state.total}
					</InlineLink>
				) : undefined
			}
		>
			<div className="space-y-3">
				<ReviewRowList label="Awaiting your approval">
					{state.items.map((item) => (
						<FeedbackRow key={item.id} feedback={item} />
					))}
				</ReviewRowList>
				{oldest && (
					// The oldest opens first, and its level walks the rest of the queue in the same order.
					<DetailStackLink
						entry={feedbackLevel(oldest.id)}
						// Opened as the queue, which the same level opened from a row is not.
						levelSearch={{ queue: "approvals" }}
						className={buttonVariants()}
					>
						<ClipboardCheckIcon aria-hidden />
						Review {state.total} {noun}
					</DetailStackLink>
				)}
			</div>
		</Section>
	);
}

function Problems({
	workspaceSlug,
	groups,
	rangeInSentence,
}: {
	workspaceSlug: string;
	groups: Pick<ReviewAttentionProps, ProblemDef["key"]>;
	rangeInSentence: string;
}) {
	const failed = PROBLEMS.flatMap((problem) => {
		const { state } = groups[problem.key];
		return state.status === "error" ? [{ problem, state }] : [];
	});
	const counted = PROBLEMS.flatMap((problem) => {
		const { state, list } = groups[problem.key];
		return state.status === "ready" && state.total > 0 ? [{ problem, state, list }] : [];
	});
	// The previous range's counts, standing in while the range just chosen loads.
	const stale = counted.some(({ state }) => state.stale);
	const FailedIcon = REVIEW_STATUS_DEFS.FAILED.icon;
	return (
		<>
			{failed.map(({ problem, state }) => (
				<QueryErrorAlert
					key={problem.key}
					error={state.error}
					title={`Couldn't load ${problem.what}`}
					onRetry={state.onRetry}
				/>
			))}
			{counted.length > 0 && (
				<p
					aria-busy={stale || undefined}
					className={cn("flex items-start gap-2 text-sm text-muted-foreground", stale && STALE)}
				>
					<FailedIcon
						aria-hidden
						className={cn(
							"mt-0.5 size-4 shrink-0",
							statusToneClass(REVIEW_STATUS_DEFS.FAILED.badgeVariant),
						)}
					/>
					<span>
						In {rangeInSentence}:{" "}
						{counted.map(({ problem, state, list }, index) => (
							<Fragment key={problem.key}>
								{index > 0 && " · "}
								<InlineLink
									tone="count"
									render={<ReviewListLink workspaceSlug={workspaceSlug} destination={list} />}
								>
									{problem.phrase(state.total)}
								</InlineLink>
							</Fragment>
						))}
					</span>
				</p>
			)}
		</>
	);
}
