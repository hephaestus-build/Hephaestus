import { ClockIcon, ListOrderedIcon, LoaderIcon, PauseIcon, TriangleAlertIcon } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "cn";
import type { ReviewQueue, ReviewQueueItem } from "@/api/types.gen";
import { type InfoLine, InfoLines, InfoTip } from "@/components/common/InfoTip";
import { InlineLink } from "@/components/common/InlineLink";
import type { PanelState } from "@/components/common/panel-state";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { type StatusDef, statusToneClass } from "@/components/common/status-def";
import { reviewedWorkIcon } from "@/components/icons/reviewed-work-icon";
import { DetailStackLink } from "@/components/layout/detail-drawer/DetailStackLink";
import { Skeleton } from "@/components/ui/skeleton";
import { durationText } from "@/lib/dates";
import { firstNonBlank } from "@/lib/text";

import { type CardFigure, FigureCaption, FigureRow } from "./CardFigures";
import { OVERVIEW_TYPE } from "./overview-type-scale";
import { reviewLevel } from "./review-levels";
import {
	oldestWaitSeconds,
	QUEUE_ROWS,
	type QueueItemState,
	type QueueState,
	queueItemState,
	queueTotal,
	secondsUntilRead,
	waitsLong,
} from "./review-queue";
import { ReviewListLink } from "./ReviewListLink";

/** The queue as it stands now, read apart from the range: it is the same whatever the range. */
export type ReviewQueueState = PanelState<{ queue: ReviewQueue }>;

export interface ReviewQueueNowProps {
	workspaceSlug: string;
	state: ReviewQueueState;
}

/**
 * The status of a row: the review registry's words for running and queued, and a hold of its own,
 * since a review held for budget waits for the cap and not for a worker.
 */
const QUEUE_ITEM_DEFS: Record<QueueItemState, StatusDef & { short: string }> = {
	running: {
		label: "Running",
		short: "Running",
		icon: LoaderIcon,
		badgeVariant: "secondary",
		description: "Being run now.",
	},
	waiting: {
		label: "Waiting",
		short: "Waiting",
		icon: ClockIcon,
		badgeVariant: "secondary",
		description: "Waiting for capacity.",
	},
	held: {
		label: "On hold for budget",
		short: "On hold",
		icon: PauseIcon,
		badgeVariant: "destructive",
		description: "The monthly AI budget is used up, so this review waits for it.",
	},
};

export function ReviewQueueNow({ workspaceSlug, state }: ReviewQueueNowProps) {
	let body: ReactNode;
	if (state.status === "error") {
		body = (
			<QueryErrorAlert
				error={state.error}
				title="We could not load the queue"
				onRetry={state.onRetry}
			/>
		);
	} else if (state.status === "loading") {
		body = (
			<div aria-busy="true" className="flex flex-col gap-2">
				<span className="sr-only">Loading the queue…</span>
				<Skeleton className="h-9 w-full" />
				<Skeleton className="h-4 w-40" />
			</div>
		);
	} else {
		body = <QueueFigures workspaceSlug={workspaceSlug} queue={state.queue} />;
	}
	return (
		<div role="region" aria-label="The queue" className="flex flex-col gap-3">
			<FigureCaption
				icon={ListOrderedIcon}
				info={
					<InfoTip label="About the queue">
						<InfoLines lines={QUEUE_INFO} />
					</InfoTip>
				}
			>
				Now
			</FigureCaption>
			{body}
		</div>
	);
}

/**
 * The (i) beside "Now": what each figure counts. It names no figure that no list opens exactly, so
 * neither the pace nor the number of workers.
 */
const QUEUE_INFO: readonly InfoLine[] = [
	{
		term: "Waiting",
		text: "counts reviews that wait for capacity now, a retry included, but not those on hold. The count opens them.",
	},
	{
		term: "Running",
		text: "counts reviews that run now. The count opens them.",
	},
	{
		term: "On hold for budget",
		text: "counts reviews that wait now because the monthly AI budget is used up. A hold is red. The count opens them.",
	},
	{
		term: "Oldest wait",
		text: "is how long the first waiting review has waited; from 30 minutes it is marked. It opens that review while the review is among the first in line. It is left out while nothing waits.",
	},
	{
		term: "Since / Waited",
		text: "is how long a review has run or waited. For a review on hold, it counts from its request.",
	},
];

