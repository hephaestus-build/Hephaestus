import { CircleCheckIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { ReviewFeedback, ReviewRunSummary } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Section } from "@/components/layout/Section";

import { FeedbackRow } from "./FeedbackResults";
import type { ReviewListTarget } from "./review-outcomes";
import type { ReviewSectionState } from "./review-states";
import { ReviewListLink } from "./ReviewListLink";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";
import { ReviewRunRow } from "./ReviewRunRow";

export interface AttentionGroup<T> {
	state: ReviewSectionState<T>;
	/** Every row the group's first few stand for. */
	list: ReviewListTarget;
}

export interface ReviewAttentionProps {
	workspaceSlug: string;
	/** Feedback awaiting approval, whenever it was composed: a decision owed has no range. */
	approvals: AttentionGroup<ReviewFeedback>;
	/** Feedback whose delivery failed in the range. */
	failedDeliveries: AttentionGroup<ReviewFeedback>;
	/** Reviews that failed or timed out in the range. */
	failedReviews: AttentionGroup<ReviewRunSummary>;
	/** "the last 30 days", for the all-clear sentence. */
	rangeInSentence: string;
}

/**
 * What an admin owes the reviews, before what the reviews did — decisions first, failures next, as
 * GitLab's merge request homepage orders them. A group with nothing in it is left out; with nothing
 * anywhere, one line says so, so an empty section never reads as broken.
 */
export function ReviewAttention({
	workspaceSlug,
	approvals,
	failedDeliveries,
	failedReviews,
	rangeInSentence,
}: ReviewAttentionProps) {
	const states = [approvals.state, failedDeliveries.state, failedReviews.state];
	let body: ReactNode;
	if (states.some((state) => state.status === "loading")) {
		body = <ReviewResultsSkeleton label="Loading what needs you" rows={2} />;
	} else if (states.every((state) => state.status === "ready" && state.items.length === 0)) {
		body = (
			<p className="flex items-center gap-2 text-sm text-muted-foreground">
				<CircleCheckIcon aria-hidden className="size-4 shrink-0" />
				No feedback awaits your approval, and nothing failed in {rangeInSentence}.
			</p>
		);
	} else {
		body = (
			<div className="space-y-6">
				<Group workspaceSlug={workspaceSlug} title="Awaiting your approval" group={approvals}>
					{(items) => items.map((item) => <FeedbackRow key={item.id} feedback={item} />)}
				</Group>
				<Group workspaceSlug={workspaceSlug} title="Failed to deliver" group={failedDeliveries}>
					{(items) => items.map((item) => <FeedbackRow key={item.id} feedback={item} />)}
				</Group>
				<Group workspaceSlug={workspaceSlug} title="Failed reviews" group={failedReviews}>
					{(items) => items.map((item) => <ReviewRunRow key={item.id} review={item} />)}
				</Group>
			</div>
		);
	}
	return (
		<Section title="Needs you" size="lg">
			{body}
		</Section>
	);
}

function Group<T>({
	workspaceSlug,
	title,
	group: { state, list },
	children,
}: {
	workspaceSlug: string;
	title: string;
	group: AttentionGroup<T>;
	children: (items: T[]) => ReactNode;
}) {
	if (state.status === "error") {
		return (
			<QueryErrorAlert
				error={state.error}
				title={`Couldn't load ${title.toLowerCase()}`}
				onRetry={state.onRetry}
			/>
		);
	}
	if (state.status !== "ready" || state.items.length === 0) {
		return null;
	}
	return (
		<Section
			level={3}
			size="sm"
			title={
				<>
					{title} <span className="text-muted-foreground tabular-nums">{state.total}</span>
				</>
			}
			actions={
				state.total > state.items.length ? (
					<InlineLink
						className="text-sm"
						render={<ReviewListLink workspaceSlug={workspaceSlug} destination={list} />}
					>
						See all {state.total}
					</InlineLink>
				) : undefined
			}
		>
			<ReviewRowList label={title}>{children(state.items)}</ReviewRowList>
		</Section>
	);
}
