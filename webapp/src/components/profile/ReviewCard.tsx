import { ChevronDownIcon, ChevronUpIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import { cn } from "cn";
import type { ItemRef, ResultList } from "@/api/types.gen";
import { DotSeparator } from "@/components/common/DotSeparator";
import { InlineLink } from "@/components/common/InlineLink";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatShortDay, formatTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { exposedFeedback } from "./ObservationFeedback";
import { OwnFeedbackList } from "./OwnFeedbackList";
import { ReviewResultRow } from "./ReviewResultRow";
import type { FeedbackResponseControls, ReviewEntry } from "./reviews";
import { TimelineItem } from "./TimelineItem";

export interface ReviewCardProps {
	review: ReviewEntry;
	/** The reader's response to each piece of feedback; absent leaves no response controls. */
	responses?: FeedbackResponseControls;
	/** Route-owned, lazy detail for one observation. */
	renderObservation?: (result: ResultList) => ReactNode;
	/** Off on a practice's own level, where every row is that practice and no row repeats it. */
	showPracticeName?: boolean;
	/**
	 * Whether a result arrives open; without it every one does. Whoever places the card decides: a
	 * practice's own level opens one row by its rule (`practice-profile/observation-filter.ts`).
	 */
	openOnArrival?: (result: ResultList) => boolean;
	/** The observation a link asked for, which its row marks as selected. */
	selectedResultId?: string;
	/** Every row is a match of the search, and wears the accent's bar. */
	marksSearchMatches?: boolean;
	/** Set on the last card while earlier reviews exist below the fold. */
	tailContinues?: boolean;
}

const COLLAPSED_RESULT_COUNT = 3;

function resultPriority({ outcome }: ResultList): number {
	return outcome === "NOT_MET" ? 0 : 1;
}

export function ReviewCard({
	review,
	responses,
	renderObservation,
	showPracticeName = true,
	openOnArrival,
	selectedResultId,
	marksSearchMatches = false,
	tailContinues = false,
}: ReviewCardProps) {
	const [showAllResults, setShowAllResults] = useState(false);
	const { results, feedback } = review;
	// Only feedback that reached the reader is listed; the band goes when none did.
	const delivered = exposedFeedback(feedback);
	const at = review.review.createdAt;
	const merged = results.length === 1;
	const hiddenCount = Math.max(0, results.length - COLLAPSED_RESULT_COUNT);
	const orderedResults = [...results].sort((a, b) => resultPriority(a) - resultPriority(b));
	const visibleResults = showAllResults
		? orderedResults
		: orderedResults.slice(0, COLLAPSED_RESULT_COUNT);

	return (
		<TimelineItem
			at={at}
			label={
				<>
					<span className="text-sm font-semibold text-foreground">{formatShortDay(at)}</span>
					{formatTime(at)}
				</>
			}
			tailContinues={tailContinues}
		>
			{/* The feedback card's edge, a stronger outline and a faint shadow, so each observation card
			    stands off the page as a feedback card does. */}
			<div className="min-w-0 overflow-hidden rounded-xl border border-foreground/15 bg-background shadow-sm">
				{!merged && <ReviewedItem item={review.review.item} tone="head" />}
				<ul className="divide-y">
					{visibleResults.map((result) => (
						<ReviewResultRow
							// A row whose arrival state changes starts again from it: the open state is the
							// row's own, so a filter that moves the target must remount the row to move it.
							key={`${result.id}:${String(openOnArrival?.(result) ?? true)}`}
							result={result}
							selected={result.id === selectedResultId}
							matched={marksSearchMatches}
							details={renderObservation?.(result)}
							defaultOpen={openOnArrival?.(result) ?? true}
							showPracticeName={showPracticeName}
							work={merged ? <ReviewedItem item={review.review.item} tone="line" /> : undefined}
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
							onClick={() => setShowAllResults((current) => !current)}
						>
							{showAllResults
								? "Show less"
								: `Show ${hiddenCount} more ${hiddenCount === 1 ? "observation" : "observations"}`}
							{showAllResults ? (
								<ChevronUpIcon data-icon="inline-end" />
							) : (
								<ChevronDownIcon data-icon="inline-end" />
							)}
						</Button>
					</div>
				)}
				{renderObservation === undefined && delivered.length > 0 && (
					<div className="border-t bg-sidebar px-4 py-3">
						<OwnFeedbackList feedback={delivered} responses={responses} />
					</div>
				)}
			</div>
		</TimelineItem>
	);
}

function ReviewedItem({ item, tone }: { item: ItemRef; tone: "head" | "line" }) {
	const Icon = reviewedWorkIcon(item.kind, item.provider ?? undefined);
	const identity = item.label;
	const isHead = tone === "head";
	const typography = isHead ? "text-sm font-medium" : "text-xs";
	const name = hasText(item.url) ? (
		<Tooltip>
			<TooltipTrigger
				render={
					<InlineLink href={item.url} external className={cn("flex min-w-0", typography)}>
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
				<Icon className="mt-0.5 size-3.5 shrink-0 text-muted-foreground" />
				<div className="flex min-w-0 flex-col gap-0.5">
					{name}
					{hasText(item.container) && (
						<span className="truncate text-xs text-muted-foreground">{item.container}</span>
					)}
				</div>
			</div>
		);
	}

	return (
		<span className="flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
			<Icon className="size-3.5 shrink-0" />
			{name}
			{hasText(item.container) && (
				<>
					<DotSeparator />
					<span className="truncate">{item.container}</span>
				</>
			)}
		</span>
	);
}