function QueueFigures({ workspaceSlug, queue }: { workspaceSlug: string; queue: ReviewQueue }) {
	const total = queueTotal(queue);
	const rows = queue.head.slice(0, QUEUE_ROWS);
	const oldest = oldestWaitSeconds(queue);
	const figures: CardFigure[] = [
		{
			key: "waiting",
			term: "waiting",
			value: queue.waiting,
			none: queue.waiting === 0,
			link: queueLink(workspaceSlug, "WAITING", queue.waiting, "waiting"),
		},
		{
			key: "running",
			term: "running",
			value: queue.running,
			none: queue.running === 0,
			link: queueLink(workspaceSlug, "RUNNING", queue.running, "running"),
		},
		{
			key: "held",
			term: "on hold for budget",
			value: (
				<QueueHold show={queue.heldForBudget > 0} broken>
					{queue.heldForBudget}
				</QueueHold>
			),
			none: queue.heldForBudget === 0,
			// The link's name hides the hold's "Needs a look", so it says it too.
			link: queueLink(
				workspaceSlug,
				"HELD",
				queue.heldForBudget,
				"on hold for budget, needs a look",
			),
		},
	];
	// While nothing waits there is no oldest wait to tell: "0 waiting" says it already.
	if (oldest !== undefined) {
		const longest = longestWaiting(queue);
		figures.push({
			key: "oldest",
			term: "oldest wait",
			value: <QueueHold show={waitsLong(queue)}>{durationText(oldest)}</QueueHold>,
			// It counts no rows but times one review: the whole figure opens that review, so it reads
			// as the other figures that open something. Not among the head, it opens nothing.
			link:
				longest === undefined
					? undefined
					: {
							render: <DetailStackLink entry={reviewLevel(longest.reviewId)} single />,
							// The name says the figure too: the link's own name hides the hold's words.
							label: `Oldest wait ${durationText(oldest)}${waitsLong(queue) ? ", needs a look" : ""}: open that review`,
						},
		});
	}
	return (
		<>
			<FigureRow label="Queue now" figures={figures} />
			{total === 0 && <p className={OVERVIEW_TYPE.empty}>Nothing waits or runs.</p>}
			{rows.length > 0 && <QueueTable queue={queue} rows={rows} />}
			{total > rows.length && (
				<p className="text-sm">
					<InlineLink
						tone="count"
						render={
							<ReviewListLink
								workspaceSlug={workspaceSlug}
								// The queue's statuses, by review and not by job, so the link names no count: the
								// list need not hold as many reviews as the figures above add up to.
								destination={{ list: "reviews", search: { status: ["RUNNING", "QUEUED"] } }}
							/>
						}
					>
						See all
					</InlineLink>
				</p>
			)}
		</>
	);
}

/**
 * A figure of the queue as the link to the Reviews table filtered to the same queue state, with no
 * days: the queue is now. The list keeps the reviews whose job the figure counts, so it shows as
 * many rows as the figure says. Nothing of it opens nothing.
 */
function queueLink(
	workspaceSlug: string,
	queueState: QueueState,
	count: number,
	noun: string,
): CardFigure["link"] {
	if (count === 0) {
		return undefined;
	}
	return {
		render: (
			<ReviewListLink
				workspaceSlug={workspaceSlug}
				destination={{ list: "reviews", search: { queueState } }}
			/>
		),
		label: `${count} ${count === 1 ? "review" : "reviews"} ${noun}`,
	};
}

/**
 * The review the oldest wait belongs to: the first waiting review of the queue's head whose wait
 * began then; undefined when it is not among the head.
 */
function longestWaiting(queue: ReviewQueue): ReviewQueueItem | undefined {
	const since = queue.oldestWaitingSince?.getTime();
	return queue.head.find(
		(candidate) => queueItemState(candidate) === "waiting" && candidate.since.getTime() === since,
	);
}

