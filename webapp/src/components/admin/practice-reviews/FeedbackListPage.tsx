import type { ReasonCount, ReviewFeedback } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import type { PanelState } from "@/components/common/panel-state";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import { FeedbackDeveloperSummary } from "./FeedbackDeveloperSummary";
import {
	clearedFeedbackFacets,
	clearedFeedbackFilters,
	FeedbackFilters,
	hasFeedbackFilter,
} from "./FeedbackFilters";
import { FeedbackResults, type FeedbackResultsState } from "./FeedbackResults";
import { clearedScope } from "./review-scope";
import type { FeedbackSearch } from "./review-search";
import { loadingMoreRows, ReviewListEnd } from "./ReviewListEnd";
import { ReviewListRefresh } from "./ReviewListRefresh";
import type { ReviewPeople } from "./ReviewPersonFacet";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";
import { ReviewTabIntro } from "./ReviewTabIntro";

export interface FeedbackListPageProps {
	workspaceSlug: string;
	search: FeedbackSearch;
	onSearchChange: (patch: Partial<FeedbackSearch>) => void;
	/** The feedback the current `search` selects, as far as it is loaded. */
	feedback: PagedListState<ReviewFeedback>;
	groups: FacetSource;
	practices: FacetSource;
	people: ReviewPeople;
	developerCounts: PanelState<{ counts: ReasonCount[] }>;
	/** The one record the scope is filtered to, by name, for its pill. */
	record?: ScopeRecordFilter;
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
	workspaceSlug,
	search,
	onSearchChange,
	feedback,
	groups,
	practices,
	people,
	developerCounts,
	record,
}: FeedbackListPageProps) {
	const rows = feedback.status === "ready" ? feedback.rows : [];
	// Guarded on the filter being set: see `ResultsListPage`. Unfiltered, row zero is whoever
	// sorts first, and their name would be shown against a different person's id.
	const filteredDeveloper = search.developerId === undefined ? undefined : rows[0]?.person;
	const developerName =
		filteredDeveloper?.displayName ??
		filteredDeveloper?.providerLogin ??
		people.options.find((person) => person.accountId === search.developerId)?.label;
	const hasFilter = hasFeedbackFilter(search);
	const reset = () => onSearchChange(clearedFeedbackFilters());

	return (
		<section aria-label="Feedback" className="space-y-4">
			<ReviewTabIntro tab="feedback" />
			<FeedbackFilters
				search={search}
				onPatch={onSearchChange}
				onReset={() => onSearchChange(clearedFeedbackFacets())}
				onClearScope={() => onSearchChange(clearedScope())}
				groups={groups}
				practices={practices}
				people={people}
				total={feedback.status === "ready" ? feedback.total : undefined}
				developerName={developerName}
				record={record}
			/>
			{search.developerId !== undefined && (
				<FeedbackDeveloperSummary
					workspaceSlug={workspaceSlug}
					developerId={search.developerId}
					developerName={developerName}
					state={developerCounts}
				/>
			)}
			{feedback.status === "error" && (
				<FeedbackResults
					state={{ status: "error", error: feedback.error, onRetry: feedback.onRetry }}
				/>
			)}
			{feedback.status === "loading" && <FeedbackResults state={{ status: "loading" }} />}
			{feedback.status === "ready" && (
				<>
					<ReviewListRefresh failure={feedback.refreshFailure} noun="feedback" />
					<FeedbackResults
						state={resultsState(rows, hasFilter ? reset : undefined)}
						loadingMore={loadingMoreRows(feedback)}
					/>
					{rows.length > 0 && <ReviewListEnd {...feedback} noun="feedback" />}
				</>
			)}
		</section>
	);
}
