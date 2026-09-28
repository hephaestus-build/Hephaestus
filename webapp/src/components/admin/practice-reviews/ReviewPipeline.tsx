import { type LucideIcon, MessageSquareTextIcon, ScanSearchIcon, WorkflowIcon } from "lucide-react";

import { cn } from "cn";
import type { PracticeReviewBucket, PracticeReviewOverview } from "@/api/types.gen";
import { ACTIVITY_TONES, STALE } from "@/components/activity/activity-tones";
import { BucketBars } from "@/components/activity/BucketBars";
import { InlineLink } from "@/components/common/InlineLink";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { OutcomeMix } from "./OutcomeMix";
import {
	feedbackSlots,
	markedIncorrectSlot,
	observationSlots,
	type OutcomeScope,
	type OutcomeSlot,
	type ReviewListTarget,
	reviewSlots,
	slotsTotal,
} from "./review-outcomes";
import type { OverviewRegionState } from "./review-states";
import { ReviewListLink } from "./ReviewListLink";

export interface ReviewPipelineProps {
	workspaceSlug: string;
	state: OverviewRegionState;
	/** The range as the lists' day filters, so every count opens the rows it counts. */
	scope: OutcomeScope;
}

interface StageDef {
	key: "reviews" | "observations" | "feedback";
	title: string;
	icon: LucideIcon;
	noun: (count: number) => string;
	perBucket: (bucket: PracticeReviewBucket) => number;
	slots: (overview: PracticeReviewOverview, scope: OutcomeScope) => OutcomeSlot[];
	flags?: (overview: PracticeReviewOverview, scope: OutcomeScope) => OutcomeSlot[];
	list: (scope: OutcomeScope) => ReviewListTarget;
}

const STAGES: readonly StageDef[] = [
	{
		key: "reviews",
		title: "Reviews",
		icon: WorkflowIcon,
		noun: (count) => (count === 1 ? "review" : "reviews"),
		perBucket: (bucket) => bucket.reviews,
		slots: (overview, scope) => reviewSlots(overview.reviews, scope),
		list: ({ from, to }) => ({ list: "runs", search: { from, to } }),
	},
	{
		key: "observations",
		title: "Observations",
		icon: ScanSearchIcon,
		noun: (count) => (count === 1 ? "observation" : "observations"),
		perBucket: (bucket) => bucket.observations,
		slots: (overview, scope) => observationSlots(overview.observations, scope),
		flags: (overview, scope) => [markedIncorrectSlot(overview.observationsInvalidated, scope)],
		list: ({ from, to }) => ({ list: "observations", search: { from, to } }),
	},
	{
		key: "feedback",
		title: "Feedback",
		icon: MessageSquareTextIcon,
		noun: (count) => (count === 1 ? "piece of feedback" : "pieces of feedback"),
		perBucket: (bucket) => bucket.feedback,
		slots: (overview, scope) => feedbackSlots(overview.feedback, scope),
		list: ({ from, to }) => ({ list: "feedback", search: { from, to } }),
	},
];

/**
 * The stages work flows through — reviews ran, recorded observations, composed feedback — as one
 * sentence, then a tile each: its total, its volume over time, and how it turned out. Volume is
 * drawn in the neutral accent because only an outcome is a state.
 */
export function ReviewPipeline({ workspaceSlug, state, scope }: ReviewPipelineProps) {
	const busy = state.status === "loading" || state.stale;
	return (
		<div className="space-y-4">
			{state.status === "ready" ? (
				<p className={cn("text-sm text-muted-foreground", state.stale && STALE)}>
					{pipelineSentence(state.overview)}
				</p>
			) : (
				<Skeleton aria-hidden className="h-5 w-full max-w-xl" />
			)}
			<div className="@container">
				<ul
					aria-busy={busy || undefined}
					aria-label="Stages"
					className={cn(
						"grid grid-cols-1 gap-3 @3xl:grid-cols-3",
						state.status === "ready" && state.stale && STALE,
					)}
				>
					{STAGES.map((stage) => (
						<li key={stage.key} className="flex">
							{state.status === "ready" ? (
								<StageTile
									workspaceSlug={workspaceSlug}
									stage={stage}
									overview={state.overview}
									scope={scope}
								/>
							) : (
								<StageSkeleton />
							)}
						</li>
					))}
				</ul>
			</div>
			{busy && <span className="sr-only">Loading what the reviews did</span>}
		</div>
	);
}

/**
 * The stages in one line, what a plan summary says before its detail. Each stage is counted by its
 * own date, so the line lists them side by side rather than claiming one caused the next.
 */
function pipelineSentence(overview: PracticeReviewOverview): string {
	const reviews = slotsTotal(reviewSlots(overview.reviews));
	const observations = slotsTotal(observationSlots(overview.observations));
	const feedback = slotsTotal(feedbackSlots(overview.feedback));
	if (reviews + observations + feedback === 0) {
		return "Nothing was reviewed in this range.";
	}
	const { delivered, awaitingApproval } = overview.feedback;
	return (
		`In this range: ${reviews} ${reviews === 1 ? "review" : "reviews"}, ${observations} ` +
		`${observations === 1 ? "observation" : "observations"} and ${feedback} ` +
		`${feedback === 1 ? "piece" : "pieces"} of feedback, of which ${delivered} delivered and ` +
		`${awaitingApproval} awaiting approval.`
	);
}

function StageTile({
	workspaceSlug,
	stage,
	overview,
	scope,
}: {
	workspaceSlug: string;
	stage: StageDef;
	overview: PracticeReviewOverview;
	scope: OutcomeScope;
}) {
	const Icon = stage.icon;
	return (
		<Card size="sm" className="w-full">
			<CardHeader>
				<CardTitle className="flex items-center gap-2 text-sm font-medium">
					<Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
					<InlineLink
						render={
							<ReviewListLink workspaceSlug={workspaceSlug} destination={stage.list(scope)} />
						}
					>
						{stage.title}
					</InlineLink>
				</CardTitle>
			</CardHeader>
			<CardContent>
				<OutcomeMix
					workspaceSlug={workspaceSlug}
					slots={stage.slots(overview, scope)}
					flags={stage.flags?.(overview, scope)}
					noun={stage.noun}
					label={`${stage.title} by outcome`}
				>
					<BucketBars
						rows={overview.buckets.map((bucket) => ({
							start: bucket.start.getTime(),
							count: stage.perBucket(bucket),
						}))}
						bucket={overview.bucket}
						span={{ from: overview.from, to: overview.to }}
						name={stage.title}
						fill={ACTIVITY_TONES.accent.fill}
					/>
				</OutcomeMix>
			</CardContent>
		</Card>
	);
}

/** A tile's shape while the overview loads: the title, the number, the bars, the mix. */
function StageSkeleton() {
	return (
		<Card size="sm" className="w-full" aria-hidden>
			<CardHeader>
				<Skeleton className="h-4 w-24" />
			</CardHeader>
			<CardContent className="flex flex-col gap-3">
				<Skeleton className="h-6 w-16" />
				<Skeleton className="h-16 w-full" />
				<Skeleton className="h-2 w-full" />
				<Skeleton className="h-4 w-40" />
			</CardContent>
		</Card>
	);
}
