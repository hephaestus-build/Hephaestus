import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";
import { useState } from "react";

import { cn } from "cn";
import type { PracticeGroupReviewRun, ReviewedWorkRef } from "@/api/types.gen";
import { InlineLink } from "@/components/common/InlineLink";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { asDate, formatShortDay, formatTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import type { ObservationControls } from "./review-runs";
import { ReviewObservationRow } from "./ReviewObservationRow";
import { TimelineItem } from "./TimelineItem";

export interface ReviewRunCardProps {
	run: PracticeGroupReviewRun;
	initialObservationCount?: number;
	/** The reader's response to an observation, handed to every row. */
	observations?: ObservationControls;
	/** Off on a practice's own level, where every row is that practice and no row repeats it. */
	showPracticeName?: boolean;
	/**
	 * Which of this card's observations arrive open: every one, only the first, or none. Whoever
	 * places the card decides — a feed of one practice opens its newest row and leaves the reader
	 * to open the rest.
	 */
	initiallyOpen?: "all" | "first" | "none";
	/** Set on the last card while earlier runs exist below the fold. */
	tailContinues?: boolean;
}

/**
 * One review run as a row of the timeline: the day and time in the date column, a dot on the
 * rail, and the card with the reviewed work at its head and the observations as its rows. The head
 * names the work and nothing else: what the run is worth reading is what it observed, and a
 * sentence about the run as a whole only stood between the reader and those rows.
 *
 * A run that carries one observation has no two things to separate — on a practice's own level a
 * run reviews that practice once, so every card there is that case. It drops the head and merges
 * into one block: the observation's summary and outcome are the block's first line, and the work
 * the head would have named is the small line under it.
 */
const NO_CONTROLS: ObservationControls = {};

export function ReviewRunCard({
	run,
	initialObservationCount = 3,
	observations = NO_CONTROLS,
	showPracticeName = true,
	initiallyOpen = "all",
	tailContinues = false,
}: ReviewRunCardProps) {
	const [showAllObservations, setShowAllObservations] = useState(false);
	const reviewedAt = asDate(run.reviewedAt);
	// One observation is one block: the row takes the work line and the card grows no head over it.
	const merged = run.observations.length === 1;
	const collapsedCount = Math.max(1, initialObservationCount);
	const hiddenCount = Math.max(0, run.observations.length - collapsedCount);
	const visibleObservations = showAllObservations
		? run.observations
		: run.observations.slice(0, collapsedCount);

	return (
		<TimelineItem
			at={reviewedAt}
			label={
				reviewedAt && (
					<>
						<span className="text-sm font-semibold text-foreground">
							{formatShortDay(reviewedAt)}
						</span>
						{formatTime(reviewedAt)}
					</>
				)
			}
			tailContinues={tailContinues}
		>
			<div className="min-w-0 overflow-hidden rounded-xl border bg-background">
				{!merged && <ReviewedWork work={run.reviewedWork} tone="head" />}
				<ul className="divide-y">
					{visibleObservations.map((observation, index) => (
						<ReviewObservationRow
							key={observation.id}
							observation={observation}
							defaultOpen={initiallyOpen === "all" || (initiallyOpen === "first" && index === 0)}
							showPracticeName={showPracticeName}
							// Either the card's head or the row's own work line links the work every
							// observation here was seen on, so the evidence block never links it again.
							showWorkLink={false}
							work={merged ? <ReviewedWork work={run.reviewedWork} tone="line" /> : undefined}
							onRespond={observations.onRespond}
							isFeedbackResponsePending={
								observations.pendingFeedbackId === observation.feedbackResponse?.feedbackId
							}
						/>
					))}
				</ul>
				{hiddenCount > 0 && (
					<div className="border-t px-4 py-2">
						<Button
							type="button"
							variant="quiet"
							size="sm"
							className="h-8"
							onClick={() => setShowAllObservations((current) => !current)}
						>
							{showAllObservations ? "Show less" : `Show more (${hiddenCount})`}
							{showAllObservations ? (
								<ChevronUpIcon data-icon="inline-end" />
							) : (
								<ChevronDownIcon data-icon="inline-end" />
							)}
						</Button>
					</div>
				)}
			</div>
		</TimelineItem>
	);
}

/**
 * The work a run reviewed, in the two shapes a card needs it.
 *
 * `head` is the head of a card whose observations are several: the link the rows below it share,
 * with the repository under it. `line` is the same work under a merged card's summary, on one
 * small line — "#902 · HephaestusTest/practice-validation" — where the summary is the block's
 * anchor and the work it was seen on reads as the note beneath it rather than as a second head.
 *
 * One component, because the two differ in layout and type size only: whether the work is linked,
 * how it is tooltipped and when the repository is printed are the same decisions, and a fix to one
 * copy of them would have missed the other.
 */
function ReviewedWork({ work, tone }: { work: ReviewedWorkRef; tone: "head" | "line" }) {
	const Icon = reviewedWorkIcon(work.kind, work.provider);
	// The wire names the work: "#902", "#backend-guild", a document's title.
	const identity = work.label;
	const isHead = tone === "head";
	const typography = isHead ? "text-sm font-medium" : "text-xs";
	const name = hasText(work.url) ? (
		<Tooltip>
			<TooltipTrigger
				render={
					<InlineLink href={work.url} external className={cn("flex min-w-0", typography)}>
						<span className="truncate">{identity}</span>
					</InlineLink>
				}
			/>
			<TooltipContent className="max-w-80 text-pretty">{identity}</TooltipContent>
		</Tooltip>
	) : (
		<span className={cn("truncate", typography)}>{identity}</span>
	);

	if (isHead) {
		return (
			<div className="flex min-w-0 items-start gap-2.5 border-b px-4 py-3">
				<Icon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" aria-hidden />
				<div className="flex min-w-0 flex-col gap-0.5">
					{name}
					{hasText(work.repositoryName) && (
						<span className="truncate text-xs text-muted-foreground">{work.repositoryName}</span>
					)}
				</div>
			</div>
		);
	}

	return (
		<span className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
			<Icon className="size-3.5 shrink-0" aria-hidden />
			{name}
			{hasText(work.repositoryName) && (
				<>
					<span aria-hidden>·</span>
					<span className="truncate">{work.repositoryName}</span>
				</>
			)}
		</span>
	);
}
