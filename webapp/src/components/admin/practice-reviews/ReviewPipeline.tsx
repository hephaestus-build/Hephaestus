import { type LucideIcon, MessageSquareTextIcon, ScanSearchIcon, WorkflowIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { ReviewBucket, ReviewOverview } from "@/api/types.gen";
import { deltaPhrase } from "@/components/activity/activity-buckets";
import { STALE } from "@/components/activity/activity-tones";
import { BucketBars } from "@/components/activity/BucketBars";
import { InlineLink } from "@/components/common/InlineLink";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

import { CardTitleLink } from "./CardTitleLink";
import { OutcomeMix, type OutcomeMixProps } from "./OutcomeMix";
import { OVERVIEW_TYPE } from "./overview-type-scale";
import { percentText, shareOf } from "./review-attention";
import {
	ENDED_REVIEW_STATUSES,
	finishedCount,
	markedIncorrectSlot,
	OUTCOME_LEGEND_LABELS,
	resultSlots,
	type OutcomeScope,
	scopeSearch,
	overviewFeedbackSlots,
	type OutcomeSlot,
	reviewSlots,
	slotsTotal,
} from "./review-outcomes";
import type { OverviewRegionState, PreviousReviewPeriodState } from "./review-states";
import { ReviewListLink } from "./ReviewListLink";

export interface ReviewPipelineProps {
	state: OverviewRegionState;
	/** The period before the range, which each total is set against; failed, there is no comparison. */
	previous: PreviousReviewPeriodState;
	/** That period by name, from the range on screen: "the previous 30 days". */
	previousName: string;
	/** The range as the lists' day filters, so every count opens the rows it counts. */
	scope: OutcomeScope;
	/** The stages to draw, in their order; all three when absent. */
	stages?: readonly StageKey[];
	/** The list's accessible name, so two rows of tiles on a page are told apart. */
	"aria-label"?: string;
	/** A last tile with its own read, such as the failed deliveries, drawn after the stages. */
	lastTile?: ReactNode;
	/** Given, the counts of the stages that link open their lists filtered to the rows counted. */
	workspaceSlug?: string;
}

export type StageKey = "reviews" | "results" | "feedback";

/**
 * Volume is information, not a state, and a practice surface has no tone for that: its accent is
 * kept for what the eye should land on, and the provider tones Activity gives its bars name provider
 * states. Activity's reason for avoiding grey — beside state-coloured bars it reads as disabled —
 * does not arise here, where each tile draws one series alone.
 */
const VOLUME_FILL = "var(--color-muted-foreground)";

interface StageDef {
	key: StageKey;
	title: string;
	icon: LucideIcon;
	noun: (count: number) => string;
	perBucket: (bucket: ReviewBucket) => number;
	total: (overview: ReviewOverview) => number;
	slots: (overview: ReviewOverview, scope?: OutcomeScope) => OutcomeSlot[];
	flags?: (overview: ReviewOverview, scope: OutcomeScope) => OutcomeMixProps["flags"];
}

const isFinished = (slot: OutcomeSlot) =>
	(ENDED_REVIEW_STATUSES as readonly string[]).includes(slot.key);

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
		// Reviews not finished yet have no outcome to count: they come last in the row, quiet, and
		// the page re-reads while any are there. How many run and wait right now is the queue's.
		flags: (overview, scope) => ({
			label: OUTCOME_LEGEND_LABELS.openReviews,
			slots: [openReviewsSlot(overview, scope)],
		}),
	},
	{
		key: "results",
		title: "Observations",
		icon: ScanSearchIcon,
		noun: (count) => (count === 1 ? "observation" : "observations"),
		perBucket: (bucket) => bucket.results,
		total: (overview) => slotsTotal(resultSlots(overview.results)),
		slots: (overview, scope) =>
			resultSlots(overview.results, scope).map((slot) => ({
				...slot,
				label: slot.def.label.toLowerCase(),
			})),
		flags: (overview, scope) => ({
			label: OUTCOME_LEGEND_LABELS.checkedResults,
			slots: [markedIncorrectSlot(overview.resultsMarkedIncorrect, scope)],
		}),
	},
	{
		key: "feedback",
		title: "Feedback",
		icon: MessageSquareTextIcon,
		noun: (count) => (count === 1 ? "piece of feedback" : "pieces of feedback"),
		perBucket: (bucket) => bucket.feedback,
		total: (overview) => slotsTotal(overviewFeedbackSlots(overview.feedback)),
		slots: (overview, scope) => overviewFeedbackSlots(overview.feedback, scope),
	},
];

/** The row's columns by how many tiles it holds, so no tile stands alone on a wide screen. */
const GRID_COLUMNS: Partial<Record<number, string>> = {
	1: "",
	2: "@xl:grid-cols-2",
	3: "@3xl:grid-cols-3",
};

