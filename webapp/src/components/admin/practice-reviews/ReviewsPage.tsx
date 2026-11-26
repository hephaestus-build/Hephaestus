import { WorkflowIcon } from "lucide-react";
import type { ReactNode } from "react";
import { ReviewListRefresh } from "./ReviewListRefresh";

import type { ItemRef, ReviewSummary } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { ReviewDayTable, ReviewDayTableSkeleton } from "@/components/practice-trace/ReviewDayTable";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import { itemName } from "@/lib/item-kinds";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import { REQUEST_BLOCKED, type RowReviewRequest } from "./RequestReviewAction";
import type { PracticeFacetOption } from "./ResultFilters";
import { clearedScope } from "./review-scope";
import { REVIEW_PAGE_SIZE, type ReviewsSearch } from "./review-search";
import {
	clearedReviewFacets,
	clearedReviewFilters,
	hasReviewFilter,
	ReviewFilters,
} from "./ReviewFilters";
import { loadingMoreRows, ReviewListEnd } from "./ReviewListEnd";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";
import { REVIEW_SUMMARY_COLUMNS, ReviewSummaryRow } from "./ReviewSummaryRow";
import { ReviewTabIntro } from "./ReviewTabIntro";

export interface ReviewRequestActions {
	canRequest: (item: ItemRef) => boolean;
	onRequest: (itemId: string) => void;
	isRequesting: (itemId: string) => boolean;
}

export function rowReviewRequest(
	actions: ReviewRequestActions,
	item: ItemRef,
	activeItems: ReadonlySet<string>,
): RowReviewRequest {
	const work = itemName(item);
	if (!actions.canRequest(item)) {
		return { work, blocked: REQUEST_BLOCKED.kind };
	}
	return {
		work,
		onRequest: () => actions.onRequest(item.id),
		requesting: actions.isRequesting(item.id),
		blocked: activeItems.has(item.id) ? REQUEST_BLOCKED.active : undefined,
	};
}

function activeItemsOf(reviews: readonly ReviewSummary[]): ReadonlySet<string> {
	return new Set(
		reviews.flatMap((review) =>
			review.status === "QUEUED" || review.status === "RUNNING" ? [review.item.id] : [],
		),
	);
}

export interface ReviewsPageProps {
	search: ReviewsSearch;
	onSearchChange: (patch: Partial<ReviewsSearch>) => void;
	reviews: PagedListState<ReviewSummary>;
	requestReview?: ReviewRequestActions;
	/**
	 * The answer to the last ask from a row, drawn by the route: why it started nothing, or the way to
	 * the review it created, which the filtered list may not show.
	 */
	requestOutcome?: ReactNode;
	record?: ScopeRecordFilter;
	groups: FacetSource;
	practices: FacetSource<PracticeFacetOption>;
	openReviewId?: string;
}

function listLabel(order: ReviewsSearch["order"]): string {
	return order === "OLDEST" ? "Practice reviews, oldest first" : "Practice reviews, newest first";
}

// Created dates include queue time; Requested would mislabel reviews started by other origins.
const TIME_LABEL = "Created";

export function ReviewsPage({
	search,
	onSearchChange,
	reviews,
	requestReview,
	requestOutcome,
	record,
	groups,
	practices,
	openReviewId,
}: ReviewsPageProps) {
	const rows = reviews.status === "ready" ? reviews.rows : [];
	const activeItems = activeItemsOf(rows);
	const hasFilter = hasReviewFilter(search);
	const reset = () => onSearchChange(clearedReviewFilters());
	const label = listLabel(search.order);
	let results: ReactNode;
	if (reviews.status === "error") {
		results = (
			<QueryErrorAlert
				error={reviews.error}
				title="We could not load reviews"
				onRetry={reviews.onRetry}
			/>
		);
	} else if (reviews.status === "loading") {
		results = (
			<ReviewDayTableSkeleton
				aria-label={label}
				timeLabel={TIME_LABEL}
				columns={REVIEW_SUMMARY_COLUMNS}
				rows={REVIEW_PAGE_SIZE}
			/>
		);
	} else {
		results = (
			<>
				<ReviewListRefresh failure={reviews.refreshFailure} noun="reviews" />
				<ReviewDayTable
					aria-label={label}
					timeLabel={TIME_LABEL}
					columns={REVIEW_SUMMARY_COLUMNS}
					reviews={rows}
					dateOf={(review) => review.createdAt}
					reviewKey={(review) => review.id}
					loadingMore={loadingMoreRows(reviews)}
					renderRow={(review) => (
						<ReviewSummaryRow
							review={review}
							open={review.id === openReviewId}
							request={requestReview && rowReviewRequest(requestReview, review.item, activeItems)}
						/>
					)}
					empty={
						<Empty>
							<EmptyHeader>
								<EmptyMedia variant="icon">
									<WorkflowIcon />
								</EmptyMedia>
								<EmptyTitle>
									{hasFilter ? "No reviews match these filters" : "No reviews yet"}
								</EmptyTitle>
								<EmptyDescription>
									{hasFilter
										? "Change or clear the filters to see more."
										: "Reviews appear when an event on the work starts one or someone requests one."}
								</EmptyDescription>
							</EmptyHeader>
							{hasFilter && (
								<EmptyContent>
									<Button variant="outline" size="sm" onClick={reset}>
										Clear all filters
									</Button>
								</EmptyContent>
							)}
						</Empty>
					}
				/>
				{rows.length > 0 && <ReviewListEnd {...reviews} noun="reviews" />}
			</>
		);
	}

	return (
		<section aria-label="Practice reviews" className="space-y-4">
			<ReviewTabIntro tab="reviews" />
			<ReviewFilters
				record={record}
				search={search}
				onPatch={onSearchChange}
				onReset={() => onSearchChange(clearedReviewFacets())}
				onClearScope={() => onSearchChange(clearedScope())}
				groups={groups}
				practices={practices}
				total={reviews.status === "ready" ? reviews.total : undefined}
			/>
			{requestOutcome}
			{results}
		</section>
	);
}
