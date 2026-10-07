import { WorkflowIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { ReviewRunSummary } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import { REVIEW_PAGE_SIZE, type RunsSearch } from "./review-search";
import { ReviewListEnd } from "./ReviewListEnd";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";
import { clearedRunFilters, hasRunFilter, ReviewRunFilters } from "./ReviewRunFilters";
import { ReviewRunRow } from "./ReviewRunRow";

export interface ReviewRunsPageProps {
	search: RunsSearch;
	onSearchChange: (patch: Partial<RunsSearch>) => void;
	/** The reviews the current search selects, as far as they are loaded. */
	reviews: PagedListState<ReviewRunSummary>;
}

/**
 * The list of reviews, given its loaded pages. It neither fetches nor polls: the route asks for the
 * next page when the end of the list asks for it and keeps asking while a review is still running,
 * and this screen only ever sees the answer — which is why a still-running review looks the same
 * here as anywhere else.
 */
export function ReviewRunsPage({ search, onSearchChange, reviews }: ReviewRunsPageProps) {
	const rows = reviews.status === "ready" ? reviews.rows : [];
	const hasFilter = hasRunFilter(search);
	// The toolbar's Reset and the empty state's button are one action, not two copies of it.
	const reset = () => onSearchChange(clearedRunFilters());
	const emptyDescription = hasFilter
		? "Change or clear the filters to see more."
		: "Reviews appear when an enabled practice is triggered or a contributor requests one.";
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
		results = <ReviewResultsSkeleton label="Loading reviews" rows={REVIEW_PAGE_SIZE} />;
	} else if (rows.length === 0) {
		results = (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<WorkflowIcon />
					</EmptyMedia>
					<EmptyTitle>{hasFilter ? "No reviews match these filters" : "No reviews yet"}</EmptyTitle>
					<EmptyDescription>{emptyDescription}</EmptyDescription>
				</EmptyHeader>
				{hasFilter && (
					<EmptyContent>
						<Button variant="outline" size="sm" onClick={reset}>
							Clear all filters
						</Button>
					</EmptyContent>
				)}
			</Empty>
		);
	} else {
		results = (
			<>
				<ReviewRowList label="Practice reviews, newest first">
					{rows.map((review) => (
						<ReviewRunRow key={review.id} review={review} />
					))}
				</ReviewRowList>
				<ReviewListEnd {...reviews} noun="reviews" />
			</>
		);
	}

	return (
		<section aria-label="Practice reviews" className="space-y-4">
			<ReviewRunFilters
				search={search}
				onPatch={onSearchChange}
				onReset={reset}
				total={reviews.status === "ready" ? reviews.total : undefined}
			/>
			{results}
		</section>
	);
}
