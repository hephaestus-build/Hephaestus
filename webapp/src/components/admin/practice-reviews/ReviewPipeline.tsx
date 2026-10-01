import { type LucideIcon, MessageSquareTextIcon, ScanSearchIcon, WorkflowIcon } from "lucide-react";
import { Fragment } from "react";

import { cn } from "cn";
import type { PracticeReviewBucket, PracticeReviewOverview } from "@/api/types.gen";
import { deltaPhrase } from "@/components/activity/activity-buckets";
import { STALE } from "@/components/activity/activity-tones";
import { BucketBars } from "@/components/activity/BucketBars";
import { InlineLink } from "@/components/common/InlineLink";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { OutcomeMix, type OutcomeMixProps } from "./OutcomeMix";
import {
	FINISHED_REVIEW_STATUSES,
	feedbackSlots,
	markedIncorrectSlot,
	OUTCOME_LEGEND_LABELS,
	observationSlots,
	type OutcomeScope,
	type OutcomeSlot,
	type ReviewListTarget,
	reviewSlots,
	slotsTotal,
} from "./review-outcomes";
import type { OverviewRegionState, PreviousReviewPeriodState } from "./review-states";
import { ReviewListLink } from "./ReviewListLink";

export interface ReviewPipelineProps {
	workspaceSlug: string;
	state: OverviewRegionState;
	/** The period before the range, which each total is set against; failed, there is no comparison. */
	previous: PreviousReviewPeriodState;
	/** The range as the lists' day filters, so every count opens the rows it counts. */
	scope: OutcomeScope;
}

/**
 * Volume is information, not a state, and a practice surface has no tone for that: its accent is
 * kept for what the eye should land on, and the provider tones Activity gives its bars name provider
 * states. Activity's reason for avoiding grey — beside state-coloured bars it reads as disabled —
 * does not arise here, where each tile draws one series alone.
 */
const VOLUME_FILL = "var(--color-muted-foreground)";

interface StageDef {
	key: "reviews" | "observations" | "feedback";
	title: string;
	icon: LucideIcon;
	noun: (count: number) => string;
	perBucket: (bucket: PracticeReviewBucket) => number;
	total: (overview: PracticeReviewOverview) => number;
	slots: (overview: PracticeReviewOverview, scope?: OutcomeScope) => OutcomeSlot[];
	flags?: (overview: PracticeReviewOverview, scope: OutcomeScope) => OutcomeMixProps["flags"];
	list: (scope: OutcomeScope) => ReviewListTarget;
}

const isFinished = (slot: OutcomeSlot) =>
	(FINISHED_REVIEW_STATUSES as readonly string[]).includes(slot.key);

const STAGES: readonly StageDef[] = [
	{
		key: "reviews",
		title: "Reviews",
		icon: WorkflowIcon,
		noun: (count) => (count === 1 ? "review" : "reviews"),
		perBucket: (bucket) => bucket.reviews,
		// Every review requested in the range, whether it has finished or not.
		total: (overview) => slotsTotal(reviewSlots(overview.reviews)),
		slots: (overview, scope) => reviewSlots(overview.reviews, scope).filter(isFinished),
		list: ({ from, to }) => ({ list: "runs", search: { from, to } }),
	},
	{
		key: "observations",
		title: "Observations",
		icon: ScanSearchIcon,
		noun: (count) => (count === 1 ? "observation" : "observations"),
		perBucket: (bucket) => bucket.observations,
		total: (overview) => slotsTotal(observationSlots(overview.observations)),
		slots: (overview, scope) => observationSlots(overview.observations, scope),
		flags: (overview, scope) => ({
			label: OUTCOME_LEGEND_LABELS.checkedObservations,
			slots: [markedIncorrectSlot(overview.observationsInvalidated, scope)],
		}),
		list: ({ from, to }) => ({ list: "observations", search: { from, to } }),
	},
	{
		key: "feedback",
		title: "Feedback",
		icon: MessageSquareTextIcon,
		noun: (count) => (count === 1 ? "piece of feedback" : "pieces of feedback"),
		perBucket: (bucket) => bucket.feedback,
		total: (overview) => slotsTotal(feedbackSlots(overview.feedback)),
		slots: (overview, scope) => feedbackSlots(overview.feedback, scope),
		list: ({ from, to }) => ({ list: "feedback", search: { from, to } }),
	},
];