export function ReviewPipeline({
	state,
	previous,
	previousName,
	scope,
	stages,
	"aria-label": label = "Stages",
	lastTile,
	workspaceSlug,
}: ReviewPipelineProps) {
	const busy = state.status === "loading" || state.stale;
	const shown = stages === undefined ? STAGES : STAGES.filter(({ key }) => stages.includes(key));
	const tiles = shown.length + (lastTile === undefined ? 0 : 1);
	return (
		<div className="@container">
			<ul
				aria-busy={busy || undefined}
				aria-label={label}
				className={cn(
					"grid grid-cols-1 gap-3",
					GRID_COLUMNS[tiles] ?? "@xl:grid-cols-2 @5xl:grid-cols-4",
					state.status === "ready" && state.stale && STALE,
				)}
			>
				{shown.map((stage) => (
					<li key={stage.key} className="flex">
						{state.status === "ready" ? (
							<StageTile
								stage={stage}
								overview={state.overview}
								previous={previous}
								previousName={previousName}
								stale={state.stale}
								scope={scope}
								workspaceSlug={workspaceSlug}
							/>
						) : (
							<StageSkeleton />
						)}
					</li>
				))}
				{lastTile !== undefined && <li className="flex">{lastTile}</li>}
			</ul>
			{busy && <span className="sr-only">Loading what the reviews did</span>}
		</div>
	);
}

function StageTile({
	stage,
	overview,
	previous,
	previousName,
	stale,
	scope,
	workspaceSlug,
}: {
	stage: StageDef;
	overview: ReviewOverview;
	previous: PreviousReviewPeriodState;
	previousName: string;
	/** The overview is the previous range's, which nothing may be set against. */
	stale: boolean;
	scope: OutcomeScope;
	workspaceSlug?: string;
}) {
	const Icon = stage.icon;
	const total = stage.total(overview);
	// Set against the period before once both are in; until then its line is held, so the tile does
	// not move when the comparison lands.
	let delta: string | null | undefined = null;
	if (previous.status === "error" || previous.status === "none") {
		delta = undefined;
	} else if (previous.status === "ready" && !stale) {
		delta = deltaPhrase(total, stage.total(previous.period.overview), previousName);
	}
	return (
		<Card size="sm" className="w-full">
			<CardHeader>
				<CardTitle className="flex items-center gap-2 text-sm font-medium">
					<Icon aria-hidden className="size-4 shrink-0 text-muted-foreground" />
					<CardTitleLink
						workspaceSlug={workspaceSlug}
						destination={{ list: stage.key, search: scopeSearch(scope, stage.key) }}
					>
						{stage.title}
					</CardTitleLink>
				</CardTitle>
			</CardHeader>
			<CardContent>
				<OutcomeMix
					total={total}
					slots={stage.slots(overview, scope)}
					flags={stage.flags?.(overview, scope)}
					noun={stage.noun}
					label={OUTCOME_LEGEND_LABELS[stage.key]}
					delta={delta}
					workspaceSlug={workspaceSlug}
					trailing={
						stage.key === "reviews" ? (
							<FailedShare overview={overview} scope={scope} workspaceSlug={workspaceSlug} />
						) : undefined
					}
				>
					{/* Drawn at zero too, so the tiles beside each other keep their rows in line. */}
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
				</OutcomeMix>
			</CardContent>
		</Card>
	);
}

/** Reviews of the range not finished yet, queued or running, opening the Reviews table at them. */
function openReviewsSlot(overview: ReviewOverview, scope: OutcomeScope): OutcomeSlot {
	return {
		key: "open",
		def: REVIEW_STATUS_DEFS.RUNNING,
		label: "not finished yet",
		count: overview.reviews.queued + overview.reviews.running,
		target: {
			list: "reviews",
			search: { ...scopeSearch(scope, "reviews"), status: ["QUEUED", "RUNNING"] },
		},
	};
}

function FailedShare({
	overview: { reviews },
	scope,
	workspaceSlug,
}: {
	overview: ReviewOverview;
	scope: OutcomeScope;
	workspaceSlug?: string;
}) {
	const { failed } = reviews;
	const finished = finishedCount(reviews);
	if (failed === 0) {
		return null;
	}
	const share = shareOf(failed, finished);
	const text = (
		<>
			<span className={OVERVIEW_TYPE.value}>{percentText(share)}</span> failed
		</>
	);
	return (
		<span
			data-tone="secondary"
			title={`${failed} of ${finished} finished reviews`}
			className={cn("inline-flex items-center gap-1.5", OVERVIEW_TYPE.label)}
		>
			<FailedIcon aria-hidden className="size-3.5 shrink-0 text-muted-foreground" />
			{workspaceSlug === undefined ? (
				<span>{text}</span>
			) : (
				<InlineLink
					tone="count"
					aria-label={`${percentText(share)} failed, ${failed} of ${finished} finished reviews`}
					render={
						<ReviewListLink
							workspaceSlug={workspaceSlug}
							destination={{
								list: "reviews",
								search: { ...scopeSearch(scope, "reviews"), status: ["FAILED"] },
							}}
						/>
					}
				>
					{text}
				</InlineLink>
			)}
		</span>
	);
}

const FailedIcon = REVIEW_STATUS_DEFS.FAILED.icon;

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
