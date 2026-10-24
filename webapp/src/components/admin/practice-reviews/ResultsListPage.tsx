import type { ReactNode } from "react";
import { ReviewListRefresh } from "./ReviewListRefresh";

import type { ReviewResult } from "@/api/types.gen";
import type { FacetSource } from "@/components/common/FacetMultiSelect";
import type { PagedListState } from "@/runtime/tanstack-query/infinite-list";

import {
	clearedResultFacets,
	clearedResultFilters,
	hasResultFilter,
	ResultFilters,
} from "./ResultFilters";
import { ResultRows, type ResultRowsState } from "./ResultRows";
import { clearedScope } from "./review-scope";
import type { ResultsSearch } from "./review-search";
import { loadingMoreRows, ReviewListEnd } from "./ReviewListEnd";
import type { ReviewPeople } from "./ReviewPersonFacet";
import type { ScopeRecordFilter } from "./ReviewScopeFilters";
import { ReviewTabIntro } from "./ReviewTabIntro";

export interface ResultsListPageProps {
	search: ResultsSearch;
	onSearchChange: (patch: Partial<ResultsSearch>) => void;
	results: PagedListState<ReviewResult>;
	groups: FacetSource;
	practices: FacetSource;
	people: ReviewPeople;
	record?: ScopeRecordFilter;
	summary?: ReactNode;
}

function resultsState(
	rows: ReviewResult[],
	onClearFilters: (() => void) | undefined,
): ResultRowsState {
	if (rows.length > 0) {
		return { status: "ready", results: rows };
	}
	return onClearFilters
		? { status: "empty", filtered: true, onClearFilters }
		: { status: "empty", filtered: false };
}

export function ResultsListPage({
	search,
	onSearchChange,
	results,
	groups,
	practices,
	people,
	record,
	summary,
}: ResultsListPageProps) {
	const rows = results.status === "ready" ? results.rows : [];
	// Only a developer filter makes the first row's name safe to use for that developer.
	const filteredDeveloper = search.developerId === undefined ? undefined : rows[0]?.developer;
	const hasFilter = hasResultFilter(search);
	const reset = () => onSearchChange(clearedResultFilters());
	const byDay = search.order !== "ACTIONABILITY";

	return (
		<section aria-label="Practice review observations" className="space-y-4">
			<ReviewTabIntro tab="results" />
			<ResultFilters
				search={search}
				onPatch={onSearchChange}
				onReset={() => onSearchChange(clearedResultFacets())}
				// "Most actionable first" is this list's own, not the scope's, so it outlasts "Clear scope".
				onClearScope={() =>
					onSearchChange({
						...clearedScope(),
						order: search.order === "ACTIONABILITY" ? search.order : undefined,
					})
				}
				groups={groups}
				practices={practices}
				people={people}
				total={results.status === "ready" ? results.total : undefined}
				subjectName={
					filteredDeveloper?.displayName ?? filteredDeveloper?.providerLogin ?? undefined
				}
				record={record}
			/>
			{summary}
			{results.status === "error" && (
				<ResultRows state={{ status: "error", error: results.error, onRetry: results.onRetry }} />
			)}
			{results.status === "loading" && <ResultRows state={{ status: "loading" }} />}
			{results.status === "ready" && (
				<>
					<ReviewListRefresh failure={results.refreshFailure} noun="observations" />
					<ResultRows
						state={resultsState(rows, hasFilter ? reset : undefined)}
						byDay={byDay}
						loadingMore={loadingMoreRows(results)}
					/>
					{rows.length > 0 && <ReviewListEnd {...results} noun="observations" />}
				</>
			)}
		</section>
	);
}