/**
 * A figure that needs a look: the mark and weight, and the same said in words to a screen reader,
 * which hears neither. By the overview's colour rule (`review-attention-thresholds.ts`), only a
 * broken figure, reviews held for budget, is red; a long wait is advice, in the text's colour.
 */
function QueueHold({
	show,
	broken = false,
	children,
}: {
	show: boolean;
	/** Nothing runs until an admin acts: the mark is red. */
	broken?: boolean;
	children: ReactNode;
}) {
	if (!show) {
		return <span>{children}</span>;
	}
	return (
		<span className={cn("font-semibold", broken && statusToneClass("destructive"))}>
			<span className="sr-only">Needs a look: </span>
			<TriangleAlertIcon aria-hidden className="mr-1 inline size-3.5 align-[-0.125em]" />
			{children}
		</span>
	);
}

/** The heads of the queue's table, small and muted, so the rows carry the eye. */
const QUEUE_HEAD = cn("pb-1 font-medium", OVERVIEW_TYPE.caption);
const QUEUE_CELL = "py-0.5 align-middle";

/**
 * The first reviews in line as a small table: the work, which opens the review, the developer, the
 * state and how long it has run or waited. Each column lines up down the rows; the developer takes
 * what room is left and truncates first, so the time is never cut off. A held review's time runs
 * from its request, since its hold points at the next check of the cap.
 */
function QueueTable({ queue, rows }: { queue: ReviewQueue; rows: readonly ReviewQueueItem[] }) {
	return (
		<table aria-label="First in the queue" className="w-full text-sm">
			<thead>
				<tr className="border-b text-left">
					<th scope="col" className={cn(QUEUE_HEAD, "pr-4")}>
						Work
					</th>
					<th scope="col" className={cn(QUEUE_HEAD, "w-full pr-4")}>
						Developer
					</th>
					<th scope="col" className={cn(QUEUE_HEAD, "pr-4")}>
						State
					</th>
					<th scope="col" className={cn(QUEUE_HEAD, "text-right whitespace-nowrap")}>
						Since / Waited
					</th>
				</tr>
			</thead>
			<tbody>
				{rows.map((item) => (
					<QueueRow key={item.jobId} queue={queue} item={item} />
				))}
			</tbody>
		</table>
	);
}

function QueueRow({ queue, item }: { queue: ReviewQueue; item: ReviewQueueItem }) {
	const def = QUEUE_ITEM_DEFS[queueItemState(item)];
	const Icon = reviewedWorkIcon(item.item.kind, item.item.provider ?? undefined);
	const StateIcon = def.icon;
	return (
		<tr>
			<td className={cn(QUEUE_CELL, "pr-4 whitespace-nowrap")}>
				<InlineLink
					className="inline-flex items-center gap-1.5"
					title={item.item.title ?? undefined}
					render={<DetailStackLink entry={reviewLevel(item.reviewId)} single />}
				>
					<Icon className="size-3.5 shrink-0" />
					<span>{item.item.label}</span>
				</InlineLink>
			</td>
			{/* `max-w-0` lets the cell shrink below its words, so the name truncates, not the row. */}
			<td className={cn(QUEUE_CELL, "max-w-0 pr-4", OVERVIEW_TYPE.label)}>
				<span className="block truncate">
					{item.developer
						? (firstNonBlank(item.developer.displayName, item.developer.providerLogin) ?? "")
						: ""}
				</span>
			</td>
			<td className={cn(QUEUE_CELL, "pr-4 whitespace-nowrap", OVERVIEW_TYPE.label)}>
				<span className="inline-flex items-center gap-1.5" title={def.label}>
					<StateIcon
						aria-hidden
						className={cn("size-3.5 shrink-0", statusToneClass(def.badgeVariant))}
					/>
					{def.short}
				</span>
			</td>
			<td
				className={cn(QUEUE_CELL, "text-right whitespace-nowrap tabular-nums", OVERVIEW_TYPE.label)}
			>
				{durationText(secondsUntilRead(queue, item.since))}
			</td>
		</tr>
	);
}
