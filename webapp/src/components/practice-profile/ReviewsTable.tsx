import { PulseIcon } from "@primer/octicons-react";
import { CrosshairIcon, PlayIcon } from "lucide-react";
import type { ReactElement } from "react";

import { cn } from "cn";

import type { ItemRef, ProfileReview, ReviewResultCounts } from "@/api/types.gen";
import { ActionButton } from "@/components/common/ActionButton";
import { LOADING_MORE_ROWS } from "@/components/common/InfiniteListEnd";
import { useNow } from "@/components/common/use-now";
import { OUTCOME_COUNT_KEYS, type ReviewSaw } from "@/components/practice-trace/review-saw";
import { RequestedReviewTag } from "@/components/practice-trace/review-tags";
import {
	type ReviewColumn,
	ReviewDayTable,
	ReviewDayTableSkeleton,
	ReviewItemCell,
	ReviewSawCell,
} from "@/components/practice-trace/ReviewDayTable";
import { OPEN_ROW_BAR, PracticeTableRow } from "@/components/practice-vocabulary/PracticeTable";
import { OWN_REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
import { ReviewStatusBadge } from "@/components/practice-vocabulary/ReviewStatusBadge";
import {
	Empty,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { Spinner } from "@/components/ui/spinner";
import { TableCell } from "@/components/ui/table";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatDayTime, formatTime } from "@/lib/dates";
import { hasText } from "@/lib/text";

import { REVIEWS_OF_YOUR_WORK } from "./practice-profile-search";

export interface ReviewsTableProps {
	reviews: ProfileReview[];
	/**
	 * Whether a filter narrows the list: the newest row is then not the latest review, and an empty
	 * list says nothing about whether any review exists.
	 */
	filtered: boolean;
	onOpenReview: (reviewId: string) => void;
	openReviewId?: string;
	onReviewNow?: (itemId: string) => void;
	requesting?: string;
	/** Whether the next page of reviews is on its way: placeholder rows then continue the table. */
	isLoadingMore?: boolean;
	/** The work whose reviews the list is narrowed to, by its id; its rows show Track pressed. */
	trackedItemId?: string;
	/**
	 * Narrows the list to every review of one work, or lets go of it; without it no row offers
	 * Track. A row offers it when its work has more than one review.
	 */
	onTrack?: (item: ItemRef | undefined) => void;
}

const COLUMNS: readonly ReviewColumn[] = [
	{ label: "What it saw" },
	{ label: "Actions", srOnly: true, className: "w-0" },
	{ label: "Open", srOnly: true, className: "w-40" },
];

/** The moment a review is listed at: when it finished, or when it was asked for while it runs. */
const reviewedAt = ({ review }: ProfileReview): Date => review.completedAt ?? review.createdAt;

function sawOf({ review, results }: ProfileReview): ReviewSaw {
	const counts: ReviewResultCounts = { met: 0, notMet: 0, doesNotApply: 0, couldNotDetermine: 0 };
	for (const { outcome } of results) {
		counts[OUTCOME_COUNT_KEYS[outcome]] += 1;
	}
	return { status: review.status, results: counts };
}

export function ReviewsTable({
	reviews,
	filtered,
	onOpenReview,
	openReviewId,
	onReviewNow,
	requesting,
	isLoadingMore = false,
	trackedItemId,
	onTrack,
}: ReviewsTableProps) {
	// The newest row of a narrowed list is only the newest of what the filter kept.
	const latestId = filtered ? undefined : reviews[0]?.review.id;
	const today = new Date(useNow());
	const names = controlNames(reviews, today);
	// Track narrows to a work's runs, so only a work with more than one review offers it.
	const trackable = (entry: ProfileReview) =>
		entry.totalOnWork > 1 || entry.review.item.id === trackedItemId;

	const offersRequest =
		onReviewNow !== undefined &&
		reviews.some((entry) => entry.review.kind === "ITEM" && isLatestOnWork(entry));
	const offersTrack = onTrack !== undefined && reviews.some(trackable);

	return (
		<>
			{(offersRequest || offersTrack) && (
				<RowActionsLegend request={offersRequest} track={offersTrack} />
			)}
			<ReviewDayTable
				aria-label={REVIEWS_OF_YOUR_WORK}
				columns={COLUMNS}
				reviews={reviews}
				dateOf={reviewedAt}
				reviewKey={(entry) => entry.review.id}
				// Every cell sits in the middle of its row, the controls too, so "Request review" stands at
				// one height in every row whatever the work's name wraps to.
				tableClassName={cn(ROWS_CENTERED, LATEST_ROW)}
				loadingMore={isLoadingMore ? LOADING_MORE_ROWS : undefined}
				renderRow={(entry) => (
					<ReviewRow
						entry={entry}
						isLatest={entry.review.id === latestId}
						open={entry.review.id === openReviewId}
						onOpenReview={onOpenReview}
						// Another review of a work is asked for from its latest review, the one it would follow.
						onReviewNow={isLatestOnWork(entry) ? onReviewNow : undefined}
						isRequesting={requesting !== undefined && requesting === entry.review.item.id}
						workName={names.work.get(entry.review.item.id) ?? entry.review.item.label}
						trackName={names.track.get(entry.review.id) ?? entry.review.item.label}
						track={
							onTrack !== undefined && trackable(entry)
								? {
										pressed: entry.review.item.id === trackedItemId,
										onPressedChange: (pressed) => onTrack(pressed ? entry.review.item : undefined),
									}
								: undefined
						}
					/>
				)}
				empty={
					filtered ? (
						<p className="text-sm text-muted-foreground">No reviews match your filters.</p>
					) : (
						<Empty>
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<PulseIcon />
								</EmptyMedia>
								<EmptyTitle>No reviews of your work yet</EmptyTitle>
								<EmptyDescription>
									A review appears here once it records something about your work: a pull or merge
									request, an issue, a conversation or a document.
								</EmptyDescription>
							</EmptyHeader>
						</Empty>
					)
				}
			/>
		</>
	);
}

/** Whether a row is its work's latest review, by the server's count of every review of the work. */
const isLatestOnWork = (entry: ProfileReview) => entry.positionOnWork === entry.totalOnWork;

/**
 * The names a row's buttons say after their words, one per control on the page. A work is named by
 * its number ("#19"), with its repository or channel where another work on the page has the same
 * number. Track is on every run of a work, so its name adds which run: "review 2 of 3".
 */
function controlNames(
	reviews: readonly ProfileReview[],
	today: Date,
): { work: Map<string, string>; track: Map<string, string> } {
	const itemsPerLabel = new Map<string, Set<string>>();
	for (const { review } of reviews) {
		const ids = itemsPerLabel.get(review.item.label) ?? new Set<string>();
		ids.add(review.item.id);
		itemsPerLabel.set(review.item.label, ids);
	}
	const work = new Map<string, string>();
	for (const { review } of reviews) {
		const { item } = review;
		const shared = (itemsPerLabel.get(item.label)?.size ?? 0) > 1;
		work.set(
			item.id,
			shared && hasText(item.container) ? `${item.label}, ${item.container}` : item.label,
		);
	}
	// Two works can still share a number and a repository's name at two providers: the provider
	// tells them apart, and a name that still repeats ends in its place among them.
	const worksPerName = new Map<string, ItemRef[]>();
	for (const { review } of reviews) {
		const name = work.get(review.item.id) ?? review.item.label;
		const items = worksPerName.get(name) ?? [];
		if (!items.some((known) => known.id === review.item.id)) {
			worksPerName.set(name, [...items, review.item]);
		}
	}
	for (const [name, items] of worksPerName) {
		if (items.length > 1) {
			for (const [index, item] of items.entries()) {
				const provider = item.provider === null ? undefined : PROVIDER_NAMES[item.provider];
				work.set(
					item.id,
					`${name}${provider === undefined ? "" : ` at ${provider}`} (${index + 1})`,
				);
			}
		}
	}
	// Every work's name is unique by now, and so is each run's place on its work.
	const track = new Map(
		reviews.map((entry) => {
			const run =
				entry.totalOnWork > 1
					? `review ${entry.positionOnWork} of ${entry.totalOnWork}`
					: formatDayTime(reviewedAt(entry), today);
			return [
				entry.review.id,
				`${work.get(entry.review.item.id) ?? entry.review.item.label}, ${run}`,
			] as const;
		}),
	);
	return { work, track };
}

/**
 * One quiet line over the table that says what each row button does, led by the button's icon:
 * the buttons carry only their icons, and a hover or focus on one names it again.
 */
function RowActionsLegend({ request, track }: { request: boolean; track: boolean }) {
	return (
		<p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
			{request && (
				<span className="inline-flex items-center gap-1.5">
					<PlayIcon aria-hidden className="size-3.5 shrink-0 text-foreground" />
					<span>
						<span className="font-medium text-foreground">{REQUEST_AGAIN}</span> of the work, from
						its latest review
					</span>
				</span>
			)}
			{track && (
				<span className="inline-flex items-center gap-1.5">
					<CrosshairIcon aria-hidden className="size-3.5 shrink-0 text-foreground" />
					<span>
						<span className="font-medium text-foreground">{TRACK_LABEL}</span>: show only its
						reviews
					</span>
				</span>
			)}
		</p>
	);
}

interface ReviewRowProps {
	entry: ProfileReview;
	isLatest: boolean;
	open: boolean;
	onOpenReview: (reviewId: string) => void;
	onReviewNow?: (itemId: string) => void;
	isRequesting: boolean;
	/** The work as the row's buttons name it, unique among the works on the page. */
	workName: string;
	/** What Track's name says after its words, unique among the rows on the page. */
	trackName: string;
	/** The row's Track toggle, where its work has other reviews to narrow to. */
	track?: { pressed: boolean; onPressedChange: (pressed: boolean) => void };
}

function ReviewRow({
	entry,
	isLatest,
	open,
	onOpenReview,
	onReviewNow,
	isRequesting,
	workName,
	trackName,
	track,
}: ReviewRowProps) {
	const { review } = entry;
	const { item } = review;
	const at = reviewedAt(entry);
	const today = new Date(useNow());

	return (
		<PracticeTableRow
			open={open}
			// The latest review stands framed in the accent, as a card's next step does, and says so in
			// its corner: no tag among the work's own.
			data-latest={isLatest || undefined}
			link={{
				// The review level is the list of every practice the review reached, so the link says so.
				text: OPEN_REVIEW_TEXT,
				name: `for ${item.label}, ${formatDayTime(at, today)}`,
				onOpen: () => onOpenReview(review.id),
			}}
		>
			{/* The status over the time it was reached at, as the workspace admin's list labels it, with
			    its sentence: one column for when and how the review ended. */}
			<TableCell className={cn(OPEN_ROW_BAR, "align-top whitespace-nowrap")}>
				{isLatest && (
					<span data-latest-label="" className={LATEST_LABEL}>
						{LATEST_REVIEW}
					</span>
				)}
				<span className="flex min-w-0 flex-col items-start gap-1.5">
					<ReviewStatusBadge def={OWN_REVIEW_STATUS_DEFS[review.status]} />
					<time dateTime={at.toISOString()} className="font-semibold">
						{formatTime(at)}
					</time>
				</span>
			</TableCell>
			{/* The sequence on the work and "Requested" share one line under the work. */}
			<ReviewItemCell
				item={item}
				position={entry.positionOnWork}
				total={entry.totalOnWork}
				tags={review.origin === "REQUEST" && <RequestedReviewTag />}
			/>
			<ReviewSawCell saw={sawOf(entry)} />
			<TableCell className="align-top whitespace-normal">
				{/* Icon buttons side by side, Request first so it stands at one place in every row that
				    offers it; each names itself on hover and focus, and the line over the table says
				    what both do. */}
				<span className="inline-flex items-center gap-2">
					{review.kind === "ITEM" && onReviewNow && (
						<RowActionTip label={isRequesting ? "Requesting review…" : REQUEST_AGAIN}>
							<ActionButton
								type="button"
								size="icon"
								disabled={isRequesting}
								onClick={() => onReviewNow(item.id)}
								aria-label={`${isRequesting ? "Requesting review…" : REQUEST_AGAIN}: ${workName}`}
							>
								{isRequesting ? <Spinner /> : <PlayIcon aria-hidden />}
							</ActionButton>
						</RowActionTip>
					)}
					{track !== undefined && (
						// The admin lists' Track, the crosshair that narrows the list to this work, drawn as
						// the button beside it is; pressed, it keeps the fill.
						<RowActionTip label={track.pressed ? "Stop tracking this work" : TRACK_LABEL}>
							<ActionButton
								type="button"
								size="icon"
								className={TRACK_PRESSED}
								// Each run of a work has its Track; `controlNames` tells them apart.
								aria-label={`${TRACK_LABEL}: ${trackName}`}
								aria-pressed={track.pressed}
								onClick={() => track.onPressedChange(!track.pressed)}
							>
								<CrosshairIcon aria-hidden />
							</ActionButton>
						</RowActionTip>
					)}
				</span>
			</TableCell>
		</PracticeTableRow>
	);
}

/** A row button's name on hover and focus, as the button carries only its icon. */
function RowActionTip({ label, children }: { label: string; children: ReactElement }) {
	return (
		<Tooltip>
			<TooltipTrigger render={children} />
			<TooltipContent>{label}</TooltipContent>
		</Tooltip>
	);
}

/**
 * Every row is a review of its work, so asking from a row asks for another one: the words say so.
 */
const REQUEST_AGAIN = "Request another review";

/** The words on a row's own link, which opens the review's level. */
export const OPEN_REVIEW_TEXT = "See the reviewed practices";

/** A provider as a work's name says it, where two works would otherwise share a name. */
const PROVIDER_NAMES: Partial<Record<NonNullable<ItemRef["provider"]>, string>> = {
	GITHUB: "GitHub",
	GITLAB: "GitLab",
};

/** The words in the latest review's corner. */
export const LATEST_REVIEW = "Latest review";

/**
 * The latest review's row (`data-latest`), framed: the accent outline and the calm mentor wash of a
 * card's next step, with room at the top of each cell for the words in its corner. Below the frame's
 * `@2xl` the row is a grid and takes the room itself. Drawn by the table, as a row owns no colour.
 */
const LATEST_ROW = [
	"[&_tr[data-latest]]:bg-mentor/5 dark:[&_tr[data-latest]]:bg-mentor/10",
	"[&_tr[data-latest]]:outline-1 [&_tr[data-latest]]:-outline-offset-1 [&_tr[data-latest]]:outline-mentor",
	"@2xl:[&_tr[data-latest]>td]:pt-7 @max-2xl:[&_tr[data-latest]]:pt-7",
].join(" ");

/** "Latest review", small in the row's top left corner, over the time cell's own content. */
const LATEST_LABEL =
	"absolute top-1.5 left-2 text-xs leading-none font-medium text-mentor @max-2xl:-top-5.5";

/**
 * A pressed Track keeps the primary fill a press shows. The outline button's own
 * `dark:aria-pressed:bg-muted` outranks the plain state, so dark mode names it again.
 */
const TRACK_PRESSED =
	"aria-pressed:border-primary aria-pressed:bg-primary aria-pressed:text-primary-foreground dark:aria-pressed:border-primary dark:aria-pressed:bg-primary";

/** The words on a row's Track toggle. */
export const TRACK_LABEL = "Track this work";

/** Centers every body cell, over the record table's own top alignment of its cells. */
const ROWS_CENTERED = "[&_tbody_td]:align-middle!";

export function ReviewsTableSkeleton({ rows }: { rows: number }) {
	return <ReviewDayTableSkeleton aria-label={REVIEWS_OF_YOUR_WORK} columns={COLUMNS} rows={rows} />;
}
