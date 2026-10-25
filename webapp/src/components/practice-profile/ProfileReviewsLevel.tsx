import { XIcon } from "lucide-react";
import { type ReactNode, useState } from "react";

import type { ItemRef } from "@/api/types.gen";
import {
	AppliedFacetPills,
	listFacet,
} from "@/components/admin/practice-reviews/AppliedFacetPills";
import {
	ReviewSelectionBar,
	type ReviewSelectionName,
} from "@/components/admin/practice-reviews/ReviewSelectionBar";
import {
	type ReviewSortItem,
	ReviewSortSelect,
} from "@/components/admin/practice-reviews/ReviewSortSelect";
import type { FacetOption } from "@/components/common/FacetMultiSelect";
import { type FilterOption, FilterToggle } from "@/components/common/FilterToggle";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { ResultCount } from "@/components/common/ResultCount";
import { statusFacetOptions } from "@/components/common/status-def";
import {
	DetailDrawerHeader,
	LevelPathRow,
} from "@/components/layout/detail-drawer/DetailDrawerHeader";
import type { LevelPath } from "@/components/layout/detail-drawer/DetailPath";
import {
	OWN_REVIEW_STATUS_DEFS,
	type ReviewStatus,
} from "@/components/practice-vocabulary/review-status-defs";
import { EarlierReviews } from "@/components/profile/ReviewFeed";
import type { ReviewFeedState } from "@/components/profile/reviews";
import { Button } from "@/components/ui/button";
import { DrawerBody, DrawerDescription, DrawerTitle } from "@/components/ui/drawer";
import { ITEM_KIND_VALUES, itemKindLabel, itemName } from "@/lib/item-kinds";
import { nonEmpty } from "@/lib/search-params";
import { hasText } from "@/lib/text";

import {
	PROFILE_REVIEWS_PAGE_SIZE,
	REVIEW_TIMEFRAMES,
	REVIEW_TIMEFRAME_DEFS,
	REVIEWS_OF_YOUR_WORK,
	type ReviewOrder,
	type ReviewTimeframe,
} from "./practice-profile-search";
import { ReviewsTable, ReviewsTableSkeleton } from "./ReviewsTable";

/** The choice that narrows nothing; it never reaches the address, where every review is the default. */
const ALL_TIME = "all";

const TIMEFRAME_OPTIONS: readonly FilterOption<ReviewTimeframe | typeof ALL_TIME>[] = [
	...REVIEW_TIMEFRAMES.map((value) => ({ value, label: REVIEW_TIMEFRAME_DEFS[value].label })),
	{ value: ALL_TIME, label: "All time" },
];

/**
 * The statuses as the developer's own review surfaces name them, with their icons. The list holds a
 * review only once it records something about the reader, so a waiting review is never in it.
 */
const STATUS_OPTIONS = statusFacetOptions(OWN_REVIEW_STATUS_DEFS).filter(
	(option) => option.value !== "QUEUED",
);

const NEWEST = "NEWEST";

const ORDER_ITEMS: readonly [ReviewSortItem<string>, ...ReviewSortItem<string>[]] = [
	{ value: NEWEST, label: "Newest first" },
	{ value: "OLDEST", label: "Oldest first" },
];

/**
 * The kinds of work to offer: the ones this build knows, the ones the list shows and any chosen,
 * for the reason `TraceKindFilter` gives.
 */
function kindOptions(
	reviews: readonly { review: { item: { kind: string } } }[],
	chosen: readonly string[] | undefined,
): FacetOption[] {
	const kinds = new Set([
		...ITEM_KIND_VALUES,
		...reviews.map((entry) => entry.review.item.kind),
		...(chosen ?? []),
	]);
	return [...kinds].map((kind) => ({ value: kind, label: itemKindLabel(kind, 2) }));
}

/**
 * The tracked work's name for the tracking bar: its own once a row or a Track press showed it; a
 * placeholder only while the list is still loading; otherwise the bar's general words, since no read
 * of the name is under way that a placeholder could wait for.
 */
function trackedName(item: ItemRef | undefined, loading: boolean): ReviewSelectionName {
	if (item !== undefined) {
		return { status: "ready", name: itemName(item) };
	}
	return loading ? { status: "loading" } : { status: "ready", name: "a piece of work" };
}

export interface ProfileReviewsLevelProps {
	nested?: boolean;
	path: LevelPath;
	feed: ReviewFeedState;
	onOpenReview: (reviewId: string) => void;
	/** The review whose level is open over this one. */
	openReviewId?: string;
	/** The kinds of work the list is narrowed to, or `undefined` for every kind. */
	kind?: string[];
	onKindChange: (kinds: string[] | undefined) => void;
	/** How far back the list reaches, or `undefined` for every review. */
	since?: ReviewTimeframe;
	onSinceChange: (since: ReviewTimeframe | undefined) => void;
	/** The statuses the list is narrowed to, or `undefined` for every status. */
	status?: ReviewStatus[];
	onStatusChange?: (statuses: ReviewStatus[] | undefined) => void;
	/** Oldest first, or `undefined` for newest first. */
	order?: ReviewOrder;
	onOrderChange?: (order: ReviewOrder | undefined) => void;
	/** How many reviews the filters keep, from the server; absent until the first page is in. */
	total?: number;
	/** The work whose reviews the list is narrowed to, by its id: a row's Track sets it. */
	trackedItemId?: string;
	onTrack?: (item: ItemRef | undefined) => void;
	onReviewNow?: (itemId: string) => void;
	requesting?: string;
	/**
	 * The way to the review a row's ask created, drawn by the route. The list shows a review only
	 * once it records something, so the new one may not be a row yet.
	 */
	requestOutcome?: ReactNode;
}

