import { Link } from "@tanstack/react-router";
import { WorkflowIcon } from "lucide-react";

import type { ListPracticeReviewsResponse } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";
import { REVIEW_STATUS_DEFS } from "@/components/practice-vocabulary/review-status-defs";
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
	// Page one, because a narrowed list is a different list: page 4 of the old one is very likely
	// past the end of the new one. The screen owns the URL, so the screen owns this — the toolbar
	// reports the facet the reader changed and nothing else.
	const patchFilter = (patch: Partial<RunsSearch>) => onSearchChange({ ...patch, page: 0 });

	return (
		<section aria-label="Practice reviews" className="space-y-4">
			<ReviewRunFilters
				search={search}
				onPatch={patchFilter}
				onReset={reset}
				total={reviews?.page?.totalElements}
			/>
			{error == null ? (
				isLoading ? (
					<ReviewResultsSkeleton label="Loading reviews" rows={REVIEW_PAGE_SIZE} />
				) : rows.length === 0 ? (
					<Empty variant="outlined">
						<EmptyHeader>
							<EmptyMedia variant="icon">
								<WorkflowIcon />
							</EmptyMedia>
							<EmptyTitle>No reviews found</EmptyTitle>
							<EmptyDescription>
								{/* A range can empty this list too, so "never triggered" is not the only reason and
							    must not be said to a reader who has just picked a window. */}
								{hasFilter
									? search.status && !search.from && !search.to
										? `No review is ${REVIEW_STATUS_DEFS[search.status].label.toLowerCase()}. Other reviews may exist under another status.`
										: "No review matches these filters. Other reviews may exist outside them."
									: "Reviews appear when an enabled practice is triggered or a contributor requests one."}
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
				) : (
					<ReviewRowList label="Practice reviews, newest first">
						{rows.map((review) => (
							<ReviewRunRow
								key={review.id}
								workspaceSlug={workspaceSlug}
								review={review}
								search={search}
							/>
						))}
					</ReviewRowList>
				)
			) : (
				<QueryErrorAlert error={error} title="Couldn't load reviews" onRetry={onRetry} />
			)}
			<TablePagination
				page={reviews?.page?.number ?? search.page ?? 0}
				totalPages={reviews?.page?.totalPages ?? 0}
				renderPageLink={(page, props) => (
					<Link
						{...props}
						to="/w/$workspaceSlug/admin/practices/reviews"
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
