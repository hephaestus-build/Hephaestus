import { Link } from "@tanstack/react-router";
import { WorkflowIcon } from "lucide-react";
import type { ReactNode } from "react";

import type { ListPracticeReviewsResponse } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";
import { Button } from "@/components/ui/button";
import {
	Empty,
	EmptyContent,
	EmptyDescription,
	EmptyHeader,
	EmptyMedia,
	EmptyTitle,
} from "@/components/ui/empty";

import { REVIEW_PAGE_SIZE, type RunsSearch } from "./review-search";
import { ReviewResultsSkeleton } from "./ReviewResultsSkeleton";
import { ReviewRowList } from "./ReviewRow";
import { clearedRunFilters, hasRunFilter, ReviewRunFilters } from "./ReviewRunFilters";
import { ReviewRunRow } from "./ReviewRunRow";

export interface ReviewRunsPageProps {
	workspaceSlug: string;
	search: RunsSearch;
	onSearchChange: (patch: Partial<RunsSearch>) => void;
	/** The page of reviews the current search asked for. Absent until the first answer arrives. */
	reviews: ListPracticeReviewsResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry: () => void;
}

/**
 * The list of reviews, given its page of results. It neither fetches nor polls: the route asks for
 * the page the URL names and keeps asking while a review is still running, and this screen only ever
 * sees the answer — which is why a still-running review looks the same here as anywhere else.
 */
export function ReviewRunsPage({
	workspaceSlug,
	search,
	onSearchChange,
	reviews,
	isLoading,
	error,
	onRetry,
}: ReviewRunsPageProps) {
	const rows = reviews?.content ?? [];
	const hasFilter = hasRunFilter(search);
	// The toolbar's Reset and the empty state's button are one action, not two copies of it.
	const reset = () => onSearchChange(clearedRunFilters());
	const emptyDescription = hasFilter
		? "No review matches these filters. Other reviews may exist outside them."
		: "Reviews appear when an enabled practice is triggered or a contributor requests one.";
	let results: ReactNode;
	if (error != null) {
		results = <QueryErrorAlert error={error} title="Couldn't load reviews" onRetry={onRetry} />;
	} else if (isLoading) {
		results = <ReviewResultsSkeleton label="Loading reviews" rows={REVIEW_PAGE_SIZE} />;
	} else if (rows.length === 0) {
		results = (
			<Empty variant="outlined">
				<EmptyHeader>
					<EmptyMedia variant="icon">
						<WorkflowIcon />
					</EmptyMedia>
					<EmptyTitle>No reviews found</EmptyTitle>
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
			<ReviewRowList label="Practice reviews, newest first">
				{rows.map((review) => (
					<ReviewRunRow key={review.id} review={review} />
				))}
			</ReviewRowList>
		);
	}

	return (
		<section aria-label="Practice reviews" className="space-y-4">
			<ReviewRunFilters
				search={search}
				onPatch={onSearchChange}
				onReset={reset}
				total={reviews?.page?.totalElements}
			/>
			{results}
			<TablePagination
				page={reviews?.page?.number ?? search.page ?? 0}
				totalPages={reviews?.page?.totalPages ?? 0}
				renderPageLink={(page, props) => (
					<Link
						{...props}
						to="/w/$workspaceSlug/admin/practices/reviews/runs"
						params={{ workspaceSlug }}
						// Spread rather than list the filters: page 2 of a filtered list has to stay
						// filtered, and naming them one by one is what silently dropped the next one.
						search={{ ...search, page: page === 0 ? undefined : page }}
					/>
				)}
			/>
		</section>
	);
}
