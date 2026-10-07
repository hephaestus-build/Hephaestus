import type { ReviewFeedback } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import { QueryErrorAlert } from "@/components/common/QueryErrorAlert";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import { clearedFeedbackFilters, FeedbackFilters, hasFeedbackFilter } from "./FeedbackFilters";
import { FeedbackResults, type FeedbackResultsState } from "./FeedbackResults";
import type { FeedbackSearch } from "./review-search";
import { ReviewListEnd } from "./ReviewListEnd";
import type { ReviewPeople } from "./ReviewPersonFacet";

export interface FeedbackListPageProps {
	search: FeedbackSearch;
	onSearchChange: (patch: Partial<FeedbackSearch>) => void;
	/** The feedback the current `search` selects, as far as it is loaded. */
	feedback: PagedListState<ReviewFeedback>;
	practices: FacetSource;
	people: ReviewPeople;
}

function resultsState(
	rows: ReviewFeedback[],
	onClearFilters: (() => void) | undefined,
): FeedbackResultsState {
	if (rows.length > 0) {
		return { status: "ready", feedback: rows };
	}
	return onClearFilters
		? { status: "empty", filtered: true, onClearFilters }
		: { status: "empty", filtered: false };
}

export function FeedbackListPage({
	search,
	onSearchChange,
	feedback,
	practices,
	people,
}: FeedbackListPageProps) {
	const rows = feedback.status === "ready" ? feedback.rows : [];
	// Guarded on the filter being set: see `ObservationsListPage`. Unfiltered, row zero is whoever
	// sorts first, and their name would be shown against a different person's id.
	const filteredRecipient = search.recipientUserId == null ? undefined : rows[0]?.recipient;
	const hasFilter = hasFeedbackFilter(search);
	const reset = () => onSearchChange(clearedFeedbackFilters());

	return (
		<section aria-label="Feedback" className="space-y-4">
			<FeedbackFilters
				search={search}
				onPatch={onSearchChange}
				onReset={reset}
				practices={practices}
				people={people}
				total={feedback.status === "ready" ? feedback.total : undefined}
				scopedWork={rows[0]?.reviewedWork}
				recipientName={filteredRecipient?.name ?? filteredRecipient?.login}
			/>
			{feedback.status === "error" && (
				<QueryErrorAlert
					error={feedback.error}
					title="We could not load feedback"
					onRetry={feedback.onRetry}
				/>
			)}
			{feedback.status === "loading" && <FeedbackResults state={{ status: "loading" }} />}
			{feedback.status === "ready" && (
				<>
					<FeedbackResults state={resultsState(rows, hasFilter ? reset : undefined)} />
					<ReviewListEnd {...feedback} noun="feedback" />
				</>
			)}
		</section>
	);
}
