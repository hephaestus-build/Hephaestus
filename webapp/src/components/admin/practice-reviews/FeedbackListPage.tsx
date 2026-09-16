import { Link } from "@tanstack/react-router";

import type { ListPracticeReviewFeedbackResponse } from "@/api/types.gen";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import { TablePagination } from "@/components/common/TablePagination";

import { clearedFeedbackFilters, FeedbackFilters, hasFeedbackFilter } from "./FeedbackFilters";
import { FeedbackResults, type FeedbackResultsState } from "./FeedbackResults";
import type { FeedbackSearch } from "./review-search";
import type { ReviewPeople } from "./ReviewPersonFacet";

export interface FeedbackListPageProps {
	workspaceSlug: string;
	search: FeedbackSearch;
	onSearchChange: (patch: Partial<FeedbackSearch>) => void;
	/** The page of feedback the current `search` selects, or `undefined` while it is unknown. */
	feedback: ListPracticeReviewFeedbackResponse | undefined;
	isLoading: boolean;
	error: unknown;
	onRetry?: () => void;
	people: ReviewPeople;
}

export function FeedbackListPage({
	workspaceSlug,
	search,
	onSearchChange,
	feedback,
	isLoading,
	error,
	onRetry,
	people,
}: FeedbackListPageProps) {
	const rows = feedback?.content ?? [];
	// Guarded on the filter being set: see `ObservationsListPage`. Unfiltered, row zero is whoever
	// sorts first, and their name would be shown against a different person's id.
	const filteredRecipient = search.recipientUserId == null ? undefined : rows[0]?.recipient;
	const hasFilter = hasFeedbackFilter(search);
	const reset = () => onSearchChange(clearedFeedbackFilters());
	const patchFilter = (patch: Partial<FeedbackSearch>) => onSearchChange({ ...patch, page: 0 });
	let resultsState: FeedbackResultsState = { status: "ready", feedback: rows };
	if (isLoading) {
		resultsState = { status: "loading" };
	} else if (rows.length === 0) {
		resultsState = hasFilter
			? { status: "empty", filtered: true, onClearFilters: reset }
			: { status: "empty", filtered: false };
	}

	return (
		<section aria-label="Feedback delivery" className="space-y-4">
			<FeedbackFilters
				search={search}
				onPatch={patchFilter}
				onReset={reset}
				people={people}
				total={feedback?.page?.totalElements}
				scopedArtifact={rows[0]?.artifact}
				recipientName={filteredRecipient?.name ?? filteredRecipient?.login}
			/>
			{error == null ? (
				<FeedbackResults workspaceSlug={workspaceSlug} state={resultsState} />
			) : (
				<QueryErrorAlert error={error} title="Couldn't load feedback" onRetry={onRetry} />
			)}
			<TablePagination
				page={feedback?.page?.number ?? search.page ?? 0}
				totalPages={feedback?.page?.totalPages ?? 0}
				renderPageLink={(page, props) => (
					<Link
						{...props}
						to="/w/$workspaceSlug/admin/practices/reviews/delivery"
						params={{ workspaceSlug }}
						search={(previous) => ({ ...previous, page: page === 0 ? undefined : page })}
					/>
				)}
			/>
		</section>
	);
}