/**
 * The stages work flows through — reviews ran, recorded observations, composed feedback — a tile
 * each, in the recipe of Activity's tiles: the total, how it compares with the period before, its
 * volume over the range, then what it split into. Each stage is counted by its own date, so the
 * tiles stand side by side rather than claiming one caused the next.
 */
export function ReviewPipeline({ workspaceSlug, state, previous, scope }: ReviewPipelineProps) {
	const busy = state.status === "loading" || state.stale;
	return (
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
								previous={previous}
								stale={state.stale}
								scope={scope}
							/>
						) : (
							<StageSkeleton />
						)}
					</li>
				))}
			</ul>
			{busy && <span className="sr-only">Loading what the reviews did</span>}
		</div>
	);
}

function StageTile({
	workspaceSlug,
	stage,
	overview,
	previous,
	stale,
	scope,
}: {
	workspaceSlug: string;
	stage: StageDef;
	overview: PracticeReviewOverview;
	previous: PreviousReviewPeriodState;
	/** The overview is the previous range's, which nothing may be set against. */
	stale: boolean;
	scope: OutcomeScope;
}) {
	const Icon = stage.icon;
	const total = stage.total(overview);
	// Set against the period before once both are in; until then its line is held, so the tile does
	// not move when the comparison lands.
	let delta: string | null | undefined = null;
	if (previous.status === "error") {
		delta = undefined;
	} else if (previous.status === "ready" && !stale) {
		delta = deltaPhrase(total, stage.total(previous.period.overview), previous.period.name);
	}
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
					total={total}
					slots={stage.slots(overview, scope)}
					flags={stage.flags?.(overview, scope)}
					noun={stage.noun}
					label={OUTCOME_LEGEND_LABELS[stage.key]}
					delta={delta}
				>
					{stage.key === "reviews" && (
						<StillRunning workspaceSlug={workspaceSlug} overview={overview} scope={scope} />
					)}
					{total > 0 && (
						<BucketBars
							rows={overview.buckets.map((bucket) => ({
								start: bucket.start.getTime(),
								count: stage.perBucket(bucket),
							}))}
							bucket={overview.bucket}
							span={{ from: overview.from, to: overview.to }}
							name={stage.title}
							fill={VOLUME_FILL}
						/>
					)}
				</OutcomeMix>
			</CardContent>
		</Card>
	);
}

/**
 * Reviews not finished yet — "1 running, 2 queued" — as a live line rather than a legend entry: they
 * have no outcome to count yet, and the page re-reads while any are there.
 */
function StillRunning({
	workspaceSlug,
	overview,
	scope,
}: {
	workspaceSlug: string;
	overview: PracticeReviewOverview;
	scope: OutcomeScope;
}) {
	const slots = reviewSlots(overview.reviews, scope);
	// What is happening now reads before what is waiting.
	const live = (["RUNNING", "QUEUED"] as const).flatMap((status) =>
		slots.filter((slot) => slot.key === status && slot.count > 0),
	);
	if (live.length === 0) {
		return null;
	}
	return (
		<p className="text-sm text-muted-foreground">
			{live.map((slot, index) => (
				<Fragment key={slot.key}>
					{index > 0 && ", "}
					<InlineLink
						tone="count"
						render={<ReviewListLink workspaceSlug={workspaceSlug} destination={slot.target} />}
					>
						<span className="tabular-nums">{slot.count}</span> {slot.label}
					</InlineLink>
				</Fragment>
			))}
		</p>
	);
}

/** A tile's shape while the overview loads: the title, the number, the bars, the legend. */
function StageSkeleton() {
	return (
		<Card size="sm" className="w-full" aria-hidden>
			<CardHeader>
				<Skeleton className="h-4 w-24" />
			</CardHeader>
			<CardContent className="flex flex-col gap-3">
				<Skeleton className="h-6 w-16" />
				<Skeleton className="h-16 w-full" />
				<Skeleton className="h-4 w-40" />
			</CardContent>
		</Card>
	);
}