/**
 * The reviews that recorded something about the reader's own work, newest first, as a level over
 * the practice profile. The server lists a review once it records something about the reader, so
 * one still running may not be here yet.
 */
export function ProfileReviewsLevel({
	nested,
	path,
	feed,
	onOpenReview,
	openReviewId,
	kind,
	onKindChange,
	since,
	onSinceChange,
	status,
	onStatusChange,
	order,
	onOrderChange,
	total,
	trackedItemId,
	onTrack,
	onReviewNow,
	requesting,
	requestOutcome,
}: ProfileReviewsLevelProps) {
	const reviews = feed.status === "ready" ? feed.reviews : [];
	const filtered =
		kind !== undefined || since !== undefined || status !== undefined || hasText(trackedItemId);
	// The admin Reviews tab's facets, as chips: the control and its pills on a phone, from one place.
	const kindFacet = listFacet("Work", kindOptions(reviews, kind), kind, (values) =>
		onKindChange(nonEmpty(values)),
	);
	const statusFacet = listFacet("Status", STATUS_OPTIONS, status, (values) =>
		onStatusChange?.(nonEmpty(values)),
	);
	// The tracked work's name, from its rows: every row of the narrowed list is that work. Kept once
	// seen, so a filter that leaves none of its reviews does not turn the name back into a load.
	const rowItem = reviews.find((entry) => entry.review.item.id === trackedItemId)?.review.item;
	const [seenItem, setSeenItem] = useState<ItemRef>();
	if (rowItem !== undefined && rowItem !== seenItem) {
		setSeenItem(rowItem);
	}
	const trackedItem = rowItem ?? (seenItem?.id === trackedItemId ? seenItem : undefined);

	let body: ReactNode;
	switch (feed.status) {
		case "error": {
			body = (
				<QueryErrorAlert
					error={feed.error}
					title="We could not load reviews of your work"
					onRetry={feed.onRetry}
				/>
			);
			break;
		}
		case "loading": {
			body = <ReviewsTableSkeleton rows={PROFILE_REVIEWS_PAGE_SIZE} />;
			break;
		}
		case "ready": {
			body = (
				<>
					<ReviewsTable
						reviews={feed.reviews}
						// Oldest first, the top row is not the latest review either.
						filtered={filtered || order !== undefined}
						onOpenReview={onOpenReview}
						openReviewId={openReviewId}
						onReviewNow={onReviewNow}
						requesting={requesting}
						isLoadingMore={feed.isLoadingMore}
						trackedItemId={trackedItemId}
						onTrack={
							onTrack &&
							((item) => {
								if (item !== undefined) {
									setSeenItem(item);
								}
								onTrack(item);
							})
						}
					/>
					{/* The table draws the next page's placeholder rows itself, in its own columns. */}
					<EarlierReviews {...feed} />
				</>
			);
			break;
		}
	}

	return (
		<>
			<DetailDrawerHeader nested={nested} dismiss="inPath">
				<div className="flex min-w-0 flex-1 flex-col gap-2">
					<LevelPathRow {...path} current="Reviews" />
					<DrawerTitle className="text-2xl font-semibold tracking-tight break-words">
						{REVIEWS_OF_YOUR_WORK}
					</DrawerTitle>
					<DrawerDescription className="max-w-2xl">
						Reviews that recorded something about your work,{" "}
						{order === "OLDEST" ? "oldest first" : "newest first"}. A review that is still running
						appears once it records something.
					</DrawerDescription>
				</div>
			</DetailDrawerHeader>
			<DrawerBody className="flex flex-col gap-3 pt-2">
				{/* The filter bar of the admin Reviews tab, in a developer's subset: the facets as chips,
				    the timeframe as Practices across the workspace picks it, the sort, Reset, and the
				    count. */}
				<div role="group" aria-label="Filters" className="space-y-2">
					<div className="flex flex-wrap items-center gap-2">
						{kindFacet.control}
						{onStatusChange !== undefined && statusFacet.control}

						{onOrderChange !== undefined && (
							<ReviewSortSelect
								items={ORDER_ITEMS}
								value={order ?? NEWEST}
								onChange={(next) => onOrderChange(next === "OLDEST" ? "OLDEST" : undefined)}
							/>
						)}
						{filtered && (
							<Button
								variant="ghost"
								size="sm"
								className="h-8"
								onClick={() => {
									onKindChange(undefined);
									onSinceChange(undefined);
									onStatusChange?.(undefined);
									onTrack?.(undefined);
								}}
							>
								Reset
								<XIcon aria-hidden data-icon="inline-end" />
							</Button>
						)}
						{/* On the right, the span the list covers, then how many reviews it keeps. */}
						<div className="flex basis-full flex-wrap items-center gap-3 sm:ml-auto sm:basis-auto">
							<FilterToggle
								label="Timeframe"
								options={TIMEFRAME_OPTIONS}
								value={since ?? ALL_TIME}
								onChange={(next) => onSinceChange(next === ALL_TIME ? undefined : next)}
							/>
							<ResultCount total={total} noun={["review", "reviews"]} hasFilter={filtered} />
						</div>
					</div>
					<div className="flex flex-wrap gap-2 empty:hidden">
						<AppliedFacetPills pills={[...kindFacet.pills, ...statusFacet.pills]} />
					</div>
				</div>
				{hasText(trackedItemId) && onTrack !== undefined && (
					// The admin lists' tracking bar: what the list is narrowed to, and one press to stop.
					<ReviewSelectionBar
						kind="item"
						name={trackedName(trackedItem, feed.status === "loading")}
						onClear={() => onTrack(undefined)}
					/>
				)}
				{requestOutcome}
				{body}
			</DrawerBody>
		</>
	);
}
